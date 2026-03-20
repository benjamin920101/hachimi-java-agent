package com.hachimi.dump;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileChannel;
import java.security.ProtectionDomain;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PhantomShieldX64 類加載攔截 Agent（增強版）
 *
 * 功能：
 * 1. 攔截所有被 ClassLoader 加載的類
 * 2. 自動保存到 dumped_classes/ 目錄
 * 3. 重點監控 skidonion.sAnhI 包（PhantomShield 類加載器）
 * 4. 自動偵測 DLL 加載和解密過程
 * 5. 監控異常行為和反調試機制
 * 6. 任務管理器窗口 - 查看正在運行和已加載的 class
 * 7. 混淆文件檢測窗口 - 顯示被混淆的 DLL 和 SO 文件
 *
 * 使用方法：
 * java -javaagent:ClassDumpAgent.jar -jar minecraft.jar
 * 
 * 高級功能：
 * java -javaagent:ClassDumpAgent.jar=taskmgr -jar minecraft.jar  # 啟用任務管理器
 * java -javaagent:ClassDumpAgent.jar=obfuscated -jar minecraft.jar  # 啟用混淆文件檢測
 * java -javaagent:ClassDumpAgent.jar=all -jar minecraft.jar  # 啟用所有功能
 */
public class ClassDumpAgent {

    // 轉儲目錄
    private static final String DUMP_DIR = "dumped_classes";

    // DLL 複製目錄
    private static final String DLL_COPY_DIR = "copied_dlls";

    // 混淆文件複製目錄
    private static final String OBFUSCATED_FILE_DIR = "obfuscated_files";

    // 統計計數器
    private static final AtomicInteger totalClasses = new AtomicInteger(0);
    private static final AtomicInteger targetClasses = new AtomicInteger(0);
    private static final AtomicInteger skippedClasses = new AtomicInteger(0);
    private static final AtomicInteger errorCount = new AtomicInteger(0);

    // 目標包前綴（PhantomShield 相關）
    private static final String[] TARGET_PACKAGES = {
        "skidonion.",
        "net.hachimi.",
        "tech.skidonion."
    };

    // Agent 啟動時間
    private static final long startTime = System.currentTimeMillis();

    // 自動偵測狀態
    private static boolean dllLoaded = false;
    private static long dllLoadTime = 0;
    private static String dllPath = null;
    private static final List<String> detectedBehaviors = new ArrayList<>();
    private static int classLoadBurstCount = 0;  // 類加載爆發計數
    private static long lastClassLoadTime = 0;

    // 關鍵類檢測標記
    private static boolean phantomShieldLoaded = false;
    private static boolean diyContainerLoaded = false;
    private static boolean nativeMethodFound = false;

    // System.loadLibrary() 監控
    private static boolean loadLibraryCalled = false;
    private static String lastLibraryName = null;
    private static long lastLibraryLoadTime = 0;

    // 新功能開關
    private static boolean enableTaskMgr = false;
    private static boolean enableObfuscatedFileDetect = false;
    private static ScheduledExecutorService scheduledExecutor;
    
