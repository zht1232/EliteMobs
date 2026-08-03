@echo off
setlocal enabledelayedexpansion
set "JAVA25=C:\Users\Administrator\Downloads\MSLX-Daemon_v1.5.5.1_win-x64\DaemonData\Tools\Java\25\bin"
set "PAPER=C:\Users\Administrator\Downloads\MSLX-Daemon_v1.5.5.1_win-x64\DaemonData\Servers\1"
set "CP=%PAPER%\paper-26.2.jar"
set "CP=!CP!;%PAPER%\libraries\io\papermc\paper\paper-api\26.2.build.71-beta\paper-api-26.2.build.71-beta.jar"
set "CP=!CP!;%PAPER%\libraries\net\kyori\adventure-api\5.2.0\adventure-api-5.2.0.jar"
set "CP=!CP!;%PAPER%\libraries\net\kyori\adventure-key\5.2.0\adventure-key-5.2.0.jar"
set "CP=!CP!;%PAPER%\libraries\com\google\guava\guava\33.6.0-jre\guava-33.6.0-jre.jar"
set "CP=!CP!;%PAPER%\libraries\net\md-5\bungeecord-chat\1.21-R0.2-deprecated+build.21\bungeecord-chat-1.21-R0.2-deprecated+build.21.jar"
set "CP=!CP!;%~dp0libs\PlaceholderAPI-2.12.3.jar"
set "SRC=%~dp0src\main\java"
set "OUT=%~dp0classes"
rmdir /s /q "%OUT%" 2>nul
mkdir "%OUT%"
set FILES=
for /r "%SRC%" %%f in (*.java) do set FILES=!FILES! "%%f"
REM Target Java 25: matches paper-api 26.2 (class version 69). Do NOT downgrade below 21.
"%JAVA25%\javac.exe" -encoding UTF-8 --release 25 -cp "%CP%" -d "%OUT%" %FILES%
if %errorlevel% equ 0 (echo BUILD SUCCESSFUL) else (echo BUILD FAILED)