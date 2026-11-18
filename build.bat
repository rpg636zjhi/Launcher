@echo off
chcp 65001 >nul
title xyc Launcher 重新编译
setlocal
set "JAVAC=C:\Program Files (x86)\Android\openjdk\jdk-17.0.14\bin\javac.exe"
if not exist "%JAVAC%" set "JAVAC=javac"
set "JAR=C:\Program Files (x86)\Android\openjdk\jdk-17.0.14\bin\jar.exe"
if not exist "%JAR%" set "JAR=jar"
if exist "%~dp0out" rd /s /q "%~dp0out"
mkdir "%~dp0out"
"%JAVAC%" -encoding UTF-8 -d "%~dp0out" %~dp0src\com\mclauncher\core\*.java %~dp0src\com\mclauncher\ui\*.java %~dp0src\com\mclauncher\main\*.java
if errorlevel 1 goto :err
"%JAR%" cfe "%~dp0launcher.jar" com.mclauncher.main.Main -C "%~dp0out" .
echo 编译完成，launcher.jar 已更新。
goto :end
:err
echo 编译失败！
:end
pause
endlocal
