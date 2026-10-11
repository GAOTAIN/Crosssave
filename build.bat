@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if not exist ".build-logs" mkdir ".build-logs"
echo 正在使用 Java 17 和 Gradle 8.7 编译，日志保存在 .build-logs\build.log。
rem 所需依赖已在项目目录中配置，离线构建不需要再次下载。
call "%~dp0gradlew.bat" --offline --no-daemon --max-workers 2 clean build >".build-logs\build.log" 2>&1
set "CROSSSAVE_EXIT=%ERRORLEVEL%"
type ".build-logs\build.log"
echo.
if "%CROSSSAVE_EXIT%"=="0" (
    echo 构建成功：build\libs\Crosssave-v*-mc1.20.1.jar
) else (
    echo 构建失败，请查看上面的错误及 .build-logs\build.log。
)
if /i not "%~1"=="--no-pause" pause
exit /b %CROSSSAVE_EXIT%