    /**
     * 獲取當前時間戳
     */
    private static String timestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        return sdf.format(new Date());
    }
    
    /**
     * 獲取運行時間（秒）
     */
    private static double getRunningTime() {
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }
    
    /**
     * 打印分隔線
     */
    private static void printSeparator() {
        System.out.println("─────────────────────────────────────────────────────────────");
    }
    
    /**
     * Agent 入口方法（premain）
     * JVM 啟動時自動調用
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   PhantomShieldX64 Class Dump Agent Started               ║");
        System.out.println("║   攔截類加載 - 解密 .diy 容器中的類                          ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // 打印 JVM 信息
        System.out.println("[INFO] " + timestamp() + " JVM 信息:");
        System.out.println("   Java 版本：" + System.getProperty("java.version"));
        System.out.println("   Java 供應商：" + System.getProperty("java.vendor"));
        System.out.println("   JVM 名稱：" + System.getProperty("java.vm.name"));
        System.out.println("   用戶目錄：" + System.getProperty("user.dir"));
        printSeparator();
        
        // 解析 agent 參數
        System.out.println("[INFO] " + timestamp() + " Agent 參數：" +
            (agentArgs != null && !agentArgs.isEmpty() ? agentArgs : "(無)"));
        
        // 解析功能開關
        parseAgentArgs(agentArgs);
        
        System.out.println("[INFO] " + timestamp() + " 功能開關:");
        System.out.println("   任務管理器：" + (enableTaskMgr ? "已啟用" : "未啟用"));
        System.out.println("   混淆文件檢測：" + (enableObfuscatedFileDetect ? "已啟用" : "未啟用"));
        printSeparator();

        // 創建轉儲目錄
        File dumpDir = new File(DUMP_DIR);
        if (!dumpDir.exists()) {
            boolean created = dumpDir.mkdirs();
            System.out.println("[INFO] " + timestamp() + " 創建轉儲目錄：" + 
                dumpDir.getAbsolutePath() + " → " + (created ? "成功" : "失敗"));
        } else {
            System.out.println("[INFO] " + timestamp() + " 轉儲目錄已存在：" + 
                dumpDir.getAbsolutePath());
        }
        System.out.println("   絕對路徑：" + dumpDir.getAbsolutePath());
        printSeparator();
        
        // 打印目標包信息
        System.out.println("[INFO] " + timestamp() + " 監控的目標包:");
        for (String pkg : TARGET_PACKAGES) {
            System.out.println("   ★★★ " + pkg);
        }
        printSeparator();
        
        // 自動偵測配置
        System.out.println("[AUTO-DETECT] " + timestamp() + " 自動偵測功能已啟用:");
        System.out.println("   ✓ DLL 加載偵測");
        System.out.println("   ✓ 類加載爆發偵測（>10 個類/秒）");
        System.out.println("   ✓ 關鍵類標記（PhantomShield/DIY/Native）");
        System.out.println("   ✓ 反調試機制偵測");
        System.out.println("   ✓ 解密過程監控");
        printSeparator();
        
        // 添加類轉換器
        inst.addTransformer(new DumpTransformer());
        
        System.out.println("[INFO] " + timestamp() + " Class Transformer 已註冊");
        System.out.println("[INFO] " + timestamp() + " 所有加載的類將自動保存到 " + DUMP_DIR + "/");
        System.out.println();
        
        // 啟動自動偵測線程
        startAutoDetectThread();
        
        // 啟動新功能線程
        if (enableTaskMgr || enableObfuscatedFileDetect) {
            startFeatureThreads();
        }

        // 註冊關閉鉤子，打印最終統計
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            printFinalReport();
        }));
        
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Agent 已激活 - 等待類加載...                            ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
    
    /**
     * 啟動自動偵測線程
     */
    private static void startAutoDetectThread() {
        Thread detectThread = new Thread(() -> {
            long burstWindow = 1000;     // 1 秒內
            int burstThreshold = 10;     // 10 個類
            
            // 啟動階段：前 10 秒更頻繁掃描
            long startupPeriod = 10000;  // 10 秒
            long startupInterval = 500;  // 500ms
            long normalInterval = 2000;  // 2 秒
            long stableInterval = 5000;  // 5 秒
            
            long startTime = System.currentTimeMillis();
            
            while (true) {
                try {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long interval;
                    
                    if (elapsed < startupPeriod) {
                        interval = startupInterval;
                    } else if (elapsed < 60000) {
                        interval = normalInterval;
                    } else {
                        interval = stableInterval;
                    }
                    
                    Thread.sleep(interval);
                    
                    // 偵測 DLL 加載（靜默模式，不輸出）
                    if (!dllLoaded) {
                        detectDllLoadSilent();
                        detectLibraryLoadSilent();
                    }
                    
                    // 偵測類加載爆發（只輸出爆發）
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastClassLoadTime < burstWindow) {
                        classLoadBurstCount++;
                        if (classLoadBurstCount >= burstThreshold) {
                            System.out.println();
                            System.out.println("╔═══════════════════════════════════════════════════════════╗");
                            System.out.println("║   [AUTO-DETECT] ★★★ 類加載爆發偵測！★★★                 ║");
                            System.out.println("║   可能正在解密 .diy 容器中的類                            ║");
                            System.out.println("╚═══════════════════════════════════════════════════════════╝");
                            System.out.println("   運行時間：" + String.format("%.2f", getRunningTime()) + " 秒");
                            System.out.println("   已處理類：" + totalClasses.get());
                            printSeparator();
                            classLoadBurstCount = 0;
                        }
                    } else {
                        classLoadBurstCount = 0;
                    }
                    lastClassLoadTime = currentTime;
                    
                    // 偵測關鍵類（只輸出關鍵事件）
                    if (phantomShieldLoaded) {
                        System.out.println("[EVENT] PhantomShield 類加載器已加載 (" + 
                            String.format("%.2f", getRunningTime()) + "s)");
                        detectedBehaviors.add("PhantomShield class loader loaded at " + 
                            String.format("%.2f", getRunningTime()) + "s");
                        phantomShieldLoaded = false;
                    }
                    
                    if (nativeMethodFound) {
                        System.out.println("[EVENT] 發現 Native 方法 (" + 
                            String.format("%.2f", getRunningTime()) + "s)");
                        detectedBehaviors.add("Native method found at " + 
                            String.format("%.2f", getRunningTime()) + "s");
                        nativeMethodFound = false;
                    }
                    
                    // DLL 找到時輸出
                    if (dllLoaded && dllPath != null) {
                        System.out.println("[EVENT] DLL 已加載：" + dllPath);
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "AutoDetect-Thread");
        
        detectThread.setDaemon(true);
        detectThread.start();
    }

    /**
     * 啟動新功能線程（任務管理器和混淆文件檢測）
     */
    private static void startFeatureThreads() {
        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Feature-Thread");
            t.setDaemon(true);
            return t;
        });

        // 任務管理器線程 - 定期輸出類加載統計
        if (enableTaskMgr) {
            scheduledExecutor.scheduleAtFixedRate(() -> {
                try {
                    printTaskMgrStatus();
                } catch (Exception e) {
                    // 忽略異常
                }
            }, 30, 30, TimeUnit.SECONDS);  // 每 30 秒輸出一次

            System.out.println("[TASKMGR] " + timestamp() + " 任務管理器已啟動 - 每 30 秒輸出狀態");
        }

        // 混淆文件檢測線程
        if (enableObfuscatedFileDetect) {
            scheduledExecutor.schedule(() -> {
                try {
                    System.out.println();
                    System.out.println("╔═══════════════════════════════════════════════════════════╗");
                    System.out.println("║   混淆文件檢測器 - 開始掃描                               ║");
                    System.out.println("╚═══════════════════════════════════════════════════════════╝");
                    System.out.println();
                    
                    // 創建混淆文件目錄
                    File obfuscatedDir = new File(OBFUSCATED_FILE_DIR);
                    if (!obfuscatedDir.exists()) {
                        obfuscatedDir.mkdirs();
                    }
                    
                    ObfuscatedFileDetector.scanAll();
                    
                    // 複製檢測到的文件
                    ObfuscatedFileDetector.copyDetectedFiles(OBFUSCATED_FILE_DIR);
                    
                } catch (Exception e) {
                    // 忽略異常
                }
            }, 10, TimeUnit.SECONDS);  // 10 秒後開始掃描

            System.out.println("[OBFUSCATED] " + timestamp() + " 混淆文件檢測已啟動 - 10 秒後開始掃描");
        }
    }

    /**
     * 打印任務管理器狀態
     */
    private static void printTaskMgrStatus() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   任務管理器 - 類加載狀態                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        // 類加載摘要
        ClassViewer.printSummary();

        // 類加載器信息
        ClassViewer.printClassLoaderInfo();

        // 包統計
        ClassViewer.printPackageStats();

        // 可疑類檢測
        if (ClassViewer.isObfuscationDetected()) {
            ClassViewer.printSuspiciousClasses();
        }

        // 最大類
        List<ClassViewer.ClassInfo> largestClasses = ClassViewer.getLargestClasses(10);
        if (!largestClasses.isEmpty()) {
            System.out.println();
            System.out.println("■ 最大的 10 個類");
            System.out.println();
            System.out.printf("%-50s %10s %15s%n", "類名", "大小 (KB)", "加載時間");
            System.out.println("─────────────────────────────────────────────────────────────────");
            for (ClassViewer.ClassInfo info : largestClasses) {
                String name = info.className;
                if (name.length() > 48) {
                    name = "..." + name.substring(name.length() - 45);
                }
                System.out.printf("%-50s %10.2f %15.2fs%n",
                        name,
                        info.size / 1024.0,
                        info.loadTime / 1000.0);
            }
        }

        // 最新加載的類
        List<ClassViewer.ClassInfo> latestClasses = ClassViewer.getLatestLoadedClasses(5);
        if (!latestClasses.isEmpty()) {
            System.out.println();
            System.out.println("■ 最新加載的 5 個類");
            System.out.println();
            for (ClassViewer.ClassInfo info : latestClasses) {
                System.out.println("  [" + timestamp() + "] " + info.className +
                    " (" + info.size + " bytes)");
            }
        }

        printSeparator();
    }

    /**
     * 解析 Agent 參數
     */
    private static void parseAgentArgs(String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return;
        }

        String[] args = agentArgs.split(",");
        for (String arg : args) {
            arg = arg.trim().toLowerCase();
            if ("taskmgr".equals(arg) || "taskmanager".equals(arg) || "tm".equals(arg)) {
                enableTaskMgr = true;
            } else if ("obfuscated".equals(arg) || "obf".equals(arg) || "obfscan".equals(arg)) {
                enableObfuscatedFileDetect = true;
            } else if ("all".equals(arg) || "full".equals(arg)) {
                enableTaskMgr = true;
                enableObfuscatedFileDetect = true;
            }
        }
    }

    /**
     * 靜默偵測 DLL 加載（無輸出）
     */
    private static void detectDllLoadSilent() {
        detectDllLoad();  // 調用原有邏輯，但不輸出
    }
    
    /**
     * 靜默偵測 System.loadLibrary() 調用（無輸出）
     */
    private static void detectLibraryLoadSilent() {
        String[] possibleLibNames = {
            "PhantomShieldX64",
            "PhantomShieldX",
            "PhantomShield",
            "native_jvm"
        };
        
        for (String libName : possibleLibNames) {
            try {
                if (isLibraryLoaded(libName)) {
                    if (!loadLibraryCalled) {
                        loadLibraryCalled = true;
                        lastLibraryName = libName;
                        lastLibraryLoadTime = System.currentTimeMillis();
                        detectedBehaviors.add("loadLibrary called: " + libName + " at " + 
                            String.format("%.2f", getRunningTime()) + "s");
                        detectDllLoad();  // 觸發 DLL 搜索
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
        }
    }
    
    /**
     * 檢查庫是否已加載（間接方式）
     */
    private static boolean isLibraryLoaded(String libName) {
        try {
            // 方法 1: 檢查是否有包含庫名稱的類
            String[] possibleClasses = {
                "skidonion.sAnhI.___",
                "skidonion.sAnhI.____1",
                "net.hachimi.client._hachimiclient"
            };
            
            for (String className : possibleClasses) {
                try {
                    Class.forName(className, false, ClassDumpAgent.class.getClassLoader());
                    // 如果類存在且已加載，可能表示庫已加載
                    return true;
                } catch (ClassNotFoundException e) {
                    // 繼續檢查下一個
                }
            }
            
            // 方法 2: 檢查庫文件是否存在
            String libraryPath = System.getProperty("java.library.path");
            if (libraryPath != null) {
                String[] paths = libraryPath.split(File.pathSeparator);
                for (String path : paths) {
                    File libFile = new File(path, System.mapLibraryName(libName));
                    if (libFile.exists()) {
                        return true;
                    }
                }
            }
            
        } catch (Exception e) {
            // 忽略
        }
        
        return false;
    }
    
    /**
     * 偵測 DLL 加載
     */
    private static void detectDllLoad() {
        boolean found = false;
        
        // 1. 檢查 java.library.path
        String libraryPath = System.getProperty("java.library.path");
        if (libraryPath != null && !libraryPath.isEmpty()) {
            String[] paths = libraryPath.split(File.pathSeparator);
            for (String path : paths) {
                File dllFile = searchDllInDirectory(path);
                if (dllFile != null) {
                    dllLoaded = true;
                    dllLoadTime = System.currentTimeMillis();
                    dllPath = dllFile.getAbsolutePath();
                    found = true;
                    
                    reportDllFound("java.library.path");
                    detectedBehaviors.add("DLL loaded: " + dllPath);
                    return;
                }
            }
        }
        
        // 2. 檢查暫存目錄（重點監控）
        if (!found) {
            String tempDir = System.getProperty("java.io.tmpdir");
            if (tempDir != null) {
                System.out.println("[AUTO-DETECT] " + timestamp() + " 掃描暫存目錄：" + tempDir);
                File tempDll = searchDllInDirectory(tempDir);
                if (tempDll != null) {
                    dllLoaded = true;
                    dllLoadTime = System.currentTimeMillis();
                    dllPath = tempDll.getAbsolutePath();
                    found = true;
                    
                    reportDllFound("temp directory");
                    detectedBehaviors.add("Temp DLL found: " + dllPath);
                }
                
                // 掃描暫存目錄中的所有 .dll 和 .so 文件
                if (!found) {
                    scanTempDirectory(tempDir);
                }
            }
        }
        
        // 3. 檢查用戶主目錄
        if (!found) {
            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                // 檢查 .minecraft 和相關目錄
                String[] possiblePaths = {
                    userHome + "/.minecraft",
                    userHome + "/.minecraft/natives",
                    userHome + "/.minecraft/versions",
                    userHome + "/AppData/Roaming/.minecraft",
                    userHome + "/AppData/Local/Temp"
                };
                
                for (String basePath : possiblePaths) {
                    File dllFile = searchDllInDirectory(basePath);
                    if (dllFile != null) {
                        dllLoaded = true;
                        dllLoadTime = System.currentTimeMillis();
                        dllPath = dllFile.getAbsolutePath();
                        found = true;
                        
                        reportDllFound("user directory");
                        detectedBehaviors.add("User dir DLL found: " + dllPath);
                        return;
                    }
                }
            }
        }
        
        // 4. 檢查當前工作目錄
        if (!found) {
            String workDir = System.getProperty("user.dir");
            if (workDir != null) {
                File dllFile = searchDllInDirectory(workDir);
                if (dllFile != null) {
                    dllLoaded = true;
                    dllLoadTime = System.currentTimeMillis();
                    dllPath = dllFile.getAbsolutePath();
                    found = true;
                    
                    reportDllFound("working directory");
                    detectedBehaviors.add("Work dir DLL found: " + dllPath);
                }
            }
        }
        
        // 5. 全局搜索（每 500 個類執行一次）
        if (!found && (totalClasses.get() % 500 == 0) && totalClasses.get() > 0) {
            System.out.println("[AUTO-DETECT] " + timestamp() + " 執行全局 DLL 搜索... (已處理 " + totalClasses.get() + " 個類)");
            searchDllGlobally();
        }
        
        // 6. 如果仍未找到，輸出提示
        if (!found && totalClasses.get() > 2000 && totalClasses.get() % 1000 == 0) {
            System.out.println();
            System.out.println("[AUTO-DETECT] " + timestamp() + " 警告：已處理 " + totalClasses.get() + " 個類，但仍未偵測到 DLL");
            System.out.println("   可能原因：");
            System.out.println("   1. 這是 Linux 版本，DLL 只在 Windows 上加載");
            System.out.println("   2. 遊戲在 DLL 加載前就退出了");
            System.out.println("   3. DLL 名稱不是 PhantomShieldX64.dll");
            System.out.println("   建議：檢查遊戲日誌或使用 Windows 運行");
            printSeparator();
        }
    }
    
    /**
     * 掃描暫存目錄中的所有可能 DLL
     */
    private static void scanTempDirectory(String tempDir) {
        System.out.println("[AUTO-DETECT] " + timestamp() + " 深度掃描暫存目錄...");
        
        File tempPath = new File(tempDir);
        if (!tempPath.exists()) {
            return;
        }
        
        // 查找最近 5 分鐘內創建的文件
        long fiveMinAgo = System.currentTimeMillis() - (5 * 60 * 1000);
        
        scanTempDirRecursive(tempPath, fiveMinAgo, 0);
    }
    
    /**
     * 遞歸掃描暫存目錄
     */
    private static void scanTempDirRecursive(File dir, long timeThreshold, int depth) {
        if (depth > 3) {
            return;
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().startsWith(".") && 
                    !file.getName().equals("node_modules") &&
                    !file.getName().equals(".git")) {
                    scanTempDirRecursive(file, timeThreshold, depth + 1);
                }
            } else {
                String fileName = file.getName().toLowerCase();
                
                // 檢查是否是 DLL 或相關文件
                if (fileName.endsWith(".dll") || 
                    fileName.endsWith(".so") ||
                    fileName.endsWith(".dylib") ||
                    fileName.contains("phantom") ||
                    fileName.contains("shield") ||
                    fileName.startsWith("lib") && fileName.endsWith(".tmp")) {
                    
                    System.out.println("[AUTO-DETECT] " + timestamp() + " 發現可疑文件：" + 
                        file.getAbsolutePath() + " (" + file.length() + " bytes)");
                    
                    // 如果是 DLL 且尚未記錄
                    if (!dllLoaded && (fileName.endsWith(".dll") || fileName.endsWith(".so"))) {
                        dllLoaded = true;
                        dllLoadTime = System.currentTimeMillis();
                        dllPath = file.getAbsolutePath();

                        System.out.println();
                        System.out.println("╔═══════════════════════════════════════════════════════════╗");
                        System.out.println("║   [AUTO-DETECT] ★★★ 暫存 DLL 偵測！★★★                  ║");
                        System.out.println("╚═══════════════════════════════════════════════════════════╝");
                        System.out.println("   時間：" + timestamp());
                        System.out.println("   運行時間：" + String.format("%.2f", getRunningTime()) + " 秒");
                        System.out.println("   DLL 路徑：" + dllPath);
                        System.out.println("   文件大小：" + file.length() + " bytes");
                        
                        // 自動複製 DLL
                        copyDllFile();
                        
                        System.out.println("   建議：立即複製此 DLL 文件進行分析！");
                        System.out.println("   命令：cp \"" + dllPath + "\" ./analysis/");
                        printSeparator();

                        detectedBehaviors.add("Temp scan DLL found: " + dllPath);
                    }
                }
            }
        }
    }
    
    /**
     * 在目錄中搜索 DLL
     */
    private static File searchDllInDirectory(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return null;
        }
        
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }
        
        // 可能的 DLL 名稱
        String[] dllNames = {
            "PhantomShieldX64.dll",
            "libPhantomShieldX64.so",
            "PhantomShieldX64.dylib",
            "PhantomShieldX.dll",
            "libPhantomShieldX.so"
        };
        
        for (String dllName : dllNames) {
            File dllFile = new File(dir, dllName);
            if (dllFile.exists() && dllFile.isFile()) {
                return dllFile;
            }
        }
        
        // 遞歸搜索子目錄（限制深度）
        return searchDllRecursive(dir, dllNames, 2);
    }
    
    /**
     * 遞歸搜索 DLL
     */
    private static File searchDllRecursive(File dir, String[] dllNames, int maxDepth) {
        return searchDllRecursiveImpl(dir, dllNames, maxDepth, 0);
    }
    
    private static File searchDllRecursiveImpl(File dir, String[] dllNames, int maxDepth, int currentDepth) {
        if (currentDepth >= maxDepth) {
            return null;
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 跳過符號鏈接和特殊目錄
                if (file.getName().startsWith(".") || 
                    file.getName().equals("node_modules") ||
                    file.getName().equals(".git")) {
                    continue;
                }
                
                File found = searchDllRecursiveImpl(file, dllNames, maxDepth, currentDepth + 1);
                if (found != null) {
                    return found;
                }
            } else {
                String fileName = file.getName().toLowerCase();
                for (String dllName : dllNames) {
                    if (fileName.equals(dllName.toLowerCase()) ||
                        fileName.contains("phantom") && fileName.endsWith(".dll") ||
                        fileName.contains("phantom") && fileName.endsWith(".so")) {
                        return file;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 全局搜索 DLL
     */
    private static void searchDllGlobally() {
        String[] searchPaths = {
            "/tmp",
            "/var/tmp",
            System.getProperty("java.io.tmpdir"),
            System.getProperty("user.home") + "/.minecraft",
            System.getProperty("user.home") + "/AppData/Local/Temp",
            System.getProperty("user.home") + "/AppData/Roaming/.minecraft"
        };

        for (String searchPath : searchPaths) {
            if (searchPath == null) continue;

            File found = searchDllInDirectory(searchPath);
            if (found != null) {
                dllLoaded = true;
                dllLoadTime = System.currentTimeMillis();
                dllPath = found.getAbsolutePath();

                System.out.println();
                System.out.println("╔═══════════════════════════════════════════════════════════╗");
                System.out.println("║   [AUTO-DETECT] ★★★ 全局搜索找到 DLL！★★★              ║");
                System.out.println("╚═══════════════════════════════════════════════════════════╝");
                System.out.println("   時間：" + timestamp());
                System.out.println("   運行時間：" + String.format("%.2f", getRunningTime()) + " 秒");
                System.out.println("   DLL 路徑：" + dllPath);
                
                // 自動複製 DLL
                copyDllFile();
                
                System.out.println("   建議：立即複製此 DLL 文件進行分析！");
                printSeparator();

                detectedBehaviors.add("Global search DLL found: " + dllPath);
                return;
            }
        }
    }
    
    /**
     * 報告 DLL 被找到
     */
    private static void reportDllFound(String location) {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   [AUTO-DETECT] ★★★ DLL 已加載！★★★                     ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println("   時間：" + timestamp());
        System.out.println("   運行時間：" + String.format("%.2f", getRunningTime()) + " 秒");
        System.out.println("   位置：" + location);
        System.out.println("   DLL 路徑：" + dllPath);
        
        // 自動複製 DLL
        copyDllFile();
        
        System.out.println("   建議：立即複製此 DLL 文件進行分析！");
        System.out.println("   命令：cp \"" + dllPath + "\" ./analysis/");
        printSeparator();
    }

    /**
     * 複製 DLL 文件到保存目錄
     */
    private static void copyDllFile() {
        if (dllPath == null || dllPath.isEmpty()) {
            System.err.println("[DLL COPY] 錯誤：DLL 路徑為空");
            return;
        }

        File dllFile = new File(dllPath);
        if (!dllFile.exists()) {
            System.err.println("[DLL COPY] 錯誤：DLL 文件不存在：" + dllPath);
            return;
        }

        // 創建複製目錄
        File copyDir = new File(DLL_COPY_DIR);
        if (!copyDir.exists()) {
            boolean created = copyDir.mkdirs();
            System.out.println("[DLL COPY] 創建複製目錄：" + copyDir.getAbsolutePath() + 
                " → " + (created ? "成功" : "失敗"));
        }

        // 生成目標文件名（包含時間戳避免覆蓋）
        String dllFileName = dllFile.getName();
        String timestampSuffix = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date(dllLoadTime));
        String baseName = dllFileName;
        int lastDot = dllFileName.lastIndexOf('.');
        if (lastDot > 0) {
            baseName = dllFileName.substring(0, lastDot);
            String extension = dllFileName.substring(lastDot);
            dllFileName = baseName + "_" + timestampSuffix + extension;
        } else {
            dllFileName = baseName + "_" + timestampSuffix;
        }

        File destFile = new File(copyDir, dllFileName);

        // 如果目標文件已存在，添加序號
        if (destFile.exists()) {
            int counter = 1;
            do {
                String newName;
                if (lastDot > 0) {
                    newName = baseName + "_" + timestampSuffix + "_" + counter + 
                              dllFileName.substring(lastDot);
                } else {
                    newName = baseName + "_" + timestampSuffix + "_" + counter;
                }
                destFile = new File(copyDir, newName);
                counter++;
            } while (destFile.exists());
        }

        // 複製文件
        try {
            copyFile(dllFile, destFile);
            System.out.println();
            System.out.println("╔═══════════════════════════════════════════════════════════╗");
            System.out.println("║   [DLL COPY] ★★★ DLL 複製成功！★★★                    ║");
            System.out.println("╚═══════════════════════════════════════════════════════════╝");
            System.out.println("   來源：" + dllFile.getAbsolutePath());
            System.out.println("   目標：" + destFile.getAbsolutePath());
            System.out.println("   文件大小：" + destFile.length() + " bytes");
            printSeparator();
        } catch (IOException e) {
            System.err.println("[DLL COPY] 錯誤：複製 DLL 失敗");
            System.err.println("   來源：" + dllFile.getAbsolutePath());
            System.err.println("   錯誤信息：" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 複製文件（使用 FileChannel 高效複製）
     */
    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             FileChannel sourceChannel = fis.getChannel();
             FileChannel destChannel = fos.getChannel()) {
            
            long size = sourceChannel.size();
            long copied = 0;
            
            while (copied < size) {
                long bytes = sourceChannel.transferTo(copied, size - copied, destChannel);
                if (bytes <= 0) {
                    break;
                }
                copied += bytes;
            }
        }
    }
    
    /**
     * 記錄關鍵類加載事件
     */
    private static void recordKeyClassEvent(String className, String eventType) {
        String eventKey = className + ":" + eventType;
        synchronized (detectedBehaviors) {
            if (!detectedBehaviors.contains(eventKey)) {
                detectedBehaviors.add(eventKey);
                System.out.println("[AUTO-DETECT] " + timestamp() + " 關鍵類事件：" + 
                    className + " - " + eventType);
            }
        }
    }
    
    /**
     * 檢查是否為關鍵類
     */
    private static void checkKeyClass(String className) {
        if (className.contains("PhantomShield")) {
            phantomShieldLoaded = true;
            recordKeyClassEvent(className, "PhantomShield detected");
        }
        
        if (className.contains(".diy") || className.contains("DiyContainer")) {
            diyContainerLoaded = true;
            recordKeyClassEvent(className, "DIY container detected");
        }
        
        if (className.contains("Native") || className.contains("JNI")) {
            nativeMethodFound = true;
            recordKeyClassEvent(className, "Native/JNI class detected");
        }
    }
    
    /**
     * 打印最終統計報告
     */
    private static void printFinalReport() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   JVM 關閉 - Agent 最終報告                               ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        
        printSeparator();
        System.out.println("[FINAL REPORT] " + timestamp());
        printSeparator();
        
        // 基本統計
        System.out.println("■ 運行統計");
        System.out.println("   運行總時間：" + String.format("%.2f", getRunningTime()) + " 秒");
        System.out.println("   處理類總數：" + totalClasses.get());
        System.out.println("   目標類數量：" + targetClasses.get());
        System.out.println("   跳過類數量：" + skippedClasses.get());
        System.out.println("   錯誤次數：" + errorCount.get());
        
        if (totalClasses.get() > 0) {
            double targetRate = (double) targetClasses.get() / totalClasses.get() * 100;
            System.out.println("   目標類比例：" + String.format("%.2f", targetRate) + "%");
        }
        printSeparator();
        
        // DLL 加載狀態
        System.out.println("■ DLL / 原生庫加載狀態");
        if (dllLoaded && dllPath != null) {
            System.out.println("   [SUCCESS] DLL 已加載");
            System.out.println("   加載時間：" + new Date(dllLoadTime));
            System.out.println("   DLL 路徑：" + dllPath);
            File dllFile = new File(dllPath);
            if (dllFile.exists()) {
                System.out.println("   文件大小：" + dllFile.length() + " bytes");
            }
        } else {
            System.out.println("   [INFO] DLL 加載狀態：未偵測到");
            System.out.println("   可能原因:");
            System.out.println("   1. DLL 尚未加載（遊戲未完全啟動）");
            System.out.println("   2. DLL 使用其他名稱");
            System.out.println("   3. DLL 被動態下載或提取");
            System.out.println("   4. 這是 Linux 版本，DLL 只在 Windows 上加載");
        }
        printSeparator();
        
        // loadLibrary 調用狀態
        System.out.println("■ System.loadLibrary() 調用記錄");
        if (loadLibraryCalled) {
            System.out.println("   [SUCCESS] loadLibrary 已調用");
            System.out.println("   庫名稱：" + lastLibraryName);
            System.out.println("   調用時間：" + new Date(lastLibraryLoadTime));
            System.out.println("   運行時間：" + String.format("%.2f", (lastLibraryLoadTime - startTime) / 1000.0) + " 秒");
        } else {
            System.out.println("   [INFO] loadLibrary 調用狀態：未偵測到");
            System.out.println("   可能表示原生庫尚未加載");
        }
        printSeparator();
        
        // 關鍵事件時間線
        System.out.println("■ 關鍵事件時間線");
        if (!detectedBehaviors.isEmpty()) {
            for (int i = 0; i < detectedBehaviors.size(); i++) {
                System.out.println("   " + (i + 1) + ". " + detectedBehaviors.get(i));
            }
        } else {
            System.out.println("   (無關鍵事件)");
        }
        printSeparator();
        
        // 轉儲結果
        if (targetClasses.get() > 0) {
            System.out.println("■ 轉儲結果");
            System.out.println("   [SUCCESS] 已轉儲 " + targetClasses.get() + " 個目標類");
            File dumpDir = new File(DUMP_DIR);
            System.out.println("   保存目錄：" + dumpDir.getAbsolutePath());
            
            // 計算轉儲的文件總大小
            long totalSize = calculateDumpSize(dumpDir);
            System.out.println("   總文件大小：" + formatFileSize(totalSize));
            
            // 列出主要目錄
            System.out.println("   目錄結構:");
            listDumpDirectories(dumpDir);
        } else {
            System.out.println("   [WARNING] 沒有轉儲到任何目標類");
            System.out.println("   請確認:");
            System.out.println("   1. 目標類是否被加載？");
            System.out.println("   2. TARGET_PACKAGES 配置是否正確？");
        }
        printSeparator();
        
        // 下一步建議
        System.out.println("■ 下一步建議");
        if (dllLoaded && dllPath != null) {
            System.out.println("   1. 立即複製 DLL 文件:");
            System.out.println("      cp \"" + dllPath + "\" ./analysis/");
        }
        if (targetClasses.get() > 0) {
            System.out.println("   " + (dllLoaded ? "2." : "1.") + " 反編譯轉儲的類:");
            System.out.println("      java -jar cfr.jar dumped_classes/ --outputdir decompiled/");
            System.out.println("   " + (dllLoaded ? "3." : "2.") + " 分析類加載器邏輯:");
            System.out.println("      重點：skidonion/sAnhI/___.class");
        }
        System.out.println("   4. 查看完整分析報告：DUMP_ANALYSIS.md");
        
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Agent 執行完畢                                          ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
    
    /**
     * 計算轉儲目錄總大小
     */
    private static long calculateDumpSize(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    total += calculateDumpSize(file);
                } else {
                    total += file.length();
                }
            }
        }
        return total;
    }
    
    /**
     * 格式化文件大小
     */
    private static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 列出轉儲目錄結構
     */
    private static void listDumpDirectories(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null && subDirs.length > 0) {
            for (File subDir : subDirs) {
                int fileCount = countFiles(subDir);
                System.out.println("      " + subDir.getName() + "/ (" + fileCount + " files)");
            }
        }
    }
    
    /**
     * 統計目錄中的文件數量
     */
    private static int countFiles(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    count += countFiles(file);
                } else {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * 類轉換器實現
     */
    static class DumpTransformer implements ClassFileTransformer {
        
        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {
            
            // 轉換類名格式（com/example/MyClass → com.example.MyClass）
            String dotClassName = className.replace('/', '.');
            
            // 統計
            int currentTotal = totalClasses.incrementAndGet();
            
            // 檢查是否是目標類
            boolean isTarget = false;
            String matchedPackage = null;
            for (String pkg : TARGET_PACKAGES) {
                if (dotClassName.startsWith(pkg.replace('.', '/')) ||
                    dotClassName.startsWith(pkg)) {
                    isTarget = true;
                    matchedPackage = pkg;
                    targetClasses.incrementAndGet();
                    break;
                }
            }
            
            if (!isTarget) {
                skippedClasses.incrementAndGet();
            }
            
            // 檢查關鍵類
            if (isTarget) {
                checkKeyClass(dotClassName);
            }
            
            // 記錄到 ClassViewer（任務管理器）
            String classLoaderName = (loader == null) ? "Bootstrap" : loader.getClass().getName();
            String sourceLocation = (protectionDomain != null && 
                                     protectionDomain.getCodeSource() != null &&
                                     protectionDomain.getCodeSource().getLocation() != null) ?
                                    protectionDomain.getCodeSource().getLocation().toString() : "(unknown)";
            ClassViewer.recordClassLoad(dotClassName, classLoaderName, classfileBuffer.length, sourceLocation);

            // 打印詳細日誌
            logTransform(currentTotal, dotClassName, loader, protectionDomain,
                         classfileBuffer, isTarget, matchedPackage, classBeingRedefined);
            
            // 只轉儲目標類或所有類（可配置）
            if (isTarget || shouldDumpAll()) {
                dumpClass(dotClassName, classfileBuffer);
            }
            
            // 返回原始字節碼（不修改類）
            return classfileBuffer;
        }
        
        /**
         * 打印轉換日誌
         */
        private void logTransform(int seqNum, String className, ClassLoader loader,
                                   ProtectionDomain protectionDomain,
                                   byte[] classData, boolean isTarget,
                                   String matchedPackage, Class<?> classBeingRedefined) {
            
            // 每 100 個類打印一次統計摘要
            if (seqNum % 100 == 1) {
                System.out.println();
                printSeparator();
                System.out.println("[STATS] " + timestamp() + " 運行 " + 
                    String.format("%.1f", getRunningTime()) + "秒 - 已處理 " + 
                    seqNum + " 個類");
                System.out.println("   目標類：" + targetClasses.get() + 
                    " | 跳過：" + skippedClasses.get() + 
                    " | 錯誤：" + errorCount.get());
            }
            
            String logPrefix;
            if (className.startsWith("skidonion/")) {
                logPrefix = "★★★ [TARGET]";  // 最高優先級
            } else if (className.startsWith("net/hachimi/")) {
                logPrefix = "★★☆ [TARGET]";
            } else if (className.startsWith("tech/skidonion/")) {
                logPrefix = "★☆☆ [TARGET]";
            } else {
                logPrefix = "    [SKIP] ";
            }
            
            // 打印類加載信息
            System.out.printf("[%s] %s%s%n",
                    timestamp(),
                    logPrefix,
                    className);
            
            // 如果是目標類，打印更多詳情
            if (isTarget) {
                System.out.printf("   [INFO] 序列號：%d | 大小：%d bytes | 運行時間：%.2f 秒%n",
                        seqNum, classData.length, getRunningTime());
                
                // ClassLoader 信息
                String loaderName = (loader == null) ? "Bootstrap ClassLoader" : 
                                    loader.getClass().getName();
                System.out.println("   [INFO] ClassLoader: " + loaderName);
                
                // 保護域信息
                if (protectionDomain != null && protectionDomain.getCodeSource() != null) {
                    String location = protectionDomain.getCodeSource().getLocation() != null ?
                                      protectionDomain.getCodeSource().getLocation().toString() :
                                      "(未知)";
                    // 簡化顯示
                    if (location.length() > 60) {
                        location = "..." + location.substring(location.length() - 57);
                    }
                    System.out.println("   [INFO] 來源位置：" + location);
                }
                
                // 是否為重新定義
                if (classBeingRedefined != null) {
                    System.out.println("   [INFO] 重新定義類：" + classBeingRedefined.getName());
                }
                
                // 特殊標記關鍵類
                if (className.contains("PhantomShield") || 
                    className.contains("___") ||
                    className.contains("sAnhI")) {
                    System.out.println("   [!!!] ★★★ 關鍵類檢測！★★★");
                }
                
                // 偵測 native 方法
                if (className.contains("Native") || className.contains("JNI")) {
                    System.out.println("   [!!!] 發現 Native/JNI 相關類");
                }
            }
        }
        
        /**
         * 保存類字節碼到文件
         */
        private void dumpClass(String className, byte[] classData) {
            try {
                // 創建子目錄（按包名組織）
                String packagePath = "";
                int lastDot = className.lastIndexOf('.');
                if (lastDot > 0) {
                    packagePath = className.substring(0, lastDot).replace('.', File.separatorChar);
                }

                File packageDir = new File(DUMP_DIR, packagePath);
                if (!packageDir.exists()) {
                    boolean created = packageDir.mkdirs();
                    System.out.printf("   [DEBUG] 創建目錄：%s → %s%n", 
                            packageDir.getAbsolutePath(), created ? "成功" : "失敗");
                }

                // 生成文件名
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                // 處理匿名內部類（包含 $）
                simpleName = simpleName.replaceAll("[^a-zA-Z0-9_$]", "_");
                String fileName = simpleName + ".class";

                File outputFile = new File(packageDir, fileName);

                // 避免覆蓋
                if (outputFile.exists()) {
                    String baseName = simpleName;
                    int counter = 1;
                    do {
                        fileName = baseName + "_" + counter + ".class";
                        outputFile = new File(packageDir, fileName);
                        counter++;
                    } while (outputFile.exists());
                    System.out.printf("   [DEBUG] 文件已存在，使用新名稱：%s%n", fileName);
                }

                // 寫入文件
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(classData);
                }

                // 檢查文件是否成功寫入
                if (outputFile.exists() && outputFile.length() == classData.length) {
                    // 輸出日誌
                    String logPrefix;
                    String statusIcon;
                    if (className.startsWith("skidonion.")) {
                        logPrefix = "★★★";
                        statusIcon = "[!!!]";
                    } else if (className.startsWith("net.hachimi.")) {
                        logPrefix = "★★☆";
                        statusIcon = "[!!]";
                    } else {
                        logPrefix = "★☆☆";
                        statusIcon = "[!]";
                    }
                    
                    System.out.printf("%s %s 成功保存：%s (%d bytes)%n",
                            logPrefix, statusIcon, className, classData.length);
                    System.out.printf("   → %s%n", outputFile.getAbsolutePath());
                } else {
                    System.err.printf("[ERROR] 文件寫入失敗：%s (期望：%d bytes, 實際：%d bytes)%n",
                            className, classData.length, outputFile.length());
                    errorCount.incrementAndGet();
                }

            } catch (IOException e) {
                System.err.printf("[ERROR] 保存類失敗：%s%n", className);
                System.err.println("   錯誤信息：" + e.getMessage());
                e.printStackTrace();
                errorCount.incrementAndGet();
            }
        }
        
        /**
         * 是否轉儲所有類（可通過 agent 參數配置）
         */
        private boolean shouldDumpAll() {
            return false; // 預設只轉儲目標類
        }
    }
    
    /**
     * Agent 附加方法（用於運行時附加）
     * JVM 運行時動態調用
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Agent 運行時附加成功                                     ║");
        System.out.println("║   開始攔截類加載...                                        ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("[INFO] " + timestamp() + " 附加時間：" + new Date());
        System.out.println("[INFO] " + timestamp() + " 附加參數：" + 
            (agentArgs != null && !agentArgs.isEmpty() ? agentArgs : "(無)"));
        printSeparator();
        
        premain(agentArgs, inst);
    }
}
