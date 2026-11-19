#!/usr/bin/env bash
# Headless Paper startup smoke test for a compiled CloverReports plugin JAR.
set -Eeuo pipefail

minecraft_version="${1:?Usage: smoke-paper.sh <Minecraft version> <plugin jar>}"
plugin_jar="${2:?Usage: smoke-paper.sh <Minecraft version> <plugin jar>}"
[[ -f "$plugin_jar" ]] || { echo "Missing plugin JAR: $plugin_jar" >&2; exit 1; }
plugin_jar="$(realpath "$plugin_jar")"

workdir="$(mktemp -d)"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  [[ ! -f "$workdir/server/server.log" ]] || {
    echo "=== Paper server log (last 100 lines) ==="
    tail -n 100 "$workdir/server/server.log"
  }
  rm -rf "$workdir"
}
trap cleanup EXIT

user_agent="CloverReports-CI/1.4.0 (https://github.com/slyphmp4/CloverReports)"
builds_url="https://fill.papermc.io/v3/projects/paper/versions/${minecraft_version}/builds"
builds="$(curl -fLsS --retry 3 --connect-timeout 15 -H "User-Agent: $user_agent" "$builds_url")"
download="$(jq -er 'first(.[] | select(.channel == "STABLE")) | .downloads."server:default"' <<<"$builds")"
url="$(jq -er '.url' <<<"$download")"
checksum="$(jq -er '.checksums.sha256' <<<"$download")"
[[ "$url" == https://fill-data.papermc.io/* ]] || { echo "Unexpected Paper download host" >&2; exit 1; }
[[ "$checksum" =~ ^[a-f0-9]{64}$ ]] || { echo "Invalid Paper SHA-256" >&2; exit 1; }

mkdir -p "$workdir/server/plugins"
curl -fLsS --retry 3 --connect-timeout 15 -H "User-Agent: $user_agent" -o "$workdir/server/paper.jar" "$url"
echo "$checksum  $workdir/server/paper.jar" | sha256sum --check --strict
cp "$plugin_jar" "$workdir/server/plugins/CloverReports.jar"
printf 'eula=true\n' > "$workdir/server/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25573\nmax-players=1\n' > "$workdir/server/server.properties"

mkfifo "$workdir/server/console.in"
exec 3<>"$workdir/server/console.in"
(
  cd "$workdir/server"
  exec java --enable-native-access=ALL-UNNAMED -Xms256M -Xmx1536M -jar paper.jar nogui < console.in > server.log 2>&1
) &
server_pid=$!

started=0
for ((i = 0; i < 180; i++)); do
  if grep -Eq 'Done \([0-9.]+s\)!' "$workdir/server/server.log" 2>/dev/null; then
    started=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Paper exited during startup" >&2
    exit 1
  fi
  sleep 1
done
[[ "$started" == 1 ]] || { echo "Timed out waiting for Paper startup" >&2; exit 1; }

if ! grep -Eq '\[CloverReports\] Enabling CloverReports v' "$workdir/server/server.log"; then
  echo "CloverReports never enabled" >&2
  exit 1
fi
if grep -Eq '\[CloverReports\] Disabling CloverReports|Error occurred while enabling CloverReports|Could not load .+CloverReports' "$workdir/server/server.log"; then
  echo "CloverReports startup failure detected" >&2
  exit 1
fi
[[ -f "$workdir/server/plugins/CloverReports/config.yml" ]] || { echo "Missing generated plugin config" >&2; exit 1; }
[[ -f "$workdir/server/plugins/CloverReports/reports.db" ]] || { echo "SQLite database was not created" >&2; exit 1; }

echo "Paper $minecraft_version: server started; CloverReports enabled; config and SQLite database created."
printf 'stop\n' >&3
for ((i = 0; i < 30; i++)); do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid"
    server_pid=""
    exit 0
  fi
  sleep 1
done
echo "Paper failed to stop cleanly" >&2
exit 1
