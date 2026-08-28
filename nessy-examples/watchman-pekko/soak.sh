#!/usr/bin/env bash
#
# Copyright © 2026 James Carman
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# ---------------------------------------------------------------------------
# Run the watchman against a real model and a real database, then ASSERT what
# happened.
#
# This exists because on 2026-08-28 four separate defects shipped past 52 green
# tests and four rounds of code review, and every one was caught by running the
# thing. They shared a shape: a silent omission that leaves a green suite. A
# test asserts what its author thought to check; a soak notices when the system
# quietly does nothing.
#
# THE MOST IMPORTANT ASSERTION HERE IS "parked at least once".
# The first soak of this feature reported zero refusals and zero errors, which
# read as success. It was measuring a system that had recorded ONE observation
# and then done nothing for six rounds: the failure mode was invisible because
# every check asked whether something bad had happened, and none asked whether
# anything good had. If the run never parks, the refusal count is vacuous --
# nothing ever arrived mid-turn to be refused. So this script fails when the
# condition under test did not occur, not only when the system misbehaves.
#
# Usage:  ./soak.sh [rounds-to-wait]      (default 8)
#
# Prerequisites, none of which this script installs:
#   * Postgres    docker run -d --name watchman-postgres -p 5432:5432 \
#                   -e POSTGRES_USER=watchman -e POSTGRES_PASSWORD=watchman \
#                   -e POSTGRES_DB=watchman postgres:16
#   * A model     LM Studio (or anything OpenAI-compatible) on :1234
#   * Optional    grafana/otel-lgtm on :3000/:4318 for traces and metrics
#
# It DESTROYS the watchman_pekko schema on every run. That is deliberate -- a
# soak that inherits state measures the state, not the code.
# ---------------------------------------------------------------------------
set -uo pipefail

ROUNDS_TARGET="${1:-8}"
MODULE="nessy-examples/watchman-pekko"
SCHEMA="watchman_pekko"
PG_CONTAINER="${PG_CONTAINER:-watchman-postgres}"
LOG_DIR="${HOME}/.local/state/nessy"
LOG="${LOG_DIR}/watchman-pekko.log"
JAR="${MODULE}/target/nessy-example-watchman-pekko-0.1.0-SNAPSHOT.jar"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
psql_q() { docker exec -i "$PG_CONTAINER" psql -U watchman -d watchman -tAc "$1" 2>/dev/null; }

# --- prerequisites, checked rather than assumed -----------------------------
say "Checking prerequisites"
docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$" \
  || { echo "FAIL: Postgres container '${PG_CONTAINER}' is not running."; exit 1; }
curl -sf -m 5 -o /dev/null http://localhost:1234/v1/models \
  || { echo "FAIL: no OpenAI-compatible model endpoint on :1234."; exit 1; }
echo "  postgres: up    model endpoint: up"

# --- build ------------------------------------------------------------------
# -am, deliberately: a plain -pl build can compile against a stale nessy-spi
# snapshot in the shared ~/.m2 when another worktree has installed one.
say "Building (this is the gate -- a soak of a stale jar measures nothing)"
./mvnw -q -pl "$MODULE" -am package -DskipTests || { echo "FAIL: build failed."; exit 1; }
echo "  built $(ls -la "$JAR" | awk '{print $5}') bytes"

# --- reset ------------------------------------------------------------------
say "Wiping ${SCHEMA}"
docker exec -i "$PG_CONTAINER" psql -U watchman -d watchman \
  -c "DROP SCHEMA IF EXISTS ${SCHEMA} CASCADE;" >/dev/null 2>&1
docker exec -i "$PG_CONTAINER" psql -U watchman -d watchman \
  < "${MODULE}/src/main/resources/schema.sql" >/dev/null 2>&1
echo "  durable_state rows: $(psql_q "SELECT count(*) FROM ${SCHEMA}.durable_state;")"

# --- launch -----------------------------------------------------------------
# Secrets travel as EXPORTED ENVIRONMENT VARIABLES, never as command-line
# arguments: process arguments are visible to every user on the box via `ps`.
say "Starting the watchman (1-minute rounds)"
mkdir -p "$LOG_DIR"; umask 077
pkill -9 -f "watchman-pekko/target" 2>/dev/null
while lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; do sleep 1; done
[ -f "$LOG" ] && mv "$LOG" "${LOG}.$(date +%Y%m%d-%H%M%S)"

export WATCHMAN_CRON="0 */1 * * * *"
export WATCHMAN_DB_URL="jdbc:postgresql://localhost:5432/watchman?currentSchema=${SCHEMA}"
export WATCHMAN_DB_USER="watchman" WATCHMAN_DB_PASSWORD="watchman"
export WATCHMAN_PASSWORD="${WATCHMAN_PASSWORD:-soak}"
export OTLP_TRACES_URL="http://localhost:4318/v1/traces"

nohup java -jar "$JAR" >> "$LOG" 2>&1 &
SOAK_PID=$!
echo "  pid=${SOAK_PID}  log=${LOG}"

