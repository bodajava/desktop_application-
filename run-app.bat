@echo off
rem ---------------------------------------------------------------------------------------------
rem  Exam Halls and Proctoring Allocation Management System - launcher (Windows)
rem
rem  Usage:  run-app.bat             check the database, then start the app
rem          run-app.bat --no-check  start without the database check
rem  Java:   runtime\ next to this script (portable edition), else JAVA_HOME, else PATH
rem  Env:    JAVA_HOME / JAVA_OPTS / DB_URL / DB_USERNAME / DB_PASSWORD (override config file)
rem ---------------------------------------------------------------------------------------------
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title Exam Halls - launcher

rem ---- Java 21+ --------------------------------------------------------------------------------
rem Order: bundled runtime (portable edition) - JAVA_HOME - java on the PATH
set "JAVA=java"
set "JAVAW=javaw"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
)
if exist "%~dp0runtime\bin\java.exe" (
    set "JAVA=%~dp0runtime\bin\java.exe"
    set "JAVAW=%~dp0runtime\bin\javaw.exe"
)
"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found. Install Java 21 or newer ^(e.g. Eclipse Temurin 21^) and try again.
    pause
    exit /b 1
)
rem "java -version" goes through a temp file: a for /f command that starts with a quoted path
rem (e.g. "C:\Program Files\...") would have its quotes stripped by cmd.
set "JAVA_VERSION="
"%JAVA%" -version 2> "%TEMP%\examhalls_java_version.txt"
for /f "tokens=3" %%v in ('findstr /i "version" "%TEMP%\examhalls_java_version.txt"') do if not defined JAVA_VERSION set "JAVA_VERSION=%%~v"
del "%TEMP%\examhalls_java_version.txt" >nul 2>&1
for /f "tokens=1 delims=." %%m in ("!JAVA_VERSION!") do set "JAVA_MAJOR=%%m"
if not defined JAVA_MAJOR set "JAVA_MAJOR=0"
if !JAVA_MAJOR! LSS 21 (
    echo [ERROR] Java 21 or newer is required ^(found version !JAVA_VERSION!^).
    pause
    exit /b 1
)

rem ---- Application jar: distribution bundle first, then a local Maven build --------------------
set "APP_JAR=examhalls.jar"
if not exist "%APP_JAR%" (
    set "APP_JAR="
    for %%f in (target\exam-halls-proctoring-*-all.jar) do set "APP_JAR=%%f"
)
if not defined APP_JAR (
    echo [ERROR] Application jar not found. Build it with:  mvn -Pdist clean package
    pause
    exit /b 1
)

rem ---- Configuration ---------------------------------------------------------------------------
if not exist "config\application.properties" (
    echo [WARN] config\application.properties not found - using built-in defaults.
    echo        Copy config\application.properties.example and enter the Oracle credentials.
)

if not defined JAVA_OPTS set "JAVA_OPTS=-Xms128m -Xmx768m"
set "BASE_OPTS=-Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED"

rem ---- Oracle connection check (fallback: explain and let the user decide) ---------------------
if /i "%~1"=="--no-check" goto start
echo Checking the Oracle connection...
"%JAVA%" %BASE_OPTS% -jar "%APP_JAR%" --check-db
if not errorlevel 1 goto start
echo.
echo The database is not reachable. Common fixes:
echo   * Docker:  docker start examhalls-oracle   ^(wait about 30 s until the container log says DATABASE IS READY TO USE^)
echo   * Check db.url / db.username / db.password in config\application.properties
echo   * Lab network: make sure port 1521 of the database server is reachable
echo.
choice /c YN /n /m "Start the application anyway? [Y/N] "
if errorlevel 2 exit /b 1

:start
echo Starting Exam Halls ^(logs: %CD%\logs\examhalls.log^)...
start "Exam Halls" "%JAVAW%" %BASE_OPTS% %JAVA_OPTS% -jar "%APP_JAR%"
endlocal
exit /b 0
