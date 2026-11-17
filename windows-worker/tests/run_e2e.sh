#!/usr/bin/env bash
# Starts fake Ollama + the real worker, then runs the Android WorkerEndToEndTest against it.
# Usage (from repo root): PYTHON=python3 GRADLE=gradle windows-worker/tests/run_e2e.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PY="${PYTHON:-python3}"
GRADLE="${GRADLE:-gradle}"
TOKEN="e2e-$(date +%s)"
"$PY" "$ROOT/windows-worker/tests/fake_ollama.py" 11500 &
OLLAMA_PID=$!
( cd "$ROOT/windows-worker" && OLLAMA_URL=http://127.0.0.1:11500 FRIDAY_TOKEN="$TOKEN" "$PY" -m uvicorn app:app --host 127.0.0.1 --port 18765 --log-level warning ) &
WORKER_PID=$!
trap 'kill $OLLAMA_PID $WORKER_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do curl -sf http://127.0.0.1:18765/ping >/dev/null && break; sleep 0.2; done
cd "$ROOT"
FRIDAY_E2E_URL=http://127.0.0.1:18765 FRIDAY_E2E_TOKEN="$TOKEN" "$GRADLE" --no-daemon -q :app:testDebugUnitTest --tests 'com.friday.app.worker.WorkerEndToEndTest' --rerun
echo "E2E PASSED"
