#!/usr/bin/env bash
# Headless startup test using the published Cardboard 26.3 fork and Fabric API.
set -Eeuo pipefail

plugin_jar="${1:?Usage: smoke-cardboard.sh <plugin jar>}"
[[ -f "$plugin_jar" ]] || { echo "Missing CloverReports JAR: $plugin_jar" >&2; exit 1; }
plugin_jar="$(realpath "$plugin_jar")"
workdir="$(mktemp -d)"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  if [[ -f "$workdir/server/server.log" ]]; then
    echo "=== Fabric/Cardboard server log (last 120 lines) ==="
    tail -n 120 "$workdir/server/server.log"
  fi
  rm -rf "$workdir"
}
trap cleanup EXIT

user_agent="CloverReports-CI/1.4.0 (https://github.com/slyphmp4/CloverReports)"
server="$workdir/server"
mkdir -p "$server/mods" "$server/plugins"

# Official Fabric 26.3 launcher with the loader version required by Cardboard.
fabric_launcher="https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar"
curl -fLsS --retry 3 --connect-timeout 15 -H "User-Agent: $user_agent" -o "$server/fabric-server.jar" "$fabric_launcher"

# The user's Cardboard v26.3.0 release includes its published checksum file.
cardboard_base="https://github.com/slyphmp4/Cardboard/releases/download/v26.3.0"
curl -fLsS --retry 3 --connect-timeout 15 -o "$server/mods/Cardboard-26.3.0.jar" "$cardboard_base/Cardboard-26.3.0.jar"
curl -fLsS --retry 3 --connect-timeout 15 -o "$server/mods/SHA256SUMS.txt" "$cardboard_base/SHA256SUMS.txt"
(cd "$server/mods" && sha256sum --check --strict SHA256SUMS.txt)

# Pin a 26.3 Fabric API release and verify the file against Modrinth's SHA-512.
fabric_meta_url="https://api.modrinth.com/v2/project/P7dR8mSH/version/0.166.2%2B26.3"
fabric_meta="$(curl -fLsS --retry 3 --connect-timeout 15 -H "User-Agent: $user_agent" "$fabric_meta_url")"
fabric_file="$(jq -er '.files | map(select(.primary == true)) | .[0]' <<<"$fabric_meta")"
fabric_url="$(jq -er '.url' <<<"$fabric_file")"
fabric_sha512="$(jq -er '.hashes.sha512' <<<"$fabric_file")"
[[ "$fabric_url" == https://cdn.modrinth.com/* ]] || { echo "Unexpected Fabric API download host" >&2; exit 1; }
[[ "$fabric_sha512" =~ ^[a-f0-9]{128}$ ]] || { echo "Invalid Fabric API SHA-512" >&2; exit 1; }
curl -fLsS --retry 3 --connect-timeout 15 -H "User-Agent: $user_agent" -o "$server/mods/fabric-api.jar" "$fabric_url"
echo "$fabric_sha512  $server/mods/fabric-api.jar" | sha512sum --check --strict

cp "$plugin_jar" "$server/plugins/CloverReports.jar"
printf 'eula=true\n' > "$server/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25574\nmax-players=1\n' > "$server/server.properties"
mkfifo "$server/console.in"
exec 3<>"$server/console.in"
(
  cd "$server"
  exec java --enable-native-access=ALL-UNNAMED -Xms512M -Xmx3G -jar fabric-server.jar nogui < console.in > server.log 2>&1
) &
server_pid=$!

started=0
for ((i = 0; i < 240; i++)); do
  if grep -Eq 'Done \([0-9.]+s\)!' "$server/server.log" 2>/dev/null; then
    started=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Fabric/Cardboard exited during startup" >&2
    exit 1
  fi
  sleep 1
done
[[ "$started" == 1 ]] || { echo "Timed out waiting for Fabric/Cardboard startup" >&2; exit 1; }

if ! grep -Eq '\[CloverReports\] Enabling CloverReports v' "$server/server.log"; then
  echo "CloverReports did not enable on Cardboard" >&2
  exit 1
fi
if grep -Eq '\[CloverReports\] Disabling CloverReports|Error occurred while enabling CloverReports|Could not load .+CloverReports' "$server/server.log"; then
  echo "CloverReports startup failure on Cardboard" >&2
  exit 1
fi
[[ -f "$server/plugins/CloverReports/config.yml" ]] || { echo "Missing Cardboard plugin config" >&2; exit 1; }
[[ -f "$server/plugins/CloverReports/reports.db" ]] || { echo "Missing Cardboard SQLite database" >&2; exit 1; }
echo "Cardboard 26.3.0: CloverReports enabled and SQLite initialized."

printf 'stop\n' >&3
for ((i = 0; i < 35; i++)); do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid"
    server_pid=""
    exit 0
  fi
  sleep 1
done
echo "Fabric/Cardboard failed to stop cleanly" >&2
exit 1
