#!/usr/bin/env bash
# Activate (or roll back) the dsh-mobile-xc mobile UI plugin on the live DSH.
#
# Why a separate script: restarting deepseek-harness.service kills every process
# in that service's control group — including the agent turn that asked for the
# restart. This script is therefore started as a transient systemd unit
# (systemd-run --on-active), so it survives the restart it performs:
#
#   systemd-run --unit=dsh-mobile-restart-live --on-active=15 --collect \
#     "$(pwd)/tools/restart-live.sh"
#
# Guarantees:
#   1. restart the service, wait until 3080 (dsh web) and 3081 (dsh-passwords
#      gateway, spawned inside the same cgroup) answer again;
#   2. if dsh web does not come back within WAIT seconds, restore the profile's
#      package.json without dsh-mobile-xc AND cordis.patch.yml without the
#      mcp-phone entry, then restart again, so the user is never left with a
#      dead DSH;
#   3. report the phone bridge unit, its /healthz and a live MCP initialize
#      handshake before and after the restart;
#   4. append everything to docs/verification/last-restart.log.
#
# MODE=check probes health only and changes nothing.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT="${DSH_SERVICE:-deepseek-harness.service}"
PROFILE="${DSH_PROFILE_DIR:-${HOME}/.dsh/profiles/web}"
PKG="$PROFILE/package.json"
# Pre-change snapshot restored when the restart fails. Rollback files are named
# after what they *remove*, so prefer the most recent, most specific one: a
# mobile-presentation failure should not also tear down the xc mobile layout.
# Override with ROLLBACK_PKG=<path>.
ROLLBACK="${ROLLBACK_PKG:-}"
PATCH="$PROFILE/cordis.patch.yml"
PATCH_ROLLBACK="$PROFILE/cordis.patch.yml.rollback-no-mcp-phone"
BRIDGE_UNIT=dsh-mobile-bridge.service
OUT="$ROOT/docs/verification/last-restart.log"
MODE="${MODE:-restart}"
WAIT="${WAIT:-90}"

if [ -z "$ROLLBACK" ]; then
  for candidate in "$PROFILE/package.json.rollback-no-presentation" "$PROFILE/package.json.rollback-no-mobile-xc"; do
    if [ -f "$candidate" ]; then ROLLBACK="$candidate"; break; fi
  done
fi

mkdir -p "$(dirname "$OUT")"
exec >>"$OUT" 2>&1

log() { echo "[$(date -Is)] $*"; }
web_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:3080/ 2>/dev/null || echo 000; }
gw_ok() { curl -s --max-time 5 http://127.0.0.1:3081/gateway/healthz 2>/dev/null | grep -q '"ok":true'; }
bridge_ok() { curl -s --max-time 5 http://127.0.0.1:3082/healthz 2>/dev/null | grep -q '"ok":true'; }
mcp_ok() {
  curl -s --max-time 8 -X POST http://127.0.0.1:3082/mcp \
    -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"restart-live","version":"0"}}}' \
    2>/dev/null | grep -q '"serverInfo"'
}

wait_up() {
  local i j code
  for i in $(seq 1 "$WAIT"); do
    code=$(web_code)
    if [ "$code" != "000" ] && ss -ltn 2>/dev/null | grep -q '127.0.0.1:3080'; then
      log "dsh web answered after ${i}s (HTTP $code; 401 = auth required, expected)"
      for j in $(seq 1 30); do
        if gw_ok; then log "dsh-passwords gateway healthy after ${i}+${j}s"; return 0; fi
        sleep 1
      done
      log "WARN: gateway still unhealthy after ${i}+30s"
      return 1
    fi
    sleep 1
  done
  log "ERROR: dsh web unreachable after ${WAIT}s"
  return 1
}

summarize() {
  log "state: unit=$(systemctl is-active "$UNIT") web=$(web_code) gateway=$(gw_ok && echo ok || echo down) pid=$(systemctl show -p MainPID --value "$UNIT")"
  log "bridge: unit=$(systemctl is-active "$BRIDGE_UNIT") healthz=$(bridge_ok && echo ok || echo down) mcp=$(mcp_ok && echo ok || echo down)"
}

log "=== restart-live start (mode=$MODE) ==="
summarize
log "profile bundles: $(python3 -c "import json;print(','.join(json.load(open('$PKG'))['dsh']['profile']['bundles']))" 2>/dev/null)"
log "mcp patch: $(grep -c 'mcp-phone' "$PATCH" 2>/dev/null || echo 0) mcp-phone entries in $PATCH"
log "rollback snapshot: ${ROLLBACK:-<none>}"

if [ "$MODE" = "check" ]; then
  if wait_up; then log "RESULT: CHECK OK (no change made)"; else log "RESULT: CHECK FAILED"; fi
  exit 0
fi

systemctl restart "$UNIT"
log "systemctl restart $UNIT -> rc=$?"

if wait_up; then
  summarize
  log "RESULT: SUCCESS — dsh-mobile-xc is active. Refresh the browser (Ctrl/Cmd+Shift+R) to get the mobile layout."
  exit 0
fi

log "RESULT: FAILED — rolling back dsh-mobile-xc and the mcp-phone MCP entry"
ROLLED_BACK=0
if [ -f "$ROLLBACK" ]; then
  cp -v "$ROLLBACK" "$PKG"
  ROLLED_BACK=1
else
  log "ERROR: rollback file $ROLLBACK missing — leaving package.json as is"
fi
if [ -f "$PATCH_ROLLBACK" ]; then
  cp -v "$PATCH_ROLLBACK" "$PATCH"
  ROLLED_BACK=1
else
  log "WARN: $PATCH_ROLLBACK missing — mcp-phone stays in $PATCH"
fi

if [ "$ROLLED_BACK" = "1" ]; then
  systemctl restart "$UNIT"
  log "rollback restart -> rc=$?"
  if wait_up; then
    summarize
    log "RESULT: ROLLED BACK — service healthy, mobile UI plugin and phone MCP inactive"
  else
    summarize
    log "RESULT: CRITICAL — service still down; run: journalctl -u $UNIT -n 200 --no-pager"
  fi
else
  summarize
  log "RESULT: CRITICAL — nothing to roll back; run: journalctl -u $UNIT -n 200 --no-pager"
fi
