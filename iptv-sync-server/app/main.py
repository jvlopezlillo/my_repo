"""Servidor de sincronización de listas IPTV (admin web + API para la app).

Variables de entorno:
  IPTV_ADMIN_PASSWORD  contraseña del administrador (obligatoria, mín. 10 caracteres)
  IPTV_DATA_DIR        carpeta de datos (por defecto ./data)
"""
import hashlib
import hmac
import os
import secrets
import sqlite3
import time
import uuid
from collections import defaultdict, deque
from contextlib import contextmanager
from pathlib import Path
from typing import Literal, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, ConfigDict, Field

DATA_DIR = Path(os.environ.get("IPTV_DATA_DIR", "./data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = DATA_DIR / "sync.db"
ADMIN_PASSWORD = os.environ.get("IPTV_ADMIN_PASSWORD", "")
if len(ADMIN_PASSWORD) < 10:
    raise RuntimeError("Define IPTV_ADMIN_PASSWORD con al menos 10 caracteres")

_secret_file = DATA_DIR / "secret"
if not _secret_file.exists():
    _secret_file.write_text(secrets.token_hex(32))
    _secret_file.chmod(0o600)
SECRET = _secret_file.read_text().strip().encode()

COOKIE = "iptv_admin"
SESSION_TTL = 7 * 24 * 3600
CODE_TTL = 15 * 60

app = FastAPI(title="IPTV Sync", docs_url=None, redoc_url=None, openapi_url=None)


# ───────────────────────── base de datos ─────────────────────────
@contextmanager
def db():
    con = sqlite3.connect(DB_PATH, timeout=10)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    try:
        yield con
        con.commit()
    finally:
        con.close()


with db() as _c:
    _c.executescript(
        """
        CREATE TABLE IF NOT EXISTS devices(
            id TEXT PRIMARY KEY, token_hash TEXT NOT NULL, name TEXT NOT NULL DEFAULT '',
            linked INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0,
            pair_code TEXT, pair_expires INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS playlists(
            device_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
            url TEXT NOT NULL DEFAULT '', server TEXT NOT NULL DEFAULT '', user TEXT NOT NULL DEFAULT '',
            pass TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(device_id, id),
            FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE);
        """
    )


def now_ms() -> int:
    return int(time.time() * 1000)


# ───────────────────────── límites de uso ─────────────────────────
_hits: dict[str, deque] = defaultdict(deque)


def rate_limit(request: Request, key: str, maximum: int, window: int):
    ip = request.client.host if request.client else "?"
    q = _hits[f"{key}:{ip}"]
    t = time.time()
    while q and q[0] < t - window:
        q.popleft()
    if len(q) >= maximum:
        raise HTTPException(429, "Demasiados intentos, espera un momento")
    q.append(t)


# ───────────────────────── modelos ─────────────────────────
class PlaylistIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    id: str = Field(min_length=1, max_length=64)
    name: str = Field(max_length=200)
    kind: Literal["M3U", "XTREAM"]
    url: str = Field("", max_length=2000)
    server: str = Field("", max_length=500)
    user: str = Field("", max_length=200)
    pass_: str = Field("", alias="pass", max_length=500)
    updatedAt: int
    deleted: bool = False


class SyncIn(BaseModel):
    playlists: list[PlaylistIn] = Field(max_length=300)


class PlaylistEdit(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    kind: Literal["M3U", "XTREAM"]
    url: str = Field("", max_length=2000)
    server: str = Field("", max_length=500)
    user: str = Field("", max_length=200)
    password: Optional[str] = None  # None = conservar la actual


def row_to_device_json(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"], "name": r["name"], "linked": bool(r["linked"]),
        "lastSeen": r["last_seen"], "createdAt": r["created_at"],
    }


# ───────────────────────── API del dispositivo (la app) ─────────────────────────
def _new_code(con: sqlite3.Connection) -> str:
    while True:
        code = f"{secrets.randbelow(10**6):06d}"
        row = con.execute("SELECT 1 FROM devices WHERE pair_code=? AND pair_expires>?", (code, now_ms())).fetchone()
        if not row:
            return code


def device_auth(authorization: str = Header(default="")) -> str:
    if not authorization.startswith("Bearer ") or "." not in authorization:
        raise HTTPException(401, "No autorizado")
    device_id, _, secret = authorization[7:].partition(".")
    with db() as con:
        r = con.execute("SELECT token_hash FROM devices WHERE id=?", (device_id,)).fetchone()
    given = hashlib.sha256(secret.encode()).hexdigest()
    if not r or not hmac.compare_digest(r["token_hash"], given):
        raise HTTPException(401, "Dispositivo desconocido o desvinculado")
    return device_id


@app.post("/api/device/register")
def register(request: Request):
    rate_limit(request, "register", 10, 3600)
    device_id = uuid.uuid4().hex
    secret = secrets.token_urlsafe(32)
    with db() as con:
        code = _new_code(con)
        con.execute(
            "INSERT INTO devices(id, token_hash, created_at, last_seen, pair_code, pair_expires) VALUES(?,?,?,?,?,?)",
            (device_id, hashlib.sha256(secret.encode()).hexdigest(), now_ms(), now_ms(), code, now_ms() + CODE_TTL * 1000),
        )
    return {"token": f"{device_id}.{secret}", "deviceId": device_id, "code": code, "expiresIn": CODE_TTL}


@app.post("/api/device/code")
def new_code(device_id: str = Depends(device_auth)):
    with db() as con:
        code = _new_code(con)
        con.execute("UPDATE devices SET pair_code=?, pair_expires=? WHERE id=?",
                    (code, now_ms() + CODE_TTL * 1000, device_id))
    return {"code": code, "expiresIn": CODE_TTL}


def _playlist_json(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"], "name": r["name"], "kind": r["kind"], "url": r["url"], "server": r["server"],
        "user": r["user"], "pass": r["pass"], "updatedAt": r["updated_at"], "deleted": bool(r["deleted"]),
    }


@app.post("/api/device/sync")
def sync(body: SyncIn, request: Request, device_id: str = Depends(device_auth)):
    """Fusión por elemento: gana el cambio con `updatedAt` mayor. Devuelve el estado completo."""
    rate_limit(request, "sync", 120, 3600)
    with db() as con:
        for p in body.playlists:
            cur = con.execute("SELECT updated_at FROM playlists WHERE device_id=? AND id=?", (device_id, p.id)).fetchone()
            if cur is None:
                con.execute(
                    "INSERT INTO playlists(device_id,id,name,kind,url,server,user,pass,updated_at,deleted) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (device_id, p.id, p.name, p.kind, p.url, p.server, p.user, p.pass_, p.updatedAt, int(p.deleted)),
                )
            elif p.updatedAt > cur["updated_at"]:
                con.execute(
                    "UPDATE playlists SET name=?,kind=?,url=?,server=?,user=?,pass=?,updated_at=?,deleted=? WHERE device_id=? AND id=?",
                    (p.name, p.kind, p.url, p.server, p.user, p.pass_, p.updatedAt, int(p.deleted), device_id, p.id),
                )
        con.execute("UPDATE devices SET last_seen=? WHERE id=?", (now_ms(), device_id))
        dev = con.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
        rows = con.execute("SELECT * FROM playlists WHERE device_id=?", (device_id,)).fetchall()
    code = dev["pair_code"] if (not dev["linked"] and dev["pair_expires"] > now_ms()) else None
    return {
        "playlists": [_playlist_json(r) for r in rows],
        "linked": bool(dev["linked"]), "name": dev["name"], "code": code, "serverTime": now_ms(),
    }


# ───────────────────────── sesión de administrador ─────────────────────────
def _sign(exp: int) -> str:
    return hmac.new(SECRET, f"admin:{exp}".encode(), hashlib.sha256).hexdigest()


def admin_auth(request: Request):
    tok = request.cookies.get(COOKIE, "")
    exp_s, _, sig = tok.partition(".")
    if not exp_s.isdigit() or int(exp_s) < time.time() or not hmac.compare_digest(sig, _sign(int(exp_s))):
        raise HTTPException(401, "Sesión no válida")


class Login(BaseModel):
    password: str = Field(max_length=500)


@app.post("/admin/api/login")
def login(body: Login, request: Request, response: Response):
    rate_limit(request, "login", 8, 600)
    if not hmac.compare_digest(body.password.encode(), ADMIN_PASSWORD.encode()):
        raise HTTPException(401, "Contraseña incorrecta")
    exp = int(time.time()) + SESSION_TTL
    response.set_cookie(
        COOKIE, f"{exp}.{_sign(exp)}", max_age=SESSION_TTL, httponly=True, samesite="strict",
        secure=request.url.scheme == "https",
    )
    return {"ok": True}


@app.post("/admin/api/logout")
def logout(response: Response):
    response.delete_cookie(COOKIE)
    return {"ok": True}


# ───────────────────────── API del administrador ─────────────────────────
@app.get("/admin/api/devices", dependencies=[Depends(admin_auth)])
def list_devices():
    with db() as con:
        rows = con.execute("SELECT * FROM devices WHERE linked=1 ORDER BY name").fetchall()
        out = []
        for r in rows:
            d = row_to_device_json(r)
            d["playlists"] = con.execute(
                "SELECT COUNT(*) FROM playlists WHERE device_id=? AND deleted=0", (r["id"],)).fetchone()[0]
            out.append(d)
    return out


class LinkIn(BaseModel):
    code: str = Field(pattern=r"^\d{6}$")
    name: str = Field(min_length=1, max_length=100)


@app.post("/admin/api/link", dependencies=[Depends(admin_auth)])
def link(body: LinkIn, request: Request):
    rate_limit(request, "link", 10, 600)
    with db() as con:
        r = con.execute("SELECT id FROM devices WHERE pair_code=? AND pair_expires>? AND linked=0",
                        (body.code, now_ms())).fetchone()
        if not r:
            raise HTTPException(404, "Código no válido o caducado")
        con.execute("UPDATE devices SET linked=1, name=?, pair_code=NULL WHERE id=?", (body.name, r["id"]))
    return {"id": r["id"]}


class RenameIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)


def _require_device(con: sqlite3.Connection, device_id: str) -> sqlite3.Row:
    r = con.execute("SELECT * FROM devices WHERE id=? AND linked=1", (device_id,)).fetchone()
    if not r:
        raise HTTPException(404, "Dispositivo no encontrado")
    return r


@app.patch("/admin/api/devices/{device_id}", dependencies=[Depends(admin_auth)])
def rename_device(device_id: str, body: RenameIn):
    with db() as con:
        _require_device(con, device_id)
        con.execute("UPDATE devices SET name=? WHERE id=?", (body.name, device_id))
    return {"ok": True}


@app.delete("/admin/api/devices/{device_id}", dependencies=[Depends(admin_auth)])
def delete_device(device_id: str):
    with db() as con:
        _require_device(con, device_id)
        con.execute("PRAGMA foreign_keys=ON")
        con.execute("DELETE FROM playlists WHERE device_id=?", (device_id,))
        con.execute("DELETE FROM devices WHERE id=?", (device_id,))
    return {"ok": True}


def _admin_playlist_json(r: sqlite3.Row) -> dict:
    # La contraseña nunca sale hacia la web: solo se indica si existe.
    return {
        "id": r["id"], "name": r["name"], "kind": r["kind"], "url": r["url"], "server": r["server"],
        "user": r["user"], "hasPass": bool(r["pass"]), "updatedAt": r["updated_at"],
    }


@app.get("/admin/api/devices/{device_id}/playlists", dependencies=[Depends(admin_auth)])
def admin_list_playlists(device_id: str):
    with db() as con:
        _require_device(con, device_id)
        rows = con.execute("SELECT * FROM playlists WHERE device_id=? AND deleted=0 ORDER BY name", (device_id,)).fetchall()
    return [_admin_playlist_json(r) for r in rows]


@app.post("/admin/api/devices/{device_id}/playlists", dependencies=[Depends(admin_auth)])
def admin_add_playlist(device_id: str, body: PlaylistEdit):
    pid = uuid.uuid4().hex
    with db() as con:
        _require_device(con, device_id)
        con.execute(
            "INSERT INTO playlists(device_id,id,name,kind,url,server,user,pass,updated_at,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
            (device_id, pid, body.name, body.kind, body.url.strip(), body.server.strip(), body.user.strip(),
             (body.password or "")[:500], now_ms()),
        )
    return {"id": pid}


@app.put("/admin/api/devices/{device_id}/playlists/{pid}", dependencies=[Depends(admin_auth)])
def admin_update_playlist(device_id: str, pid: str, body: PlaylistEdit):
    with db() as con:
        _require_device(con, device_id)
        cur = con.execute("SELECT * FROM playlists WHERE device_id=? AND id=? AND deleted=0", (device_id, pid)).fetchone()
        if not cur:
            raise HTTPException(404, "Lista no encontrada")
        password = cur["pass"] if body.password is None else body.password[:500]
        con.execute(
            "UPDATE playlists SET name=?,kind=?,url=?,server=?,user=?,pass=?,updated_at=? WHERE device_id=? AND id=?",
            (body.name, body.kind, body.url.strip(), body.server.strip(), body.user.strip(), password,
             max(now_ms(), cur["updated_at"] + 1), device_id, pid),
        )
    return {"ok": True}


@app.delete("/admin/api/devices/{device_id}/playlists/{pid}", dependencies=[Depends(admin_auth)])
def admin_delete_playlist(device_id: str, pid: str):
    with db() as con:
        _require_device(con, device_id)
        cur = con.execute("SELECT updated_at FROM playlists WHERE device_id=? AND id=?", (device_id, pid)).fetchone()
        if not cur:
            raise HTTPException(404, "Lista no encontrada")
        con.execute("UPDATE playlists SET deleted=1, updated_at=? WHERE device_id=? AND id=?",
                    (max(now_ms(), cur["updated_at"] + 1), device_id, pid))
    return {"ok": True}


# ───────────────────────── web de administración ─────────────────────────
_ADMIN_HTML = (Path(__file__).parent / "admin.html").read_text(encoding="utf-8")


@app.get("/admin", response_class=HTMLResponse)
def admin_page():
    return HTMLResponse(_ADMIN_HTML, headers={"Cache-Control": "no-store", "X-Frame-Options": "DENY"})


@app.get("/")
def root():
    return {"service": "iptv-sync"}
