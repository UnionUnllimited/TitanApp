#!/usr/bin/env bash
# Titan VPS: one command to turn a Remnawave node into a node for our extra protocols —
# Naive, TUIC, AnyTLS, ShadowTLS (titan-sbgw), MASQUE (Xray), Mieru + SSH (titan-mieru-gw) —
# with accounting through the node's Remnawave placeholders and users synced from
# Remnawave (titan-node-sync), all taking user changes without restarts.
#
#   DOMAIN=swe.example.com REMNAWAVE_URL=https://panel… REMNAWAVE_TOKEN=… bash install-node.sh
#
# DOMAIN must already point at this server (A record, no proxying), port 80 must be free
# (certificate). The Remnawave placeholders (Shadowsocks chacha20-ietf-poly1305 on
# 127.0.0.1, enabled on this node) are expected on these ports; override if yours differ:
#   P_NAIVE=61001 P_MIERU=61002 P_MASQUE=61004 P_TUIC=61005 P_ANYTLS=61006 P_SHADOWTLS=61007 P_SSH=61008
# Public ports: Naive 2096, TUIC 2083/udp, AnyTLS 2095, ShadowTLS 2052, MASQUE 2087 tcp+udp,
# Mieru 30120-30130, SSH 2222 (override with PORT_* the same way).
set -euo pipefail

: "${DOMAIN:?set DOMAIN: the name of this node, already pointing here}"
REPO="https://github.com/UnionUnllimited/TitanApp"
BRANCH="${BRANCH:-claude/exciting-ptolemy-oiy9r6}"
XRAY_VERSION="${XRAY_VERSION:-v26.9.30}"
SHADOWTLS_COVER="${SHADOWTLS_COVER:-www.amd.com}"   # the apps handshake with this site

P_NAIVE=${P_NAIVE:-61001}; P_MIERU=${P_MIERU:-61002}; P_MASQUE=${P_MASQUE:-61004}; P_TUIC=${P_TUIC:-61005}
P_ANYTLS=${P_ANYTLS:-61006}; P_SHADOWTLS=${P_SHADOWTLS:-61007}; P_SSH=${P_SSH:-61008}
PORT_NAIVE=${PORT_NAIVE:-2096}; PORT_TUIC=${PORT_TUIC:-2083}; PORT_ANYTLS=${PORT_ANYTLS:-2095}
PORT_SHADOWTLS=${PORT_SHADOWTLS:-2052}; PORT_MASQUE=${PORT_MASQUE:-2087}; PORT_SSH=${PORT_SSH:-2222}
PORT_MIERU=${PORT_MIERU:-30120-30130}

step() { printf '\n== %s\n' "$*"; }
fail() { printf '\n!! %s\n' "$*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail "run as root"

step "Checks"
# Remnawave credentials: from the environment, or an existing /etc/titan-sync.env.
if [ -z "${REMNAWAVE_URL:-}" ] && [ -f /etc/titan-sync.env ]; then
  REMNAWAVE_URL=$(sed -n 's/^REMNAWAVE_URL=//p' /etc/titan-sync.env)
  REMNAWAVE_TOKEN=$(sed -n 's/^REMNAWAVE_TOKEN=//p' /etc/titan-sync.env)
fi
: "${REMNAWAVE_URL:?set REMNAWAVE_URL}"; : "${REMNAWAVE_TOKEN:?set REMNAWAVE_TOKEN}"
busy=""
for p in 80 "$PORT_NAIVE" "$PORT_ANYTLS" "$PORT_SHADOWTLS" "$PORT_MASQUE" "$PORT_SSH" 30120; do
  owner=$(ss -Hltnp "sport = :$p" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2 || true)
  case "$owner" in ""|caddy|titan-sbgw|xray-masque|titan-mieru-gw) ;; *) busy="$busy $p($owner)";; esac
done
for p in "$PORT_TUIC" "$PORT_MASQUE"; do
  owner=$(ss -Hlunp "sport = :$p" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2 || true)
  case "$owner" in ""|titan-sbgw|xray-masque) ;; *) busy="$busy $p/udp($owner)";; esac
done
[ -z "$busy" ] || fail "ports in use:$busy — free them or set PORT_*"
for p in $P_NAIVE $P_MIERU $P_MASQUE $P_TUIC $P_ANYTLS $P_SHADOWTLS $P_SSH; do
  ss -Hltn "sport = :$p" | grep -q . || echo "   warning: nothing on 127.0.0.1:$p yet (Remnawave placeholder) — that protocol won't pass traffic until it's there"
done
echo "   ok"

step "Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl unzip python3 ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https >/dev/null
if ! command -v caddy >/dev/null; then
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq && apt-get install -y -qq caddy >/dev/null
fi

