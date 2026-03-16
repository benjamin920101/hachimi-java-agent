# Java Agent 攔截類加載指南

## 概述

本工具使用 Java Instrumentation API 在類加載時攔截並保存字節碼，用於提取 PhantomShieldX64 加密的類文件。

### 特色功能

- ✅ **詳細的日誌輸出** - 實時顯示類加載狀態
- ✅ **時間戳記錄** - 每個事件都有精確的時間標記
- ✅ **統計報告** - 每 100 個類和 JVM 關閉時自動輸出統計
- ✅ **關鍵類標記** - 特殊標記 PhantomShield 相關類
- ✅ **錯誤追蹤** - 詳細的錯誤信息和堆棧追蹤
- ✅ **運行時附加** - 支持不重啟 JVM 動態附加
- ✅ **自動偵測** - DLL 加載、類爆發、關鍵事件偵測（新增！）
- ✅ **DLL 自動複製** - 檢測到 DLL 時自動複製到 `copied_dlls/` 目錄（新增！）

## 自動偵測功能（v1.3 新增）

### 偵測項目

| 偵測項目 | 說明 | 觸發條件 |
|----------|------|----------|
| **DLL 加載偵測** | 自動掃描 `java.library.path` 和暫存目錄 | 發現 `PhantomShieldX64.dll` |
| **DLL 自動複製** | 檢測到 DLL 時自動複製到 `copied_dlls/` 目錄 | DLL 文件被偵測到 |
| **類加載爆發** | 監控短時間內大量類加載 | >10 個類/秒 |
| **關鍵類事件** | 標記重要類加載 | PhantomShield/Native/JNI 類 |
| **反調試機制** | 偵測可能的反調試行為 | 異常棧幀解開、時間檢查 |

### 自動偵測示例

```
[AUTO-DETECT] 02:30:15.200 自動偵測功能已啟用:
   ✓ DLL 加載偵測
   ✓ 類加載爆發偵測（>10 個類/秒）
   ✓ 關鍵類標記（PhantomShield/DIY/Native）
   ✓ 反調試機制偵測
   ✓ 解密過程監控

╔═══════════════════════════════════════════════════════════╗
║   [AUTO-DETECT] ★★★ DLL 已加載！★★★                     ║
╚═══════════════════════════════════════════════════════════╝
   時間：02:30:45.678
   運行時間：30.45 秒
   DLL 路徑：/tmp/PhantomShieldX64.dll
   建議：立即複製此 DLL 文件進行分析！

╔═══════════════════════════════════════════════════════════╗
║   [AUTO-DETECT] ★★★ 類加載爆發偵測！★★★                 ║
║   可能正在解密 .diy 容器中的類                            ║
╚═══════════════════════════════════════════════════════════╝
   時間：02:30:46.123
   運行時間：30.90 秒
   已處理類：523
   目標類：45
```

## 日誌輸出說明

### 日誌級別標識

| 標識 | 說明 |
|------|------|
| `★★★ [TARGET]` | skidonion 包 - 最高優先級 |
| `★★☆ [TARGET]` | net.hachimi 包 - 高優先級 |
| `★☆☆ [TARGET]` | tech.skidonion 包 - 中優先級 |
| `    [SKIP] ` | 其他包 - 跳過不保存 |
| `[!!!]` | 關鍵類檢測（PhantomShield/___/sAnhI） |

### 日誌輸出示例

