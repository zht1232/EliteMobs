@echo off
cd /d "%~dp0"
set JAR=EliteMobs-29.16.0.jar
set TEMP=jar-temp
del /q "%JAR%" 2>nul
rmdir /s /q "%TEMP%" 2>nul
mkdir "%TEMP%"
xcopy /s /y classes\* "%TEMP%\"
copy /y src\main\resources\plugin.yml "%TEMP%\"
copy /y src\main\resources\config.yml "%TEMP%\"
copy /y src\main\resources\messages.yml "%TEMP%\"
copy /y src\main\resources\mobs.yml "%TEMP%\"
xcopy /s /y src\main\resources\gems "%TEMP%\gems\"

jar cf "%JAR%" -C "%TEMP%" .
rmdir /s /q "%TEMP%"
if exist "%JAR%" (echo JAR created: %JAR%) else (echo JAR creation failed)
