@echo off
REM PhantomShieldX64 Java Agent 編譯腳本 (Windows)

set SCRIPT_DIR=%~dp0
set SRC_DIR=%SCRIPT_DIR%src
set BUILD_DIR=%SCRIPT_DIR%build
set JAR_FILE=%SCRIPT_DIR%ClassDumpAgent.jar

echo ╔═══════════════════════════════════════════════════════════╗
echo ║   PhantomShieldX64 Class Dump Agent - 編譯                 ║
echo ╚═══════════════════════════════════════════════════════════╝
echo.

REM 清理舊構建
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"

REM 檢查 javac
where javac >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] 未找到 javac，請安裝 JDK
    exit /b 1
)

echo [INFO] JDK 版本:
javac -version

REM 編譯
echo.
echo [INFO] 編譯中...
javac -d "%BUILD_DIR%" "%SRC_DIR%\ClassDumpAgent.java" "%SRC_DIR%\AgentAttacher.java"

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] 編譯失敗
    exit /b 1
)

echo [INFO] 編譯成功

REM 打包 JAR
echo.
echo [INFO] 打包 JAR...
jar cfm "%JAR_FILE%" "%SCRIPT_DIR%MANIFEST.MF" -C "%BUILD_DIR%" .

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] JAR 打包失敗
    exit /b 1
)

echo [INFO] JAR 已創建：%JAR_FILE%
echo.

echo ╔═══════════════════════════════════════════════════════════╗
echo ║   編譯完成！                                               ║
echo ╚═══════════════════════════════════════════════════════════╝
echo.
echo 使用方法:
echo   方法 1: 啟動時附加
echo     java -javaagent:ClassDumpAgent.jar -jar minecraft.jar
echo.
echo   方法 2: 運行時附加
echo     java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher
echo.
pause
