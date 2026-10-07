@echo off
setlocal
call "%~dp0deploy\docker\launch.cmd" Stop %*
exit /b %errorlevel%
