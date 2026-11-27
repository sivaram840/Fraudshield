@echo off
REM Creates the fraudshield database and app user. Run once; it asks for your MySQL root password.
REM Works even when mysql.exe is not on PATH (looks in the default MySQL install folder).
setlocal
cd /d "%~dp0.."

set "MYSQL=mysql"
where mysql >nul 2>nul
if errorlevel 1 (
  for /d %%D in ("C:\Program Files\MySQL\MySQL Server *") do if exist "%%D\bin\mysql.exe" set "MYSQL=%%D\bin\mysql.exe"
)
if "%MYSQL%"=="mysql" (
  where mysql >nul 2>nul || (echo Could not find mysql.exe. Install MySQL Server 8 or run database\setup.sql in MySQL Workbench. & exit /b 1)
)

sc query MySQL80 | find "RUNNING" >nul || echo Note: the MySQL80 service does not look started. Start it from Services if the next step fails.

echo Using "%MYSQL%"
echo Enter your MySQL root password when asked.
"%MYSQL%" -u root -p < database\setup.sql || (echo Database setup failed. & exit /b 1)
echo Database "fraudshield" and user "fraudshield" are ready. Tables are created when the backend starts.