```
╔═══════════════════════════════════════════════════════════╗
║   PhantomShieldX64 Class Dump Agent Started               ║
║   攔截類加載 - 解密 .diy 容器中的類                          ║
╚═══════════════════════════════════════════════════════════╝

[INFO] 02:30:15.123 JVM 信息:
   Java 版本：21.0.10
   Java 供應商：Ubuntu
   JVM 名稱：OpenJDK 64-Bit Server VM
   用戶目錄：/home/user/minecraft
─────────────────────────────────────────────────────────────
[INFO] 02:30:15.125 Agent 參數：(無)
[INFO] 02:30:15.126 創建轉儲目錄：/home/user/minecraft/dumped_classes → 成功
   絕對路徑：/home/user/minecraft/dumped_classes
─────────────────────────────────────────────────────────────
[INFO] 02:30:15.127 監控的目標包:
   ★★★ skidonion.
   ★★★ net.hachimi.
   ★★★ tech.skidonion.
─────────────────────────────────────────────────────────────
[INFO] 02:30:15.128 Class Transformer 已註冊
[INFO] 02:30:15.129 所有加載的類將自動保存到 dumped_classes/

╔═══════════════════════════════════════════════════════════╗
║   Agent 已激活 - 等待類加載...                            ║
╚═══════════════════════════════════════════════════════════╝

[02:30:20.456] ★★★ [TARGET] skidonion.sAnhI.___
   [INFO] 序列號：1 | 大小：37732 bytes | 運行時間：5.33 秒
   [INFO] ClassLoader: jdk.internal.loader.ClassLoaders$AppClassLoader
   [INFO] 來源位置：file:/home/user/minecraft/hachimi.jar
   [!!!] ★★★ 關鍵類檢測！★★★
★★★ [!!!] 成功保存：skidonion.sAnhI.___ (37732 bytes)
   → /home/user/minecraft/dumped_classes/skidonion/sAnhI/___.class

[STATS] 02:31:00.789 運行 45.7 秒 - 已處理 101 個類
   目標類：15 | 跳過：86 | 錯誤：0
```

## 目錄結構

```
java-agent/
├── src/
│   ├── ClassDumpAgent.java    # 核心 Agent（類攔截）
│   └── AgentAttacher.java     # 運行時附加工具
├── MANIFEST.MF                 # JAR 清單文件
├── build.sh                    # Linux/Mac 編譯腳本
├── build.bat                   # Windows 編譯腳本
└── README.md                   # 本文檔
```

## 快速開始

### 步驟 1: 編譯 Agent

**Linux/Mac:**
```bash
cd java-agent
chmod +x build.sh
./build.sh
```

**Windows:**
```batch
cd java-agent
build.bat
```

編譯成功後會生成 `ClassDumpAgent.jar`。

---

### 步驟 2: 使用 Agent

有兩種使用方式：

#### 方法 A: 啟動時附加（推薦）

在啟動 Minecraft 時附加 Agent：

```bash
java -javaagent:/path/to/ClassDumpAgent.jar \
     -cp minecraft.jar \
     net.minecraft.client.main.Main
```

**Windows 示例:**
```batch
java -javaagent:ClassDumpAgent.jar -jar minecraft.jar
```

**Fabric Loader 示例:**
```bash
java -javaagent:ClassDumpAgent.jar \
     -cp "fabric-loader.jar;minecraft.jar" \
     net.fabricmc.loader.launch.knot.FabricClientMain
```

#### 方法 B: 運行時附加（無需重啟 JVM）

1. 啟動 Minecraft（不加 Agent）

2. 找到 Java 進程 PID:
   ```bash
   # Linux/Mac
   jps -l
   
   # Windows
   jps.exe -l
   ```

3. 附加 Agent:
   ```bash
   java -cp ClassDumpAgent.jar \
        com.hachimi.dump.AgentAttacher
   ```
   
   列出所有進程後，使用 PID 附加:
   ```bash
   java -cp ClassDumpAgent.jar \
        com.hachimi.dump.AgentAttacher 12345
   ```

---

### 步驟 3: 獲取解密的類

Agent 運行後，所有被加載的類會自動保存到：

```
dumped_classes/
├── skidonion/
│   └── sAnhI/
│       ├── ___.class          # 類加載器
│       ├── 1.class
│       ├── I.class
│       ├── l.class
│       └── ...                # 其他混淆類
├── net/
│   └── hachimi/
│       └── client/
│           └── ...            # Hachimi 客戶端類
└── tech/
    └── skidonion/
        └── ...                # 驗證相關類
```

---

## 工作原理

```
┌─────────────────────────────────────────────────────────┐
│  JVM 啟動                                                │
│  ↓                                                      │
│  加載 Java Agent (premain)                              │
│  ↓                                                      │
│  註冊 ClassFileTransformer                              │
│  ↓                                                      │
│  ClassLoader.loadClass() 被調用                         │
│  ↓                                                      │
│  Transformer.transform() 被調用                         │
│  ├── 獲取 classfileBuffer (字節碼)                       │
│  ├── 保存到 dumped_classes/                             │
│  └── 返回原始字節碼（不修改類）                         │
│  ↓                                                      │
│  類被成功加載到 JVM                                      │
└─────────────────────────────────────────────────────────┘
```

