@echo off
setlocal
call "%~dp0deploy\docker\launch.cmd" Rebuild %*
exit /b %errorlevel%
