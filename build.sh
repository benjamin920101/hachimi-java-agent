#!/bin/bash
# PhantomShieldX64 Java Agent Build Script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
JAR_FILE="$SCRIPT_DIR/ClassDumpAgent.jar"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║   Class Dump Agent - Build                                ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo

# Clean old build
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# Check javac
if ! command -v javac &> /dev/null; then
    echo "[ERROR] javac not found, please install JDK"
    exit 1
fi

echo "[INFO] JDK Version:"
javac -version

# Compile
echo
echo "[INFO] Compiling..."
javac -d "$BUILD_DIR" \
    "$SRC_DIR/ClassDumpAgent.java" \
    "$SRC_DIR/ClassViewer.java" \
    "$SRC_DIR/ObfuscatedFileDetector.java" \
    "$SRC_DIR/AgentAttacher.java"

if [ $? -ne 0 ]; then
    echo "[ERROR] Compilation failed"
    exit 1
fi

echo "[INFO] Compilation successful"

# Package JAR
echo
echo "[INFO] Packaging JAR..."
jar cfm "$JAR_FILE" "$SCRIPT_DIR/MANIFEST.MF" -C "$BUILD_DIR" .

if [ $? -ne 0 ]; then
    echo "[ERROR] JAR packaging failed"
    exit 1
fi

echo "[INFO] JAR created: $JAR_FILE"
echo

# Show file info
echo "[INFO] JAR File Info:"
ls -lh "$JAR_FILE"
echo

# Verify MANIFEST
echo "[INFO] MANIFEST.MF Content:"
unzip -p "$JAR_FILE" META-INF/MANIFEST.MF
echo

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║   Build Complete!                                         ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo
echo "Usage:"
echo "  Method 1: Attach at startup"
echo "    java -javaagent:ClassDumpAgent.jar -jar minecraft.jar"
echo
echo "  Method 2: Use full path"
echo "    java -javaagent:$JAR_FILE -jar minecraft.jar"
echo
echo "  Method 3: Attach at runtime (requires VirtualMachine API)"
echo "    See README.md for details"
echo
