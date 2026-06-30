@echo off
echo Set ws = CreateObject("WScript.Shell") > silent.vbs
echo ws.Run "cmd /c cd /d ""%~dp0"" & java -jar frider.jar", 0, True >> silent.vbs
start /b wscript silent.vbs
ping -n 2 127.0.0.1 > nul
del /f /q silent.vbs