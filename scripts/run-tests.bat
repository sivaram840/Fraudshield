@echo off
REM Runs every automated test. The end-to-end suite needs all three services running (start-all.bat).
setlocal
cd /d "%~dp0.."

echo === ML service unit tests (pytest) ===
pushd ml-service
python -m pytest -q || set FAILED=1
popd

echo === Backend tests (JUnit, H2 in MySQL mode, ML mocked) ===
pushd backend
call mvnw.cmd -q test || set FAILED=1
popd

echo === End-to-end tests (React proxy, Spring Boot, ML, MySQL) ===
python tests\e2e\run_e2e.py || set FAILED=1

if defined FAILED (echo SOME TESTS FAILED & exit /b 1)
echo ALL TESTS PASSED
