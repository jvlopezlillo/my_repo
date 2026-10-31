#!/usr/bin/env python3
"""Fase 0 - validación contra un Fire TV Stick real.

Solo usa la librería estándar de Python. Requiere `adb` (y opcionalmente
`scrcpy` / scrcpy-server.jar) en el PC desde el que lo ejecutes.

Uso básico:
    python3 validate.py --host 100.64.0.10
    python3 validate.py --host 100.64.0.10 --apps com.netflix.ninja com.amazon.firetv.youtube

Qué comprueba (cada bloque es independiente; si uno falla, sigue con el resto):
  1. adb connect + modelo + versión de Fire OS / Android
  2. screencap (latencia, validez PNG, resolución)
  3. keyevent / tap / swipe, en launcher y en cada app indicada
  4. scrcpy-server: arranca y entrega H.264 por `adb forward`
  5. Resumen y diseño recomendado (también en phase0/out/report.json)

NO se expone nada a internet: todo va por la IP de Tailscale que indiques.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import socket
import statistics
import struct
import subprocess
import sys
import time
from pathlib import Path

# Apps por defecto (paquetes habituales en Fire TV; si no están instaladas se omiten).
DEFAULT_APPS = {
    "YouTube": ["com.amazon.firetv.youtube", "com.google.android.youtube.tv"],
    "Netflix": ["com.netflix.ninja"],
    "Prime Video": ["com.amazon.avod"],
}

KEY = {"HOME": 3, "BACK": 4, "DPAD_UP": 19, "DPAD_DOWN": 20, "DPAD_LEFT": 21,
       "DPAD_RIGHT": 22, "ENTER": 66, "MENU": 82, "PLAY_PAUSE": 85,
       "VOL_UP": 24, "VOL_DOWN": 25, "MUTE": 164}

RESULTS: dict = {"steps": {}}


# --------------------------------------------------------------------------- utilidades
class Adb:
    """Envoltorio mínimo de la CLI de adb apuntando a un único dispositivo."""

    def __init__(self, binary: str, serial: str):
        self.binary, self.serial = binary, serial

    def run(self, *args: str, timeout: float = 20) -> tuple[int, bytes, str, float]:
        """Ejecuta `adb -s SERIAL args...`. Devuelve (rc, stdout, stderr, segundos)."""
        t0 = time.perf_counter()
        try:
            p = subprocess.run([self.binary, "-s", self.serial, *args],
                               capture_output=True, timeout=timeout)
            return p.returncode, p.stdout, p.stderr.decode(errors="replace").strip(), time.perf_counter() - t0
        except subprocess.TimeoutExpired:
            return 124, b"", f"timeout tras {timeout}s", time.perf_counter() - t0

    def shell(self, cmd: str, timeout: float = 20) -> tuple[int, str, float]:
        rc, out, err, dt = self.run("shell", cmd, timeout=timeout)
        return rc, (out.decode(errors="replace") + err).strip(), dt

    def prop(self, name: str) -> str:
        return self.shell(f"getprop {name}")[1]


def step(n: int, title: str):
    print(f"\n=== {n}. {title} " + "=" * max(3, 60 - len(title)))


def ask(question: str, interactive: bool) -> bool | None:
    """Pregunta sí/no al usuario. None si no es interactivo."""
    if not interactive:
        return None
    while True:
        a = input(f"  ? {question} [s/n]: ").strip().lower()
        if a in ("s", "si", "sí", "y"):
            return True
        if a in ("n", "no"):
            return False


def png_size(data: bytes) -> tuple[int, int] | None:
    """Devuelve (ancho, alto) si `data` es un PNG válido, si no None."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", data[16:24])


def grab(adb: Adb) -> tuple[bytes, float]:
    rc, out, _, dt = adb.run("exec-out", "screencap", "-p", timeout=30)
    return (out if rc == 0 else b""), dt


