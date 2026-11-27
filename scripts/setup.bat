@echo off
REM One-time setup: Python packages, model training, frontend packages.
REM Run from the fraudshield folder:  scripts\setup.bat
setlocal
cd /d "%~dp0.."

echo [1/3] Installing Python packages for the ML service...
python -m pip install -r ml-service\requirements.txt || goto :fail

echo [2/3] Training and evaluating the models (about 1-2 minutes)...
pushd ml-service
python -m fraudshield_ml.train || (popd & goto :fail)
popd

echo [3/3] Installing frontend packages...
pushd frontend
call npm install || (popd & goto :fail)
popd

echo.
echo Setup complete. Make sure the MySQL database exists (database\setup.sql), then run scripts\start-all.bat
exit /b 0

:fail
echo Setup failed. See the messages above.
exit /b 1