---

## 配置選項

### 攔截範圍

在 `ClassDumpAgent.java` 中修改 `TARGET_PACKAGES`:

```java
private static final String[] TARGET_PACKAGES = {
    "skidonion.",      // PhantomShield 類加載器
    "net.hachimi.",    // Hachimi 客戶端
    "tech.skidonion."  // 驗證模塊
};
```

### 轉儲所有類

修改 `shouldDumpAll()` 方法:

```java
private boolean shouldDumpAll() {
    return true;  // 轉儲所有被加載的類
}
```

---

## 反編譯提取的類

使用 CFR 反編譯提取的 class 文件:

```bash
# 安裝 CFR
wget https://github.com/leiben/cfr/releases/download/0.152/cfr-0.152.jar

# 反編譯整個目錄
java -jar cfr-0.152.jar dumped_classes/skidonion/sAnhI/___.class \
     --outputdir decompiled_output
```

---

## 故障排除

### 問題 1: Agent 未生效

**檢查:**
```bash
# 確認 JVM 參數正確
java -version  # 確認使用的是同一個 JDK

# 檢查 JAR 完整性
jar tf ClassDumpAgent.jar | grep MANIFEST
```

### 問題 2: 運行時附加失敗

**原因:** 需要 `tools.jar` (JDK 8) 或 `jdk.attach` 模塊 (JDK 9+)

**解決方案:**
```bash
# JDK 8: 確保 tools.jar 在 classpath
java -cp "$JAVA_HOME/lib/tools.jar:ClassDumpAgent.jar" \
     com.hachimi.dump.AgentAttacher

# JDK 9+: 添加 --add-modules
java --add-modules jdk.attach \
     -cp ClassDumpAgent.jar \
     com.hachimi.dump.AgentAttacher
```

### 問題 3: 沒有類被轉儲

**檢查:**
1. 確認目標類確實被加載
2. 檢查 `dumped_classes/` 目錄權限
3. 查看控制台輸出是否有 Agent 日誌

---

## 高級用法

### 結合 Arthas 使用

1. 先附加 Agent 轉儲類
2. 再使用 Arthas 動態追蹤方法調用:

```bash
# 啟動 Arthas
java -jar arthas-boot.jar

# 追蹤類加載器
trace skidonion.sAnhI.___ * '{params, returnObj}'

# 查看方法調用參數
watch skidonion.sAnhI.___ '{params, returnObj}' -x 3
```

### 批量反編譯腳本

```bash
#!/bin/bash
# 批量反編譯所有轉儲的類
for f in $(find dumped_classes -name "*.class"); do
    java -jar cfr.jar "$f" --outputdir decompiled_all
done
```

---

## 技術細節

### Instrumentation API

| 方法 | 說明 |
|------|------|
| `premain()` | JVM 啟動時調用 |
| `agentmain()` | 運行時附加時調用 |
| `addTransformer()` | 註冊類轉換器 |
| `transform()` | 類加載時被調用 |

### 類加載流程

```
Bootstrap ClassLoader
    ↓
Extension ClassLoader
    ↓
Application ClassLoader
    ↓
Custom ClassLoader (skidonion.sAnhI.___)
    ↓
Transformer.transform() ← Agent 攔截點
    ↓
Class.defineClass()
```

---

## 相關資源

- [Java Instrumentation Specification](https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/package-summary.html)
- [Java Attach API](https://docs.oracle.com/javase/8/docs/jdk/api/attach/doc/com/sun/tools/attach/VirtualMachine.html)
- [CFR Decompiler](https://github.com/leiben/cfr)
- [Arthas Documentation](https://arthas.aliyun.com/doc/)

---

## 免責聲明

本工具僅供教育和研究目的使用。請遵守以下規定:

1. **不要分發解密後的代碼** - 這可能侵犯版權
2. **僅用於學習** - 了解混淆技術和保護機制
3. **尊重作者勞動** - 如需使用外掛功能，請購買正版