# --------------------------------------------------------------------------- 1. conexión
def test_connect(adb: Adb, serial: str) -> bool:
    step(1, "Conexión ADB y datos del dispositivo")
    r = RESULTS["steps"]["connect"] = {"ok": False}
    for attempt in range(1, 4):  # reintentos: el primer connect tras mucho tiempo suele fallar
        p = subprocess.run([adb.binary, "connect", serial], capture_output=True, text=True, timeout=30)
        msg = (p.stdout + p.stderr).strip()
        print(f"  intento {attempt}: {msg}")
        if "connected" in msg and "unable" not in msg and "failed" not in msg:
            break
        time.sleep(2 * attempt)
    state = adb.run("get-state", timeout=10)[1].decode().strip()
    r["state"] = state
    if state != "device":
        hint = {"unauthorized": "Acepta el aviso '¿Permitir depuración USB?' en la tele (marca 'Permitir siempre').",
                "offline": "Reinicia el Fire Stick o ejecuta `adb kill-server`."}.get(
            state, "¿Fire Stick encendido, Tailscale activo y ADB por red activado (Opciones de desarrollador)?")
        print(f"  ✗ Estado ADB: '{state or 'sin respuesta'}'. {hint}")
        r["hint"] = hint
        return False
    props = {k: adb.prop(k) for k in (
        "ro.product.model", "ro.product.device", "ro.product.manufacturer",
        "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.name",
        "ro.build.version.number", "ro.build.display.id")}
    props["wm_size"] = adb.shell("wm size")[1]
    props["wm_density"] = adb.shell("wm density")[1]
    r.update(ok=True, props=props)
    print(f"  ✓ Modelo: {props['ro.product.model']} ({props['ro.product.device']})")
    print(f"  ✓ Android {props['ro.build.version.release']} (SDK {props['ro.build.version.sdk']})")
    print(f"  ✓ Fire OS: {props['ro.build.version.name'] or props['ro.build.display.id'] or '(no expuesto)'}")
    print(f"  ✓ Pantalla: {props['wm_size']} | {props['wm_density']}")
    return True


# --------------------------------------------------------------------------- 2. screencap
def test_screencap(adb: Adb, out: Path, runs: int):
    step(2, "Captura de pantalla (adb exec-out screencap -p)")
    r = RESULTS["steps"]["screencap"] = {"ok": False}
    times, sizes, last = [], [], b""
    for i in range(runs):
        data, dt = grab(adb)
        dims = png_size(data)
        if not dims:
            print(f"  ✗ captura {i + 1}: no es un PNG válido ({len(data)} bytes)")
            continue
        times.append(dt); sizes.append(len(data)); last = data
        print(f"  ✓ captura {i + 1}: {dt * 1000:.0f} ms, {len(data) / 1024:.0f} KB, {dims[0]}x{dims[1]}")
    if times:
        (out / "screencap.png").write_bytes(last)
        r.update(ok=True, resolution=png_size(last), median_ms=round(statistics.median(times) * 1000),
                 min_ms=round(min(times) * 1000), avg_kb=round(statistics.mean(sizes) / 1024))
        print(f"  → mediana {r['median_ms']} ms (~{1000 / max(r['median_ms'], 1):.1f} fps). Guardada en {out / 'screencap.png'}")
    else:
        print("  ✗ screencap no devuelve imagen. Posible: DRM/pantalla protegida o permisos.")


# --------------------------------------------------------------------------- 3. entrada
def find_package(adb: Adb, candidates: list[str]) -> str | None:
    installed = adb.shell("pm list packages")[1]
    for pkg in candidates:
        if f"package:{pkg}" in installed.splitlines():
            return pkg
    return None


