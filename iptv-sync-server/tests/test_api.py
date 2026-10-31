"""Prueba de integración: python tests/test_api.py (requiere fastapi + httpx)."""
import os, sys, tempfile
os.environ["IPTV_DATA_DIR"] = tempfile.mkdtemp()
os.environ["IPTV_ADMIN_PASSWORD"] = "clave-de-prueba-123"
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from fastapi.testclient import TestClient
from app.main import app

c = TestClient(app, base_url="https://t")
pl = lambda i, n, t, d=False: {"id": i, "name": n, "kind": "M3U", "url": "http://x/" + i, "updatedAt": t, "deleted": d}

# 1) registro + primera sincronización (el dispositivo sube sus listas locales)
r = c.post("/api/device/register").json()
H = {"Authorization": "Bearer " + r["token"]}
code = r["code"]; assert len(code) == 6
s = c.post("/api/device/sync", headers=H, json={"playlists": [pl("a", "Lista A", 100), pl("b", "Lista B", 100)]}).json()
assert not s["linked"] and s["code"] == code and len(s["playlists"]) == 2

# 2) el admin no puede entrar sin sesión; con mala contraseña falla; con la buena entra
assert c.get("/admin/api/devices").status_code == 401
assert c.post("/admin/api/login", json={"password": "mala"}).status_code == 401
assert c.post("/admin/api/login", json={"password": "clave-de-prueba-123"}).status_code == 200
assert c.get("/admin/api/devices").json() == []            # aún sin vincular: invisible

# 3) vincular con código (incorrecto y correcto)
assert c.post("/admin/api/link", json={"code": "000000", "name": "x"}).status_code == 404
assert c.post("/admin/api/link", json={"code": code, "name": "Firestick papá"}).status_code == 200
devs = c.get("/admin/api/devices").json(); assert devs[0]["name"] == "Firestick papá" and devs[0]["playlists"] == 2
did = devs[0]["id"]
# el código ya no sirve
assert c.post("/admin/api/link", json={"code": code, "name": "otro"}).status_code == 404

# 4) el admin ve las listas que el dispositivo ya tenía, sin contraseñas
lst = c.get(f"/admin/api/devices/{did}/playlists").json()
assert {p["name"] for p in lst} == {"Lista A", "Lista B"} and all("pass" not in p for p in lst)

# 5) el admin edita, añade y borra; el dispositivo lo recibe al sincronizar
a = next(p for p in lst if p["name"] == "Lista A")
assert c.put(f"/admin/api/devices/{did}/playlists/{a['id']}",
             json={"name": "A renombrada", "kind": "XTREAM", "server": "http://s", "user": "u", "password": "secreto"}).status_code == 200
nid = c.post(f"/admin/api/devices/{did}/playlists", json={"name": "Nueva", "kind": "M3U", "url": "http://n"}).json()["id"]
assert c.delete(f"/admin/api/devices/{did}/playlists/b").status_code == 200
s = c.post("/api/device/sync", headers=H, json={"playlists": []}).json()
assert s["linked"] and s["name"] == "Firestick papá" and s["code"] is None
by = {p["id"]: p for p in s["playlists"]}
assert by["a"]["name"] == "A renombrada" and by["a"]["pass"] == "secreto" and by["a"]["kind"] == "XTREAM"
assert by["b"]["deleted"] is True and nid in by and not by[nid]["deleted"]

# 6) conservar la contraseña al editar sin enviarla
c.put(f"/admin/api/devices/{did}/playlists/a", json={"name": "A2", "kind": "XTREAM", "server": "http://s", "user": "u"})
s = c.post("/api/device/sync", headers=H, json={"playlists": []}).json()
assert {p["id"]: p for p in s["playlists"]}["a"]["pass"] == "secreto"

# 7) conflicto: gana el cambio más reciente; un cambio antiguo del dispositivo se ignora
s = c.post("/api/device/sync", headers=H, json={"playlists": [pl("a", "vieja", 1)]}).json()
assert {p["id"]: p for p in s["playlists"]}["a"]["name"] == "A2"
s = c.post("/api/device/sync", headers=H, json={"playlists": [pl("a", "Editada en la tele", 2**50)]}).json()
assert {p["id"]: p for p in s["playlists"]}["a"]["name"] == "Editada en la tele"

# 8) el dispositivo borra una lista (tombstone) y el admin deja de verla
c.post("/api/device/sync", headers=H, json={"playlists": [pl("a", "Editada en la tele", 2**50 + 1, True)]})
assert "a" not in {p["id"] for p in c.get(f"/admin/api/devices/{did}/playlists").json()}

# 9) seguridad: token falso, aislamiento entre dispositivos y desvinculación
assert c.post("/api/device/sync", headers={"Authorization": "Bearer zzz.zzz"}, json={"playlists": []}).status_code == 401
r2 = c.post("/api/device/register").json(); H2 = {"Authorization": "Bearer " + r2["token"]}
assert c.post("/api/device/sync", headers=H2, json={"playlists": []}).json()["playlists"] == []
assert c.get("/admin").status_code == 200 and "Administración" in c.get("/admin").text
assert c.delete(f"/admin/api/devices/{did}").status_code == 200
assert c.post("/api/device/sync", headers=H, json={"playlists": []}).status_code == 401
c.post("/admin/api/logout"); c.cookies.clear()
assert c.get("/admin/api/devices").status_code == 401
print("OK: todas las comprobaciones pasan")
