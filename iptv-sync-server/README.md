# IPTV Sync (servidor)

Servidor pequeño para gestionar en remoto las listas de las apps «IPTV TV»:
- **Web de administración** (`/admin`): ver, añadir, editar y borrar las listas de cada dispositivo vinculado.
- **API para la app**: la app sube sus listas, recibe los cambios y se sincroniza en los dos sentidos.
- Los dispositivos se vinculan con un **código de 6 cifras** que muestra la app (no se usa la MAC: Android no la expone y es fácil de adivinar).

Fusión de cambios: por cada lista gana la modificación más reciente (`updatedAt`). Un borrado es otra modificación más. Conviene que el reloj del servidor y de los dispositivos esté en hora (NTP).

## Instalar en Debian
```bash
sudo apt install python3 python3-venv
sudo useradd --system --home /opt/iptv-sync --shell /usr/sbin/nologin iptvsync
sudo mkdir -p /opt/iptv-sync /var/lib/iptv-sync
sudo cp -r app requirements.txt /opt/iptv-sync/
sudo python3 -m venv /opt/iptv-sync/.venv
sudo /opt/iptv-sync/.venv/bin/pip install -r /opt/iptv-sync/requirements.txt
sudo chown -R iptvsync:iptvsync /var/lib/iptv-sync

# Contraseña de administrador (mínimo 10 caracteres; usa una larga y única)
echo 'IPTV_ADMIN_PASSWORD=pon-aqui-una-clave-larga' | sudo tee /etc/iptv-sync.env
sudo chmod 600 /etc/iptv-sync.env

sudo cp deploy/iptv-sync.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now iptv-sync
curl http://127.0.0.1:8787/        # → {"service":"iptv-sync"}
```
El servicio escucha solo en `127.0.0.1:8787`; hay que publicarlo con HTTPS (abajo).

## Publicarlo en internet con HTTPS
El dispositivo de tu padre tiene que llegar al mini PC desde fuera de tu casa. Elige una:

**A) Cloudflare Tunnel (recomendada): sin abrir puertos en el router.** Necesitas un dominio gestionado en Cloudflare.
```bash
# instala cloudflared (guía oficial: developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads)
cloudflared tunnel login
cloudflared tunnel create iptv
cloudflared tunnel route dns iptv iptv.tudominio.com
# /etc/cloudflared/config.yml
#   tunnel: <ID-del-túnel>
#   credentials-file: /root/.cloudflared/<ID-del-túnel>.json
#   ingress:
#     - hostname: iptv.tudominio.com
#       service: http://127.0.0.1:8787
#     - service: http_status:404
sudo cloudflared service install
```
**B) Abrir el puerto 443 + Caddy** (solo si tu operadora te da IP pública, no CGNAT). Con un dominio o DuckDNS apuntando a tu IP:
```
# /etc/caddy/Caddyfile
iptv.tudominio.com {
    reverse_proxy 127.0.0.1:8787
}
```
y reenvía el puerto 443 del router al mini PC. Caddy obtiene el certificado solo.

**C) Tu VPS como puerta de entrada**: Caddy en el VPS con `reverse_proxy` hacia el mini PC por un túnel SSH inverso o WireGuard.

## Uso
1. Abre `https://iptv.tudominio.com/admin` e inicia sesión.
2. En la app, en el dispositivo: **Listas → Sincronización remota**, escribe la dirección del servidor y pulsa **Conectar**. Aparece un código de 6 cifras (caduca en 15 min).
3. En la web, escribe el código y un nombre («Firestick papá») → **Vincular**. Ya ves las listas que el dispositivo tenía y puedes editarlas.

## Seguridad y copias
- Las listas (incluidas usuario/contraseña de Xtream) se guardan **en claro** en `/var/lib/iptv-sync/sync.db` (permisos del usuario `iptvsync`). Protege el mini PC y haz copia de esa carpeta.
- La contraseña de las listas nunca se envía a la web de administración: solo se indica si existe; al editar, en blanco = conservar.
- Límites de intentos en login, registro y sincronización. Cookie de sesión `HttpOnly` + `SameSite=Strict`, `Secure` bajo HTTPS.
- Capa extra opcional: proteger `/admin` también en Caddy (`basicauth`) o con Cloudflare Access.

## Probar
```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt httpx
.venv/bin/python tests/test_api.py
```
