@echo off
setlocal

rem Run from this script's directory
cd /d "%~dp0"

set "JAR=target\COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar"

start "router1" powershell -NoExit -Command "java -jar .\%JAR% .\conf\router1.conf"
start "router2" powershell -NoExit -Command "java -jar .\%JAR% .\conf\router2.conf"
start "router3" powershell -NoExit -Command "java -jar .\%JAR% .\conf\router3.conf"
start "router4" powershell -NoExit -Command "java -jar .\%JAR% .\conf\router4.conf"

endlocal