step "Certificate for $DOMAIN (Caddy, port 80; it keeps renewing it)"
cat > /etc/caddy/Caddyfile <<EOF
{
	http_port 80
	https_port 9443
}
$DOMAIN:9443 {
	bind 127.0.0.1
	respond "ok"
}
EOF
systemctl enable -q caddy && systemctl restart caddy
CERT=""
for _ in $(seq 1 60); do
  CERT=$(find /var/lib/caddy -name "$DOMAIN.crt" 2>/dev/null | head -1)
  [ -n "$CERT" ] && break
  sleep 3
done
[ -n "$CERT" ] || fail "no certificate after 3 min — is $DOMAIN pointing here (no proxy) and port 80 open? see: journalctl -u caddy"
KEY=${CERT%.crt}.key
echo "   $CERT"

step "Programs"
tmp=$(mktemp -d)
curl -fsSL "$REPO/releases/download/node-binaries/SHA256SUMS" -o "$tmp/SHA256SUMS"
for f in titan-sbgw-linux-amd64 titan-mieru-gw-linux-amd64; do
  curl -fsSL "$REPO/releases/download/node-binaries/$f" -o "$tmp/$f"
  (cd "$tmp" && grep " $f\$" SHA256SUMS | sha256sum -c - >/dev/null) || fail "checksum of $f"
done
install -m 755 "$tmp/titan-sbgw-linux-amd64" /usr/local/bin/titan-sbgw
install -m 755 "$tmp/titan-mieru-gw-linux-amd64" /usr/local/bin/titan-mieru-gw
curl -fsSL "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/Xray-linux-64.zip" -o "$tmp/xray.zip"
unzip -oq "$tmp/xray.zip" xray -d "$tmp" && install -m 755 "$tmp/xray" /usr/local/bin/xray-masque
curl -fsSL "https://raw.githubusercontent.com/UnionUnllimited/TitanApp/$BRANCH/server/titan-node-sync.py" -o /usr/local/bin/titan-node-sync
chmod 755 /usr/local/bin/titan-node-sync
rm -rf "$tmp"
echo "   $(xray-masque version | head -1)"

step "Templates"
mkdir -p /etc/titan-gateway /etc/titan-masque /etc/titan-mieru /var/lib/titan-sync
cat > /etc/titan-gateway/template.json <<EOF
{"log": {"level": "warn"},
 "titan": {"method": "chacha20-ietf-poly1305", "xray": {
   "naive-in": "127.0.0.1:$P_NAIVE", "tuic-in": "127.0.0.1:$P_TUIC",
   "anytls-in": "127.0.0.1:$P_ANYTLS", "stls-ss": "127.0.0.1:$P_SHADOWTLS"}},
 "inbounds": [
  {"type": "naive", "tag": "naive-in", "listen": "::", "listen_port": $PORT_NAIVE, "network": "tcp",
   "tls": {"enabled": true, "server_name": "$DOMAIN", "certificate_path": "$CERT", "key_path": "$KEY"}},
  {"type": "tuic", "tag": "tuic-in", "listen": "::", "listen_port": $PORT_TUIC, "congestion_control": "bbr",
   "tls": {"enabled": true, "server_name": "$DOMAIN", "alpn": ["h3"], "certificate_path": "$CERT", "key_path": "$KEY"}},
  {"type": "anytls", "tag": "anytls-in", "listen": "::", "listen_port": $PORT_ANYTLS,
   "tls": {"enabled": true, "server_name": "$DOMAIN", "certificate_path": "$CERT", "key_path": "$KEY"}},
  {"type": "shadowtls", "tag": "stls-in", "listen": "::", "listen_port": $PORT_SHADOWTLS,
   "handshake": {"server": "$SHADOWTLS_COVER", "server_port": 443}, "strict_mode": true, "detour": "stls-ss"},
  {"type": "shadowsocks", "tag": "stls-ss", "listen": "127.0.0.1", "listen_port": 61100}]}
EOF
cat > /etc/titan-masque/template.json <<EOF
{"log": {"loglevel": "error"},
 "titan": {"method": "chacha20-ietf-poly1305", "api": "127.0.0.1:61099",
   "xray": {"masque-h2": "127.0.0.1:$P_MASQUE", "masque-h3": "127.0.0.1:$P_MASQUE"}},
 "inbounds": [
  {"tag": "masque-h2", "listen": "0.0.0.0", "port": $PORT_MASQUE, "protocol": "masque",
   "settings": {"address": ["172.30.0.1/17", "fd30::1/65"], "mtu": 8000},
   "streamSettings": {"network": "masque", "security": "tls", "tlsSettings": {"alpn": ["h2"],
     "certificates": [{"certificateFile": "$CERT", "keyFile": "$KEY"}]}}},
  {"tag": "masque-h3", "listen": "0.0.0.0", "port": $PORT_MASQUE, "protocol": "masque",
   "settings": {"address": ["172.30.128.1/17", "fd30:0:0:0:8000::1/65"], "mtu": 1400},
   "streamSettings": {"network": "masque", "security": "tls", "tlsSettings": {"alpn": ["h3"],
     "certificates": [{"certificateFile": "$CERT", "keyFile": "$KEY"}]}}}],
 "outbounds": []}
