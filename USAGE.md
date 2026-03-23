# ClassDumpAgent 增強版 - 使用說明

## 新增功能

### 1. GUI 圖形界面（Class Viewer GUI）
圖形化界面實時監控類加載，包括：
- 類列表表格（類名、包、類加載器、大小、加載時間等）
- 實時統計信息（總類數、類加載器數量、包數量等）
- 過濾器（搜索、包過濾、僅顯示混淆類）
- 事件日誌（記錄重要事件）
- 雙擊查看類詳情
- 導出類列表為 CSV
- 查看最大/最新的類
- 混淆檢測功能

**默認啟用**：當不指定任何參數時，GUI 將自動啟動。

### 2. 任務管理器窗口（TaskMgr）
實時查看正在運行和已加載的 class，包括：
- 類加載摘要（總數、活躍類、類加載器數量等）
- 類加載器信息（每個類加載器加載的類數量和大小）
- 包統計信息（按包名分類的類數量）
- 可疑/混淆類檢測（檢測短類名、無意義字符組合等）
- 最大的 10 個類
- 最新加載的 5 個類

### 3. 混淆文件檢測窗口（ObfuscatedFileDetector）
檢測被混淆的 DLL 和 SO 文件，包括：
- 副檔名被混淆的文件（如 `.dl_`, `.so_`, `.dy_` 等）
- 文件名被混淆的動態庫（短文件名、哈希樣式等）
- 偽裝成其他文件的動態庫（如 `.txt`, `.log` 結尾但實際是二進制）
- 無擴展名的二進制文件（PE/ELF/Mach-O 格式）
- 自動複製檢測到的文件到 `obfuscated_files/` 目錄

## 使用方法

### 方法 1：啟動時附加（JVM 啟動時）

```bash
# 默認啟用 GUI（無參數）
java -javaagent:ClassDumpAgent.jar -jar minecraft.jar

# 啟用 GUI 圖形界面
java -javaagent:ClassDumpAgent.jar=gui -jar minecraft.jar

# 只啟用任務管理器（禁用 GUI）
java -javaagent:ClassDumpAgent.jar=nogui,taskmgr -jar minecraft.jar

# 只啟用混淆文件檢測（禁用 GUI）
java -javaagent:ClassDumpAgent.jar=nogui,obfuscated -jar minecraft.jar

# 啟用所有功能（GUI + 任務管理器 + 混淆文件檢測）
java -javaagent:ClassDumpAgent.jar=all -jar minecraft.jar
```

### 方法 2：運行時附加（JVM 運行中）

```bash
# 列出所有 Java 進程
java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher

# 附加到指定進程（預設啟用所有功能）
java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid>

# 附加時指定功能
java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> taskmgr
java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> obfuscated
java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> all
```

### 方法 3：使用 build 腳本

```bash
# Windows
build.bat

# Linux/Mac
chmod +x build.sh
./build.sh
```

## 參數說明

| 參數 | 別名 | 說明 |
|------|------|------|
| `gui` | `swing`, `g` | 啟用 GUI 圖形界面（默認啟用） |
| `nogui` | `ng` | 禁用 GUI 圖形界面 |
| `taskmgr` | `taskmanager`, `tm` | 啟用任務管理器，定期輸出類加載狀態 |
| `obfuscated` | `obf`, `obfscan` | 啟用混淆文件檢測，掃描並複製可疑文件 |
| `all` | `full` | 啟用所有功能（GUI + taskmgr + obfuscated） |
| `verbose` | `debug`, `v` | 啟用詳細日誌輸出 |

多個參數可用逗號分隔：
```bash
java -javaagent:ClassDumpAgent.jar=taskmgr,obfuscated -jar minecraft.jar
```

## 輸出目錄結構

```
.
├── dumped_classes/          # 轉儲的類文件（按包名組織）
├── copied_dlls/             # 複製的 DLL 文件
├── obfuscated_files/        # 檢測到的混淆文件
└── DUMP_ANALYSIS.md         # 分析報告（如有）
```

## 輸出示例

### 任務管理器輸出

```
╔═══════════════════════════════════════════════════════════╗
║   任務管理器 - 類加載狀態                                   ║
╚═══════════════════════════════════════════════════════════╝

■ 類加載摘要
  運行時間：120.45 秒
  已加載類總數：15234
  當前活躍類：15234
  類加載器數量：45
  包數量：234
  可疑類數量：156
  ★★★ 檢測到混淆跡象 ★★★

■ 類加載器信息
類加載器                                                       類數量   總大小 (KB)   最後活動時間
─────────────────────────────────────────────────────────────────
sun.misc.Launcher$AppClassLoader                              8234      45678.23          120.45s
sun.misc.Launcher$ExtClassLoader                              3456      23456.78          119.32s
...

■ 最大的 10 個類
類名                                                 大小 (KB)     加載時間
─────────────────────────────────────────────────────────────────
com.example.LargeClass                                  2345.67        45.23s
...
```

### 混淆文件檢測輸出

```
╔═══════════════════════════════════════════════════════════╗
║   混淆文件檢測器 - 開始掃描                               ║
╚═══════════════════════════════════════════════════════════╝

[OBFUSCATED FILE] 發現可疑文件：/tmp/a1b2c3.dl_
   類型：Extension obfuscation: .dl_ → .dll
   大小：1.23 MB
   位置：/tmp
   ★★★ 可能是動態庫 ★★★

[OBFUSCATED FILE] 發現可疑文件：/home/user/.minecraft/natives/libx.so
   類型：Suspicious filename pattern: libx.so
   大小：567.89 KB
   位置：/home/user/.minecraft/natives
   ★★★ 可能是動態庫 ★★★

■ 統計摘要
   檢測到的可疑文件總數：23
   其中可能是動態庫的：15

■ 按混淆類型分類
   [Extension obfuscation: .dl_ → .dll]: 5 個文件
   [Suspicious filename pattern]: 8 個文件
   [Disguised as .txt]: 3 個文件
   ...
```

## 功能特點

1. **實時監控**：任務管理器每 30 秒自動輸出一次狀態
2. **自動掃描**：混淆文件檢測在啟動 10 秒後自動執行
3. **智能檢測**：
   - 類名混淆檢測（短類名、無意義字符）
   - 文件擴展名混淆檢測
   - 文件頭魔法字節檢測（PE/ELF/Mach-O）
4. **自動複製**：檢測到的可疑文件自動複製到 `obfuscated_files/` 目錄

## 注意事項

1. 需要 Java 8 或更高版本
2. 運行時附加功能需要 `tools.jar`（JDK 包含）
3. 某些功能可能需要文件系統讀取權限
4. 大規模掃描可能耗時較長

## 技術細節

### ClassViewer
- 使用 `ConcurrentHashMap` 存儲已加載類信息
- 支持並發訪問和實時統計
- 混淆檢測基於類名長度和模式匹配

### ObfuscatedFileDetector
- 掃描路徑：暫存目錄、用戶主目錄、工作目錄、java.library.path
- 支持識別的文件格式：PE (Windows DLL)、ELF (Linux SO)、Mach-O (macOS DYLIB)
- 遞歸掃描（限制深度避免過長）
- 自動去重避免重複報告
