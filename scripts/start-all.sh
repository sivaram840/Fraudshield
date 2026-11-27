#!/usr/bin/env bash
# macOS / Linux: starts the ML service, backend and frontend; Ctrl+C stops all three.
set -e
cd "$(dirname "$0")/.."
trap 'kill 0' EXIT
(cd ml-service && python3 -m uvicorn fraudshield_ml.service:app --port 8000) &
(cd backend && ./mvnw spring-boot:run) &
(cd frontend && npm run dev) &
echo "Open http://localhost:5173 (or http://127.0.0.1:5173) once the backend reports 'Started FraudShieldApplication'."
wait