EOF
cat > /etc/titan-mieru/template.json <<EOF
{"listen": "0.0.0.0", "portRange": "$PORT_MIERU", "transport": "TCP",
 "xray": "127.0.0.1:$P_MIERU", "method": "chacha20-ietf-poly1305",
 "ipsFile": "/var/lib/titan-sync/mieru-ips.json",
 "ssh": {"listen": "0.0.0.0:$PORT_SSH", "xray": "127.0.0.1:$P_SSH", "hostKey": "/etc/titan-mieru/ssh_host_ed25519"}}
EOF
umask 077
cat > /etc/titan-sync.env <<EOF
REMNAWAVE_URL=$REMNAWAVE_URL
REMNAWAVE_TOKEN=$REMNAWAVE_TOKEN
CREDENTIAL=ss
NAIVE_USERS_FILE=
MITA_CONFIG=
GATEWAY_USERS=/etc/titan-gateway/users.json
MASQUE_GATEWAY_TEMPLATE=/etc/titan-masque/template.json
MIERU_GATEWAY_TEMPLATE=/etc/titan-mieru/template.json
EOF
umask 022

step "Services"
unit() { # name description command
  cat > "/etc/systemd/system/$1.service" <<EOF
[Unit]
Description=$2
After=network-online.target
[Service]
ExecStart=$3
Restart=always
RestartSec=3
LimitNOFILE=1048576
[Install]
WantedBy=multi-user.target
EOF
}
unit titan-gateway "Titan gateway: Naive, TUIC, AnyTLS, ShadowTLS (accounted via Remnawave)" \
  "/usr/local/bin/titan-sbgw -config /etc/titan-gateway/template.json -users /etc/titan-gateway/users.json"
unit titan-masque "Titan gateway: MASQUE (accounted via Remnawave)" \
  "/usr/local/bin/xray-masque run -c /etc/titan-masque/config.json"
unit titan-mieru "Titan gateway: Mieru + SSH (accounted via Remnawave)" \
  "/usr/local/bin/titan-mieru-gw -config /etc/titan-mieru/config.json"
cat > /etc/systemd/system/titan-node-sync.service <<'EOF'
[Unit]
Description=Titan: users from Remnawave for the extra protocols
[Service]
Type=oneshot
ExecStart=/usr/local/bin/titan-node-sync
EOF
cat > /etc/systemd/system/titan-node-sync.timer <<'EOF'
[Unit]
Description=Titan: sync users every minute
[Timer]
OnBootSec=30
OnUnitActiveSec=60
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
echo "   first sync…"
titan-node-sync
systemctl enable -q --now titan-gateway titan-masque titan-mieru titan-node-sync.timer
systemctl restart titan-gateway titan-masque titan-mieru
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  for p in "$PORT_NAIVE/tcp" "$PORT_TUIC/udp" "$PORT_ANYTLS/tcp" "$PORT_SHADOWTLS/tcp" "$PORT_MASQUE" "$PORT_SSH/tcp" "${PORT_MIERU/-/:}/tcp" 80/tcp; do
    ufw allow "$p" >/dev/null
  done
  echo "   ufw: ports opened"
fi
sleep 4

step "Result"
systemctl is-active titan-gateway titan-masque titan-mieru titan-node-sync.timer | paste -sd' '
ss -lntup | grep -E ":($PORT_NAIVE|$PORT_TUIC|$PORT_ANYTLS|$PORT_SHADOWTLS|$PORT_MASQUE|$PORT_SSH|30120) " | awk '{print "   " $1, $5, $7}'
cat <<EOF

Done. In Remnawave, hosts on this node (address $DOMAIN), each on its placeholder inbound,
with the Host Mapper rule [{"op":"copy","from":"\$host.metadata.inboundTag","to":"titan"}]
(MASQUE over TCP: [{"op":"set","to":"titan","value":"masque-tcp"}]):
  Naive     $DOMAIN:$PORT_NAIVE       -> 127.0.0.1:$P_NAIVE
  TUIC      $DOMAIN:$PORT_TUIC        -> 127.0.0.1:$P_TUIC
  AnyTLS    $DOMAIN:$PORT_ANYTLS      -> 127.0.0.1:$P_ANYTLS
  ShadowTLS $DOMAIN:$PORT_SHADOWTLS   -> 127.0.0.1:$P_SHADOWTLS
  MASQUE    $DOMAIN:$PORT_MASQUE      -> 127.0.0.1:$P_MASQUE
  Mieru     $DOMAIN:${PORT_MIERU%%-*}     -> 127.0.0.1:$P_MIERU
  SSH       $DOMAIN:$PORT_SSH         -> 127.0.0.1:$P_SSH
EOF
