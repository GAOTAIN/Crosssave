@echo off
chcp 65001 >nul
setlocal
rem 优先使用项目专用的 Java 17，避免影响其他项目的 Java 环境。
if exist "%~dp0.tooling\jdk17\bin\java.exe" set "JAVA_HOME=%~dp0.tooling\jdk17"
if not defined GRADLE_USER_HOME if exist "%~dp0.tooling\gradle-user-home\caches" set "GRADLE_USER_HOME=%~dp0.tooling\gradle-user-home"
if defined JAVA_HOME (
    set "CROSSSAVE_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "CROSSSAVE_JAVA=java.exe"
)
rem 无参数启动时显示环境信息并保留窗口，方便双击检查。
if "%~1"=="" goto show_environment
"%CROSSSAVE_JAVA%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%

:show_environment
echo 正在检查项目的 Gradle 和 Java 环境……
"%CROSSSAVE_JAVA%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain --version
set "CROSSSAVE_EXIT=%ERRORLEVEL%"
echo.
echo 编译模组请双击同目录的 build.bat；它会保存日志并保留窗口。
echo 命令行用法：gradlew.bat build
pause
exit /b %CROSSSAVE_EXIT%
