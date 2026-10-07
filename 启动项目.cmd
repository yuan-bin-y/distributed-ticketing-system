@echo off
setlocal
call "%~dp0deploy\docker\launch.cmd" Start %*
exit /b %errorlevel%
