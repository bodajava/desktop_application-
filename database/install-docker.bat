@echo off
rem ---------------------------------------------------------------------------------------------
rem  Installs (or re-installs) the schema and seed data into the Oracle Free Docker container
rem  and runs the smoke test.   WARNING: install.sql drops and recreates every application object.
rem
rem  Usage:  install-docker.bat [container-name]          (default container: examhalls-oracle)
rem  The EXAM_ADMIN password is read from %APP_USER_PASSWORD% or asked for.
rem ---------------------------------------------------------------------------------------------
setlocal
cd /d "%~dp0"
set "CONTAINER=%~1"
if "%CONTAINER%"=="" set "CONTAINER=examhalls-oracle"
if not defined APP_USER_PASSWORD set /p "APP_USER_PASSWORD=EXAM_ADMIN password: "
set "CONNECT=EXAM_ADMIN/%APP_USER_PASSWORD%@//localhost:1521/FREEPDB1"

echo Copying scripts into %CONTAINER% ...
docker exec -u root %CONTAINER% rm -rf /tmp/examhalls-db || goto failed
docker cp . %CONTAINER%:/tmp/examhalls-db || goto failed
docker exec -u root %CONTAINER% chmod -R a+rX /tmp/examhalls-db || goto failed

echo Running install.sql ...
docker exec -w /tmp/examhalls-db %CONTAINER% sqlplus -S -L "%CONNECT%" @install.sql || goto failed
echo Running 06_student_accounts.sql ...
docker exec -w /tmp/examhalls-db %CONTAINER% sqlplus -S -L "%CONNECT%" @06_student_accounts.sql || goto failed
echo Running 07_student_exams.sql ...
docker exec -w /tmp/examhalls-db %CONTAINER% sqlplus -S -L "%CONNECT%" @07_student_exams.sql || goto failed
echo.
echo Running the smoke test ...
docker exec -w /tmp/examhalls-db %CONTAINER% sqlplus -S -L "%CONNECT%" @05_smoke_test.sql | findstr /r "PASS FAIL ABORTED CHECK"
endlocal
exit /b 0

:failed
echo [ERROR] Installation failed - see the messages above.
endlocal
exit /b 1