def launch(adb: Adb, pkg: str):
    # monkey lanza la actividad LAUNCHER sin necesitar conocer su nombre.
    adb.shell(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1", timeout=20)


def test_input(adb: Adb, interactive: bool, apps: dict[str, list[str]], test_power: bool):
    step(3, "Entrada: keyevent, tap y swipe")
    r = RESULTS["steps"]["input"] = {"keys": {}, "tap": {}, "swipe": {}}
    # Resolución para elegir coordenadas.
    m = re.search(r"(\d+)x(\d+)", RESULTS["steps"]["connect"]["props"]["wm_size"])
    w, h = (int(m[1]), int(m[2])) if m else (1920, 1080)

    # --- keyevents: el rc==0 solo prueba que el comando se aceptó; confirmamos a ojo.
    print("  Probando teclas: HOME, DPAD_DOWN, DPAD_UP, VOL_UP, VOL_DOWN ...")
    for name in ("HOME", "DPAD_DOWN", "DPAD_UP", "VOL_UP", "VOL_DOWN"):
        rc, _, dt = adb.shell(f"input keyevent {KEY[name]}")
        r["keys"][name] = {"rc": rc, "ms": round(dt * 1000)}
        time.sleep(0.6)
    ok = ask("¿Has visto moverse el foco en la tele (HOME/flechas)?", interactive)
    r["keys_visible"] = ok
    vol = ask("¿Ha cambiado el volumen de la tele (barra de volumen)? (depende de HDMI-CEC)", interactive)
    r["volume_visible"] = vol
    if test_power:
        adb.shell(f"input keyevent 223"); time.sleep(3); adb.shell("input keyevent 224")
        r["keys"]["SLEEP/WAKEUP"] = {"tested": True}

    # --- tap/swipe por app. Un hash distinto de la captura antes/después indica que la UI cambió.
    targets = {"Launcher": None}
    for label, pkgs in apps.items():
        pkg = find_package(adb, pkgs)
        if pkg:
            targets[label] = pkg
        else:
            print(f"  · {label}: no instalada ({', '.join(pkgs)}), se omite")
    for label, pkg in targets.items():
        print(f"\n  --- {label} ---")
        if pkg:
            launch(adb, pkg); time.sleep(8)
        else:
            adb.shell(f"input keyevent {KEY['HOME']}"); time.sleep(2)
        before, _ = grab(adb)
        tx, ty = w // 2, h // 2
        adb.shell(f"input tap {tx} {ty}"); time.sleep(2.5)
        after_tap, _ = grab(adb)
        tap_changed = hashlib.md5(before).digest() != hashlib.md5(after_tap).digest()
        adb.shell(f"input swipe {w // 2} {int(h * .7)} {w // 2} {int(h * .3)} 300"); time.sleep(2)
        after_swipe, _ = grab(adb)
        swipe_changed = hashlib.md5(after_tap).digest() != hashlib.md5(after_swipe).digest()
        print(f"  tap en ({tx},{ty}): captura {'cambió' if tap_changed else 'IGUAL'}; "
              f"swipe vertical: captura {'cambió' if swipe_changed else 'IGUAL'}")
        # La captura puede cambiar por reproducción/animaciones; la confirmación humana es la fiable.
        tap_ok = ask(f"En {label}, ¿ha reaccionado la tele al TAP en el centro?", interactive)
        sw_ok = ask(f"En {label}, ¿ha reaccionado al SWIPE (scroll)?", interactive)
        r["tap"][label] = {"pixels_changed": tap_changed, "user_confirmed": tap_ok}
        r["swipe"][label] = {"pixels_changed": swipe_changed, "user_confirmed": sw_ok}
    adb.shell(f"input keyevent {KEY['HOME']}")


# --------------------------------------------------------------------------- 4. scrcpy
def locate_scrcpy_server(explicit: str | None) -> Path | None:
    cands = [explicit] if explicit else []
    exe = shutil.which("scrcpy")
    if exe:
        base = Path(exe).resolve().parent
        cands += [base / "scrcpy-server", base / "scrcpy-server.jar",
                  base.parent / "share/scrcpy/scrcpy-server"]
    cands += ["/usr/share/scrcpy/scrcpy-server", "/usr/local/share/scrcpy/scrcpy-server",
              "/opt/homebrew/share/scrcpy/scrcpy-server"]
    return next((Path(c) for c in cands if c and Path(c).is_file()), None)


def test_scrcpy(adb: Adb, serial: str, server_path: str | None, port: int):
    step(4, "scrcpy-server: vídeo H.264 por adb forward")
    r = RESULTS["steps"]["scrcpy"] = {"ok": False}
    exe = shutil.which("scrcpy")
    if not exe:
        print("  ✗ `scrcpy` no está en el PATH."); return
    version = (re.search(r"scrcpy\s+(\d+(?:\.\d+)+)", subprocess.run(
        [exe, "--version"], capture_output=True, text=True).stdout) or [None, None])[1]
    jar = locate_scrcpy_server(server_path)
    print(f"  scrcpy {version}; server: {jar}")
    if not (version and jar):
        print("  ✗ No encuentro scrcpy-server. Pásalo con --scrcpy-server RUTA (la versión debe coincidir)."); return
    r.update(version=version)
    # El servidor exige que el primer argumento sea EXACTAMENTE la versión del jar.
    adb.run("push", str(jar), "/data/local/tmp/scrcpy-server.jar", timeout=60)
    scid = "0000ab12"
    server = subprocess.Popen(
        [adb.binary, "-s", serial, "shell",
         "CLASSPATH=/data/local/tmp/scrcpy-server.jar", "app_process", "/", "com.genymobile.scrcpy.Server",
         version, f"scid={scid}", "log_level=info", "tunnel_forward=true", "audio=false", "control=false",
         "video_codec=h264", "max_size=1280", "max_fps=30", "video_bit_rate=4000000",
         "send_device_meta=false", "send_codec_meta=false", "send_frame_meta=false", "cleanup=false"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        adb.run("forward", f"tcp:{port}", f"localabstract:scrcpy_{scid}")
        sock, t0 = None, time.perf_counter()
        while time.perf_counter() - t0 < 12 and sock is None:  # el servidor tarda ~1-2 s en abrir el socket
            try:
                sock = socket.create_connection(("127.0.0.1", port), timeout=3)
                sock.settimeout(5)
                first = sock.recv(1)  # byte "dummy" propio del modo forward
                if not first:
                    sock.close(); sock = None; time.sleep(.5)
            except OSError:
                sock = None; time.sleep(.5)
        if sock is None:
            print("  ✗ No se pudo abrir el túnel de vídeo."); return
        first_t = None; total = 0; buf = b""; end = time.perf_counter() + 5
        while time.perf_counter() < end:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            if first_t is None:
                first_t = time.perf_counter() - t0
            total += len(chunk); buf += chunk if len(buf) < 4096 else b""
        nals = {b[0] & 0x1F for b in re.findall(rb"\x00\x00\x00\x01(.)", buf, re.S)}
        has_sps = 7 in nals
        if total:
            r.update(ok=has_sps, bytes=total, first_data_s=round(first_t, 2), kbps=round(total * 8 / 5 / 1000),
                     nal_types=sorted(nals))
            print(f"  {'✓' if has_sps else '?'} {total / 1024:.0f} KB en 5 s (~{r['kbps']} kbps), "
                  f"primer dato a los {first_t:.2f} s, NAL: {sorted(nals)} {'(SPS presente → H.264 válido)' if has_sps else ''}")
        else:
            print("  ✗ Conectó pero no llegó vídeo.")
    finally:
        server.terminate()
        try:
            log = server.stdout.read(2000).decode(errors="replace")
        except Exception:
            log = ""
        if not RESULTS["steps"]["scrcpy"].get("ok") and log:
            print("  Log del servidor:\n   " + log.strip().replace("\n", "\n   "))
            RESULTS["steps"]["scrcpy"]["server_log"] = log[-1000:]
        adb.run("forward", "--remove", f"tcp:{port}")


# --------------------------------------------------------------------------- 5. resumen
def recommend() -> list[str]:
    s, out = RESULTS["steps"], []
    if not s.get("connect", {}).get("ok"):
        return ["No hay conexión ADB: resuelve eso antes de diseñar nada (ver pista arriba)."]
    inp = s.get("input", {})
    taps = inp.get("tap", {})
    works = [k for k, v in taps.items() if v["user_confirmed"] or (v["user_confirmed"] is None and v["pixels_changed"])]
    fails = [k for k in taps if k not in works]
    if taps and not works:
        out.append("MODO MANDO por defecto: el tap no respondió en ninguna app. La UI usa flechas/Enter/Atrás.")
    elif fails:
        out.append(f"Modo ratón útil en: {', '.join(works)}. NO responde en: {', '.join(fails)} → "
                   "selector de modo (ratón/mando) en la UI, con modo mando como respaldo.")
    elif works:
        out.append("Modo ratón viable en todas las apps probadas; se mantiene el modo mando como alternativa.")
    sc, sp = s.get("screencap", {}), s.get("scrcpy", {})
    if sp.get("ok"):
        out.append("VÍDEO: scrcpy-server por adb forward + WebCodecs en el navegador (opción b). "
                   f"Medido ~{sp['kbps']} kbps. Control (taps) por ADB `input`, vídeo y control separados.")
    elif sc.get("ok"):
        why = "scrcpy falló" if "scrcpy" in s else "scrcpy no probado"
        out.append(f"VÍDEO: {why}; fallback a screencap (~{1000 / max(sc['median_ms'], 1):.1f} fps, "
                   f"{sc['median_ms']} ms/captura) con JPEG si baja el ancho de banda.")
    if sc.get("ok") is False:
        out.append("screencap no funciona: solo scrcpy podría dar imagen; si ambos fallan, solo modo mando.")
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", required=True, help="IP de Tailscale del Fire Stick (100.x.x.x[:5555])")
    ap.add_argument("--adb", default="adb", help="ruta al ejecutable adb")
    ap.add_argument("--apps", nargs="*", help="paquetes extra a probar (además de YouTube/Netflix/Prime)")
    ap.add_argument("--scrcpy-server", help="ruta a scrcpy-server(.jar) si no se autodetecta")
    ap.add_argument("--screencap-runs", type=int, default=5)
    ap.add_argument("--local-port", type=int, default=27183, help="puerto local para el adb forward")
    ap.add_argument("--non-interactive", action="store_true", help="no hacer preguntas (solo comparar píxeles)")
    ap.add_argument("--test-power", action="store_true", help="probar también SLEEP/WAKEUP (apaga la pantalla 3 s)")
    ap.add_argument("--skip", nargs="*", default=[], choices=["screencap", "input", "scrcpy"])
    a = ap.parse_args()

    if not shutil.which(a.adb):
        sys.exit(f"No encuentro '{a.adb}'. Instala platform-tools o usa --adb RUTA.")
    serial = a.host if ":" in a.host else f"{a.host}:5555"
    out = Path(__file__).parent / "out"; out.mkdir(exist_ok=True)
    apps = dict(DEFAULT_APPS)
    for i, pkg in enumerate(a.apps or []):
        apps[f"extra{i + 1}:{pkg}"] = [pkg]
    adb, interactive = Adb(a.adb, serial), not a.non_interactive

    if test_connect(adb, serial):
        if "screencap" not in a.skip: test_screencap(adb, out, a.screencap_runs)
        if "input" not in a.skip: test_input(adb, interactive, apps, a.test_power)
        if "scrcpy" not in a.skip: test_scrcpy(adb, serial, a.scrcpy_server, a.local_port)

    step(5, "Resumen y diseño propuesto")
    RESULTS["recommendation"] = recommend()
    for line in RESULTS["recommendation"]:
        print(f"  • {line}")
    (out / "report.json").write_text(json.dumps(RESULTS, indent=2, ensure_ascii=False, default=str))
    print(f"\nInforme completo: {out / 'report.json'}  (pégame su contenido o el resumen de arriba)")


if __name__ == "__main__":
    main()
