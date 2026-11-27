@echo off
REM Starts the three services in their own windows:
REM   ML service  http://localhost:8000   (Python / FastAPI)
REM   Backend     http://localhost:8080   (Java / Spring Boot, needs MySQL)
REM   Frontend    http://localhost:5173   (React / Vite)
REM Optional overrides before running:  set DB_URL=...  set DB_USER=...  set DB_PASSWORD=...
setlocal
cd /d "%~dp0.."

start "FraudShield ML service" cmd /k "cd /d ml-service && python -m uvicorn fraudshield_ml.service:app --port 8000"
start "FraudShield backend" cmd /k "cd /d backend && mvnw.cmd spring-boot:run"
start "FraudShield frontend" cmd /k "cd /d frontend && npx vite --port 5173"

echo Starting... the backend takes about 30 seconds on first run (it downloads Maven dependencies once).
echo Then open http://localhost:5173  (or http://127.0.0.1:5173)
timeout /t 25 >nul
start "" http://127.0.0.1:5173