until grep -qE "Started WatchmanApplication|APPLICATION FAILED" "$LOG" 2>/dev/null; do sleep 2; done
grep -q "APPLICATION FAILED" "$LOG" && { echo "FAIL: application did not start."; tail -20 "$LOG"; exit 1; }

# --- wait -------------------------------------------------------------------
say "Waiting for ${ROUNDS_TARGET} rounds (about ${ROUNDS_TARGET} minutes)"
while [ "$(grep -c 'telling the watchman' "$LOG")" -lt "$ROUNDS_TARGET" ]; do
  sleep 10
  printf '  rounds: %s  parked: %s\r' \
    "$(grep -c 'telling the watchman' "$LOG")" "$(grep -c 'approval pending' "$LOG")"
done
echo

# --- measure ----------------------------------------------------------------
say "Measurements"
ROUNDS=$(grep -c 'telling the watchman' "$LOG")
REFUSED=$(grep -c 'REFUSED' "$LOG")
PARKED=$(grep -c 'approval pending' "$LOG")
UNSETTLED=$(grep -c 'not settled' "$LOG")
STARTUP_ERR=$(grep -ci 'APPLICATION FAILED' "$LOG")
STATE_BYTES=$(psql_q "SELECT coalesce(max(pg_column_size(state_payload)),0) FROM ${SCHEMA}.durable_state;")

read -r ADJACENT DISTINCT TOTAL <<<"$(psql_q "SELECT convert_from(payload,'UTF8') FROM ${SCHEMA}.nessy_journal ORDER BY seq;" | python3 -c '
import sys, json
prev = None; adjacent = 0; keys = []
for line in sys.stdin:
    line = line.strip()
    if not line: continue
    try: entry = json.loads(line)
    except Exception: continue
    kind = entry.get("type")
    if kind == "user-message":
        keys.append(entry.get("key"))
        if prev == "user-message": adjacent += 1
    prev = kind
print(adjacent, len(set(keys)), len(keys))
')"

FAILURES=0
check() { # name expected actual explanation
  if [ "$2" = "$3" ]; then printf '  \033[32mPASS\033[0m  %-34s %s\n' "$1" "$3"
  else printf '  \033[31mFAIL\033[0m  %-34s %s (expected %s) -- %s\n' "$1" "$3" "$2" "$4"; FAILURES=$((FAILURES+1)); fi
}

check "observations refused"        0 "$REFUSED"     "an observation arriving mid-turn was destroyed"
check "consecutive user-messages"   0 "$ADJACENT"    "malformed context: two user turns with no assistant between"
check "user-message key collisions" "$TOTAL" "$DISTINCT" "idempotence-by-key silently swallowed an observation"
check "calls left unsettled"        0 "$UNSETTLED"   "a call settled with no exchange recorded, or stalled"
check "startup failures"            0 "$STARTUP_ERR" "the application did not come up"

# The one that stops this being vacuous. Without a park, nothing ever arrived
# mid-turn, so "0 refusals" would be true of a system that does nothing at all.
if [ "$PARKED" -gt 0 ]; then
  printf '  \033[32mPASS\033[0m  %-34s %s\n' "parked at least once" "$PARKED"
else
  printf '  \033[31mFAIL\033[0m  %-34s 0 -- THE RUN IS VACUOUS: nothing arrived mid-turn, so the\n' "parked at least once"
  printf '        refusal count proves nothing. Re-run for longer, or make a gated tool likelier.\n'
  FAILURES=$((FAILURES+1))
fi

if [ "$STATE_BYTES" -lt 2048 ]; then
  printf '  \033[32mPASS\033[0m  %-34s %s bytes\n' "agent state stays small" "$STATE_BYTES"
else
  printf '  \033[31mFAIL\033[0m  %-34s %s bytes -- content is leaking into the persisted document;\n' "agent state stays small" "$STATE_BYTES"
  printf '        tool arguments belong in claims and results belong in Memory.\n'
  FAILURES=$((FAILURES+1))
fi

say "Context"
echo "  rounds: ${ROUNDS}   log: ${LOG}"
psql_q "SELECT '  backlog waiting: '||coalesce(json_array_length((convert_from(payload,'UTF8')::json)->'entries'),0)
        FROM ${SCHEMA}.nessy_document WHERE kind LIKE '%backlog%';"
psql_q "SELECT '  claims held: '||count(*) FROM ${SCHEMA}.nessy_document WHERE kind LIKE 'claim/%';"

say "$([ "$FAILURES" -eq 0 ] && echo 'SOAK PASSED' || echo "SOAK FAILED (${FAILURES})")"
echo "The watchman is still running as pid ${SOAK_PID}; kill it when you are done."
echo "Approvals page: http://localhost:8080/  (watchman / ${WATCHMAN_PASSWORD})"
exit $([ "$FAILURES" -eq 0 ] && echo 0 || echo 1)
