#!/usr/bin/env bash
# AntCraft world check: generates fresh strip worlds on a throwaway dedicated dev server,
# forceloads the strip, runs /antcraft selfcheck over RCON and fails on any FAIL line.
#
# Usage: tools/worldcheck.sh [seed ...]          (default seeds: 12345 777)
#
# Environment:
#   JAVA        java binary (default: java on PATH)
#   WORK_DIR    server directory (default: build/worldcheck); its world/ is wiped per seed
#   MODS_DIR    a folder of extra mod jars to run alongside (e.g. Lithium, FerriteCore)
#   WIDTH, LENGTH   strip size for the new worlds (default 20 x 300)
#   PORT, RCON_PORT (default 25568 / 25578)
#   SKIP_BUILD=1    don't run ./gradlew build first
#   KEEP_WORLD=1    keep each world as world_<seed> instead of deleting it
#   ANALYZE=1       also run tools/analyze.py on the saved overworld (default size only)
#
# Never touches ~/.minecraft or the project's run/ directory.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-java}"
WORK_DIR="${WORK_DIR:-$ROOT/build/worldcheck}"
WIDTH="${WIDTH:-20}"
LENGTH="${LENGTH:-300}"
PORT="${PORT:-25568}"
RCON_PORT="${RCON_PORT:-25578}"
RCON_PASSWORD="worldcheck"
SEEDS=("$@")
[ ${#SEEDS[@]} -eq 0 ] && SEEDS=(12345 777)

if [ "${SKIP_BUILD:-0}" != 1 ]; then
	(cd "$ROOT" && ./gradlew build -q)
fi
LAUNCH_CFG="$ROOT/.gradle/loom-cache/launch.cfg"
ARGS_FILE="$ROOT/build/loom-cache/argFiles/runServer"
if [ ! -f "$LAUNCH_CFG" ] || [ ! -f "$ARGS_FILE" ]; then
	echo "missing $LAUNCH_CFG or $ARGS_FILE (run ./gradlew build / an IDE sync once)" >&2
	exit 2
fi

mkdir -p "$WORK_DIR/mods" "$WORK_DIR/config"
echo "eula=true" > "$WORK_DIR/eula.txt"
if [ -n "${MODS_DIR:-}" ]; then
	rm -f "$WORK_DIR"/mods/*.jar
	cp "$MODS_DIR"/*.jar "$WORK_DIR/mods/"
fi
cat > "$WORK_DIR/config/antcraft.json" <<JSON
{
  "stripWidth": $WIDTH,
  "stripLength": $LENGTH
}
JSON

# RCON client: each argument is one command; prints the replies.
RCON_PY="$WORK_DIR/rcon.py"
cat > "$RCON_PY" <<'PY'
import socket, struct, sys
port, password = int(sys.argv[1]), sys.argv[2]
def pkt(i, t, body):
    b = body.encode() + b"\x00\x00"
    return struct.pack("<iii", len(b) + 8, i, t) + b
def exact(s, n):
    data = b""
    while len(data) < n:
        chunk = s.recv(n - len(data))
        if not chunk: raise EOFError
        data += chunk
    return data
def recv(s):
    n = struct.unpack("<i", exact(s, 4))[0]
    data = exact(s, n)
    return struct.unpack("<i", data[:4])[0], data[8:-2].decode(errors="replace")
s = socket.create_connection(("127.0.0.1", port), timeout=600)
s.sendall(pkt(1, 3, password)); recv(s)
for cmd in sys.argv[3:]:
    # Long replies come in 4096-byte packets. The server reads one request per socket read, so
    # the marker request goes out only after the first reply packet; its reply ends the list.
    s.sendall(pkt(2, 2, cmd))
    parts = [recv(s)[1]]
    if cmd == "stop": break
    s.sendall(pkt(99, 0, ""))
    while True:
        rid, body = recv(s)
        if rid == 99: break
        parts.append(body)
    print("".join(parts))
PY
rcon() { python3 -I "$RCON_PY" "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

# forceload commands covering the strip and its walls (at most 256 chunks per command)
forceload_cmds() {
	local dim="$1" x0="$2" x1="$3" z0="$4" z1="$5" z
	for ((z = z0; z <= z1; z += 512)); do
		local ze=$((z + 511 < z1 ? z + 511 : z1))
		echo "execute in $dim run forceload add $x0 $z $x1 $ze"
	done
}

failed=0
for seed in "${SEEDS[@]}"; do
	echo "=== seed $seed (${WIDTH} x ${LENGTH})"
	cat > "$WORK_DIR/server.properties" <<PROPS
level-name=world
level-seed=$seed
level-type=antcraft\:strip
server-port=$PORT
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
online-mode=false
spawn-protection=0
view-distance=6
simulation-distance=6
sync-chunk-writes=false
PROPS
	rm -rf "$WORK_DIR/world"
	log="$WORK_DIR/server_$seed.log"
	(cd "$WORK_DIR" && exec timeout 900 "$JAVA" \
		-Dfabric.dli.config="$LAUNCH_CFG" -Dfabric.dli.env=server -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer \
		@"$ARGS_FILE" --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 \
		net.fabricmc.devlaunchinjector.Main nogui > "$log" 2>&1) &
	pid=$!
	for _ in $(seq 1 300); do
		grep -q "Done (" "$log" 2>/dev/null && break
		if ! kill -0 "$pid" 2>/dev/null; then echo "server exited early, see $log" >&2; exit 1; fi
		sleep 1
	done
	grep -q "Done (" "$log" || { echo "server did not start, see $log" >&2; kill "$pid" 2>/dev/null; exit 1; }

	mapfile -t cmds < <(
		forceload_cmds minecraft:overworld -17 $((WIDTH + 16)) -17 $((LENGTH + 16))
		forceload_cmds minecraft:the_nether -17 $((WIDTH + 16)) -17 $((LENGTH + 16))
		forceload_cmds minecraft:the_end -$((WIDTH / 2 + 17)) $((WIDTH / 2 + 16)) 0 165
	)
	rcon "${cmds[@]}" > /dev/null

	# wait until the overworld and Nether strips are fully loaded, then check
	out=""
	for _ in $(seq 1 60); do
		sleep 10
		out="$(rcon "antcraft selfcheck")"
		if ! grep -qE "SKIP (overworld|the_nether) chunks" <<< "$out"; then break; fi
	done
	echo "$out" | sed -e 's/\(PASS\|FAIL\|SKIP\) /\n\1 /g' -e 's/AntCraft selfcheck:/\nAntCraft selfcheck:/' | sed '/^$/d' | tee "$WORK_DIR/selfcheck_$seed.txt"
	rcon "stop" > /dev/null || true
	wait "$pid" || true
	if grep -qE "Exception|/ERROR\]" "$log"; then
		echo "errors in $log:"; grep -nE "Exception|/ERROR\]" "$log" | head -20
		failed=1
	fi
	if grep -q "^FAIL" "$WORK_DIR/selfcheck_$seed.txt" || ! grep -q "AntCraft selfcheck:" "$WORK_DIR/selfcheck_$seed.txt"; then failed=1; fi
	if [ "${ANALYZE:-0}" = 1 ]; then
		python3 -I "$ROOT/tools/analyze.py" "$WORK_DIR/world" "$log" | grep -E "^(PASS|FAIL)" || true
	fi
	if [ "${KEEP_WORLD:-0}" = 1 ]; then rm -rf "$WORK_DIR/world_$seed"; mv "$WORK_DIR/world" "$WORK_DIR/world_$seed"; fi
done

if [ "$failed" = 0 ]; then echo "worldcheck: all seeds PASS"; else echo "worldcheck: FAILED"; fi
exit "$failed"
