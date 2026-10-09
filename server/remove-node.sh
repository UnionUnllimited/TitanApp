#!/usr/bin/env bash
# Titan VPS: take our extra protocols off a node (the counterpart of install-node.sh).
# Leaves Remnawave's own node and Xray alone. Then delete this node's placeholder inbounds
# and hosts in Remnawave.
#
#   bash remove-node.sh            # keeps Caddy (it may serve something else)
#   REMOVE_CADDY=1 bash remove-node.sh
set -uo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }

units="titan-gateway titan-masque titan-mieru titan-node-sync.timer titan-node-sync
       masque lantern-box mita caddy-naive"
for u in $units; do
  systemctl disable --now "$u" >/dev/null 2>&1 && echo "stopped $u"
done
# Any other titan timers set up by hand earlier.
for t in $(systemctl list-unit-files 'titan*.timer' --no-legend 2>/dev/null | awk '{print $1}'); do
  systemctl disable --now "$t" >/dev/null 2>&1 && echo "stopped $t"
done
rm -f /etc/systemd/system/{titan-gateway,titan-masque,titan-mieru,titan-node-sync,masque,lantern-box,caddy-naive}.service \
      /etc/systemd/system/titan-node-sync.timer
systemctl daemon-reload
rm -rf /etc/titan-gateway /etc/titan-masque /etc/titan-mieru /etc/masque /etc/lantern-box /var/lib/titan-sync \
       /etc/titan-sync.env /etc/mita-server.json
rm -f /usr/local/bin/{titan-sbgw,titan-mieru-gw,titan-node-sync,xray-masque,sing-box-masque,lantern-box}
if [ "${REMOVE_CADDY:-}" = 1 ]; then
  systemctl disable --now caddy >/dev/null 2>&1; apt-get remove -y -qq caddy >/dev/null 2>&1
  rm -f /usr/local/bin/caddy /etc/systemd/system/caddy.service /etc/apt/sources.list.d/caddy-stable.list; systemctl daemon-reload
  echo "removed caddy"
fi
echo "done. Still listening on our old ports (should be nothing):"
ss -lntup | grep -E ':(2052|2083|2087|2095|2096|2222|3012[0-9]|30130) ' || echo "  nothing"
