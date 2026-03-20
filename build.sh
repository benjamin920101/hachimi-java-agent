#!/bin/bash
# PhantomShieldX64 Java Agent 編譯腳本
# 用於編譯 ClassDumpAgent.jar

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
JAR_FILE="$SCRIPT_DIR/ClassDumpAgent.jar"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║   PhantomShieldX64 Class Dump Agent - 編譯                 ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo

# 清理舊構建
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# 檢查 javac
if ! command -v javac &> /dev/null; then
    echo "[ERROR] 未找到 javac，請安裝 JDK"
    exit 1
fi

echo "[INFO] JDK 版本:"
javac -version

# 編譯
echo
echo "[INFO] 編譯中..."
javac -d "$BUILD_DIR" \
    "$SRC_DIR/ClassDumpAgent.java" \
    "$SRC_DIR/ClassViewer.java" \
    "$SRC_DIR/ObfuscatedFileDetector.java" \
    "$SRC_DIR/AgentAttacher.java"

if [ $? -ne 0 ]; then
    echo "[ERROR] 編譯失敗"
    exit 1
fi

echo "[INFO] 編譯成功"

# 打包 JAR
echo
echo "[INFO] 打包 JAR..."
jar cfm "$JAR_FILE" "$SCRIPT_DIR/MANIFEST.MF" -C "$BUILD_DIR" .

if [ $? -ne 0 ]; then
    echo "[ERROR] JAR 打包失敗"
    exit 1
fi

echo "[INFO] JAR 已創建：$JAR_FILE"
echo

# 顯示文件信息
echo "[INFO] JAR 文件信息:"
ls -lh "$JAR_FILE"
echo

# 驗證 MANIFEST
echo "[INFO] MANIFEST.MF 內容:"
unzip -p "$JAR_FILE" META-INF/MANIFEST.MF
echo

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║   編譯完成！                                               ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo
echo "使用方法:"
echo "  方法 1: 啟動時附加"
echo "    java -javaagent:ClassDumpAgent.jar -jar minecraft.jar"
echo
echo "  方法 2: 使用完整路徑"
echo "    java -javaagent:$JAR_FILE -jar minecraft.jar"
echo
echo "  方法 3: 運行時附加 (需要 VirtualMachine API)"
echo "    見 README.md 詳細說明"
echo
