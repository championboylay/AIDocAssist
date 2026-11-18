@echo off
rem Starts AI Doc Assist from this folder, so data\, uploads\, logs\, application.properties
rem and .env are always the ones next to it. Extra arguments are passed on, for example:
rem   start.bat                 the web app (http://localhost:8080/docassist)
rem   start.bat sync all        sync every entry once, then exit
cd /d "%~dp0"
"%~dp0AIDocAssist.exe" %*
