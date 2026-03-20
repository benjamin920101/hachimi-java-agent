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
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Class Dump Agent for intercepting class loading.
 * 
 * Usage:
 * java -javaagent:ClassDumpAgent.jar -jar minecraft.jar
 * 
 * Advanced:
 * java -javaagent:ClassDumpAgent.jar=taskmgr -jar minecraft.jar
 * java -javaagent:ClassDumpAgent.jar=obfuscated -jar minecraft.jar
 * java -javaagent:ClassDumpAgent.jar=all -jar minecraft.jar
 */
public class ClassDumpAgent {

    private static final String DUMP_DIR = "dumped_classes";
    private static final String DLL_COPY_DIR = "copied_dlls";
    private static final String OBFUSCATED_FILE_DIR = "obfuscated_files";

    private static final AtomicInteger totalClasses = new AtomicInteger(0);
    private static final AtomicInteger targetClasses = new AtomicInteger(0);
    private static final AtomicInteger skippedClasses = new AtomicInteger(0);
    private static final AtomicInteger errorCount = new AtomicInteger(0);

    private static final String[] TARGET_PACKAGES = {
        "skidonion.",
        "net.hachimi.",
        "tech.skidonion."
    };

    private static final long startTime = System.currentTimeMillis();

    private static boolean dllLoaded = false;
    private static long dllLoadTime = 0;
    private static String dllPath = null;
    private static final List<String> detectedBehaviors = new ArrayList<>();
    private static int classLoadBurstCount = 0;
    private static long lastClassLoadTime = 0;

    private static boolean phantomShieldLoaded = false;
    private static boolean diyContainerLoaded = false;
    private static boolean nativeMethodFound = false;

    private static boolean loadLibraryCalled = false;
    private static String lastLibraryName = null;
    private static long lastLibraryLoadTime = 0;

    private static boolean enableTaskMgr = false;
    private static boolean enableObfuscatedFileDetect = false;
    private static boolean enableGUI = false;
    private static ScheduledExecutorService scheduledExecutor;
    private static boolean verboseLogging = false;

    private static String timestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        return sdf.format(new Date());
    }

    private static double getRunningTime() {
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    private static void printSeparator() {
        System.out.println("─────────────────────────────────────────────────────────────");
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Class Dump Agent Started                                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("[INFO] " + timestamp() + " JVM Info:");
        System.out.println("   Java Version: " + System.getProperty("java.version"));
        System.out.println("   Java Vendor: " + System.getProperty("java.vendor"));
        System.out.println("   JVM Name: " + System.getProperty("java.vm.name"));
        System.out.println("   User Directory: " + System.getProperty("user.dir"));
        printSeparator();

        System.out.println("[INFO] " + timestamp() + " Agent Args: " +
            (agentArgs != null && !agentArgs.isEmpty() ? agentArgs : "(none)"));

        parseAgentArgs(agentArgs);

        System.out.println("[INFO] " + timestamp() + " Features:");
        System.out.println("   Task Manager: " + (enableTaskMgr ? "Enabled" : "Disabled"));
        System.out.println("   Obfuscated File Detection: " + (enableObfuscatedFileDetect ? "Enabled" : "Disabled"));
        System.out.println("   GUI: " + (enableGUI ? "Enabled" : "Disabled"));
        printSeparator();

        File dumpDir = new File(DUMP_DIR);
        if (!dumpDir.exists()) {
            boolean created = dumpDir.mkdirs();
            System.out.println("[INFO] " + timestamp() + " Created dump directory: " +
                dumpDir.getAbsolutePath() + " -> " + (created ? "Success" : "Failed"));
        } else {
            System.out.println("[INFO] " + timestamp() + " Dump directory exists: " +
                dumpDir.getAbsolutePath());
        }
        System.out.println("   Absolute Path: " + dumpDir.getAbsolutePath());
        printSeparator();

        System.out.println("[INFO] " + timestamp() + " Target Packages:");
        for (String pkg : TARGET_PACKAGES) {
            System.out.println("   ★★★ " + pkg);
        }
        printSeparator();

        System.out.println("[AUTO-DETECT] " + timestamp() + " Auto-detection enabled:");
        System.out.println("   ✓ DLL Load Detection");
        System.out.println("   ✓ Class Load Burst Detection (>10 classes/sec)");
        System.out.println("   ✓ Key Class Detection (PhantomShield/DIY/Native)");
        printSeparator();

        inst.addTransformer(new DumpTransformer());

        System.out.println("[INFO] " + timestamp() + " Class Transformer registered");
        System.out.println("[INFO] " + timestamp() + " All loaded classes will be saved to " + DUMP_DIR + "/");
        System.out.println();

        startAutoDetectThread();

        if (enableTaskMgr || enableObfuscatedFileDetect) {
            startFeatureThreads();
        }

        if (enableGUI) {
            startGUI();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            printFinalReport();
        }));

        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Agent Active - Waiting for class loading...             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void startAutoDetectThread() {
        Thread detectThread = new Thread(() -> {
            long burstWindow = 1000;
            int burstThreshold = 10;

            long startupPeriod = 10000;
            long startupInterval = 500;
            long normalInterval = 2000;
            long stableInterval = 5000;

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

                    if (!dllLoaded) {
                        detectDllLoadSilent();
                        detectLibraryLoadSilent();
                    }

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastClassLoadTime < burstWindow) {
                        classLoadBurstCount++;
                        if (classLoadBurstCount >= burstThreshold) {
                            System.out.println();
                            System.out.println("╔═══════════════════════════════════════════════════════════╗");
                            System.out.println("║   [AUTO-DETECT] ★★★ Class Load Burst Detected! ★★★      ║");
                            System.out.println("╚═══════════════════════════════════════════════════════════╝");
                            System.out.println("   Running Time: " + String.format("%.2f", getRunningTime()) + " sec");
                            System.out.println("   Processed Classes: " + totalClasses.get());
                            printSeparator();
                            classLoadBurstCount = 0;
                        }
                    } else {
                        classLoadBurstCount = 0;
                    }
                    lastClassLoadTime = currentTime;

                    if (phantomShieldLoaded) {
                        System.out.println("[EVENT] PhantomShield class loader loaded (" +
                            String.format("%.2f", getRunningTime()) + "s)");
                        detectedBehaviors.add("PhantomShield class loader loaded at " +
                            String.format("%.2f", getRunningTime()) + "s");
                        phantomShieldLoaded = false;
                    }

                    if (nativeMethodFound) {
                        System.out.println("[EVENT] Native method found (" +
                            String.format("%.2f", getRunningTime()) + "s)");
                        detectedBehaviors.add("Native method found at " +
                            String.format("%.2f", getRunningTime()) + "s");
                        nativeMethodFound = false;
                    }

                    if (dllLoaded && dllPath != null) {
                        System.out.println("[EVENT] DLL loaded: " + dllPath);
                    }

                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "AutoDetect-Thread");

        detectThread.setDaemon(true);
        detectThread.start();
    }

    private static void startGUI() {
        System.out.println("[GUI] " + timestamp() + " Starting Class Viewer GUI...");
        System.out.println("[GUI] " + timestamp() + " GUI runs in parallel with agent - Close window to exit GUI only");
        
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Ignore
            }
            
            ClassViewerGUI gui = new ClassViewerGUI();
            gui.setVisible(true);
        });
    }

    private static void startFeatureThreads() {
        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Feature-Thread");
            t.setDaemon(true);
            return t;
        });

        if (enableTaskMgr) {
            scheduledExecutor.scheduleAtFixedRate(() -> {
                try {
                    printTaskMgrStatus();
                } catch (Exception e) {
                    // Ignore
                }
            }, 30, 30, TimeUnit.SECONDS);

            System.out.println("[TASKMGR] " + timestamp() + " Task Manager started - Status every 30s");
        }

        if (enableObfuscatedFileDetect) {
            scheduledExecutor.schedule(() -> {
                try {
                    System.out.println();
                    System.out.println("╔═══════════════════════════════════════════════════════════╗");
                    System.out.println("║   Obfuscated File Detector - Scanning                     ║");
                    System.out.println("╚═══════════════════════════════════════════════════════════╝");
                    System.out.println();

                    File obfuscatedDir = new File(OBFUSCATED_FILE_DIR);
                    if (!obfuscatedDir.exists()) {
                        obfuscatedDir.mkdirs();
                    }

                    ObfuscatedFileDetector.scanAll();
                    ObfuscatedFileDetector.copyDetectedFiles(OBFUSCATED_FILE_DIR);

                } catch (Exception e) {
                    // Ignore
                }
            }, 10, TimeUnit.SECONDS);

            System.out.println("[OBFUSCATED] " + timestamp() + " Obfuscated file detection started - Scanning in 10s");
        }
    }

    private static void printTaskMgrStatus() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Task Manager - Class Loading Status                     ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        ClassViewer.printSummary();
        ClassViewer.printClassLoaderInfo();
        ClassViewer.printPackageStats();

        if (ClassViewer.isObfuscationDetected()) {
            ClassViewer.printSuspiciousClasses();
        }

        List<ClassViewer.ClassInfo> largestClasses = ClassViewer.getLargestClasses(10);
        if (!largestClasses.isEmpty()) {
            System.out.println();
            System.out.println("■ Top 10 Largest Classes");
            System.out.println();
            System.out.printf("%-50s %10s %15s%n", "Class Name", "Size (KB)", "Load Time");
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

        List<ClassViewer.ClassInfo> latestClasses = ClassViewer.getLatestLoadedClasses(5);
        if (!latestClasses.isEmpty()) {
            System.out.println();
            System.out.println("■ Latest 5 Loaded Classes");
            System.out.println();
            for (ClassViewer.ClassInfo info : latestClasses) {
                System.out.println("  [" + timestamp() + "] " + info.className +
                    " (" + info.size + " bytes)");
            }
        }

        printSeparator();
    }

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
            } else if ("gui".equals(arg) || "swing".equals(arg)) {
                enableGUI = true;
            } else if ("verbose".equals(arg) || "debug".equals(arg) || "v".equals(arg)) {
                verboseLogging = true;
            }
        }
    }

    private static void detectDllLoadSilent() {
        detectDllLoad();
    }

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
                        detectDllLoad();
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private static boolean isLibraryLoaded(String libName) {
        try {
            String[] possibleClasses = {
                "skidonion.sAnhI.___",
                "skidonion.sAnhI.____1",
                "net.hachimi.client._hachimiclient"
            };

            for (String className : possibleClasses) {
                try {
                    Class.forName(className, false, ClassDumpAgent.class.getClassLoader());
                    return true;
                } catch (ClassNotFoundException e) {
                    // Continue
                }
            }

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
            // Ignore
        }

        return false;
    }

    private static void detectDllLoad() {
        boolean found = false;

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

        if (!found) {
            String tempDir = System.getProperty("java.io.tmpdir");
            if (tempDir != null) {
                File tempDll = searchDllInDirectory(tempDir);
                if (tempDll != null) {
                    dllLoaded = true;
                    dllLoadTime = System.currentTimeMillis();
                    dllPath = tempDll.getAbsolutePath();
                    found = true;

                    reportDllFound("temp directory");
                    detectedBehaviors.add("Temp DLL found: " + dllPath);
                }

                if (!found) {
                    scanTempDirectory(tempDir);
                }
            }
        }

        if (!found) {
            String userHome = System.getProperty("user.home");
            if (userHome != null) {
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

        if (!found && (totalClasses.get() % 500 == 0) && totalClasses.get() > 0) {
            System.out.println("[AUTO-DETECT] " + timestamp() + " Running global DLL search... (processed " + totalClasses.get() + " classes)");
            searchDllGlobally();
        }

        if (!found && totalClasses.get() > 2000 && totalClasses.get() % 1000 == 0) {
            System.out.println();
            System.out.println("[AUTO-DETECT] " + timestamp() + " Warning: Processed " + totalClasses.get() + " classes, DLL not detected");
            System.out.println("   Possible reasons:");
            System.out.println("   1. This is Linux version, DLL only loads on Windows");
            System.out.println("   2. Game exited before DLL loaded");
            System.out.println("   3. DLL name is not PhantomShieldX64.dll");
            printSeparator();
        }
    }

    private static void scanTempDirectory(String tempDir) {
        System.out.println("[AUTO-DETECT] " + timestamp() + " Scanning temp directory...");

        File tempPath = new File(tempDir);
        if (!tempPath.exists()) {
            return;
        }

        long fiveMinAgo = System.currentTimeMillis() - (5 * 60 * 1000);
        scanTempDirRecursive(tempPath, fiveMinAgo, 0);
    }

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

                if (fileName.endsWith(".dll") ||
                    fileName.endsWith(".so") ||
                    fileName.endsWith(".dylib") ||
                    fileName.contains("phantom") ||
                    fileName.contains("shield") ||
                    fileName.startsWith("lib") && fileName.endsWith(".tmp")) {

                    System.out.println("[AUTO-DETECT] " + timestamp() + " Suspicious file found: " +
                        file.getAbsolutePath() + " (" + file.length() + " bytes)");

                    if (!dllLoaded && (fileName.endsWith(".dll") || fileName.endsWith(".so"))) {
                        dllLoaded = true;
                        dllLoadTime = System.currentTimeMillis();
                        dllPath = file.getAbsolutePath();

                        System.out.println();
                        System.out.println("╔═══════════════════════════════════════════════════════════╗");
                        System.out.println("║   [AUTO-DETECT] ★★★ Temp DLL Detected! ★★★              ║");
                        System.out.println("╚═══════════════════════════════════════════════════════════╝");
                        System.out.println("   Time: " + timestamp());
                        System.out.println("   Running Time: " + String.format("%.2f", getRunningTime()) + " sec");
                        System.out.println("   DLL Path: " + dllPath);
                        System.out.println("   File Size: " + file.length() + " bytes");

                        copyDllFile();

                        System.out.println("   Suggestion: cp \"" + dllPath + "\" ./analysis/");
                        printSeparator();

                        detectedBehaviors.add("Temp scan DLL found: " + dllPath);
                    }
                }
            }
        }
    }

    private static File searchDllInDirectory(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return null;
        }

        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }

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

        return searchDllRecursive(dir, dllNames, 2);
    }

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
                System.out.println("║   [AUTO-DETECT] ★★★ DLL Found in Global Search! ★★★     ║");
                System.out.println("╚═══════════════════════════════════════════════════════════╝");
                System.out.println("   Time: " + timestamp());
                System.out.println("   Running Time: " + String.format("%.2f", getRunningTime()) + " sec");
                System.out.println("   DLL Path: " + dllPath);

                copyDllFile();

                printSeparator();

                detectedBehaviors.add("Global search DLL found: " + dllPath);
                return;
            }
        }
    }

    private static void reportDllFound(String location) {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   [AUTO-DETECT] ★★★ DLL Loaded! ★★★                     ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println("   Time: " + timestamp());
        System.out.println("   Running Time: " + String.format("%.2f", getRunningTime()) + " sec");
        System.out.println("   Location: " + location);
        System.out.println("   DLL Path: " + dllPath);

        copyDllFile();

        System.out.println("   Suggestion: cp \"" + dllPath + "\" ./analysis/");
        printSeparator();
    }

    private static void copyDllFile() {
        if (dllPath == null || dllPath.isEmpty()) {
            System.err.println("[DLL COPY] Error: DLL path is null");
            return;
        }

        File dllFile = new File(dllPath);
        if (!dllFile.exists()) {
            System.err.println("[DLL COPY] Error: DLL file does not exist: " + dllPath);
            return;
        }

        File copyDir = new File(DLL_COPY_DIR);
        if (!copyDir.exists()) {
            boolean created = copyDir.mkdirs();
            System.out.println("[DLL COPY] Created copy directory: " + copyDir.getAbsolutePath() +
                " -> " + (created ? "Success" : "Failed"));
        }

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

        try {
            copyFile(dllFile, destFile);
            System.out.println();
            System.out.println("╔═══════════════════════════════════════════════════════════╗");
            System.out.println("║   [DLL COPY] ★★★ DLL Copied Successfully! ★★★          ║");
            System.out.println("╚═══════════════════════════════════════════════════════════╝");
            System.out.println("   Source: " + dllFile.getAbsolutePath());
            System.out.println("   Destination: " + destFile.getAbsolutePath());
            System.out.println("   File Size: " + destFile.length() + " bytes");
            printSeparator();
        } catch (IOException e) {
            System.err.println("[DLL COPY] Error: Failed to copy DLL");
            System.err.println("   Source: " + dllFile.getAbsolutePath());
            System.err.println("   Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

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

    private static void recordKeyClassEvent(String className, String eventType) {
        String eventKey = className + ":" + eventType;
        synchronized (detectedBehaviors) {
            if (!detectedBehaviors.contains(eventKey)) {
                detectedBehaviors.add(eventKey);
                System.out.println("[AUTO-DETECT] " + timestamp() + " Key class event: " +
                    className + " - " + eventType);
            }
        }
    }

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

    private static void printFinalReport() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   JVM Shutdown - Final Report                             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        printSeparator();
        System.out.println("[FINAL REPORT] " + timestamp());
        printSeparator();

        System.out.println("■ Statistics");
        System.out.println("   Total Running Time: " + String.format("%.2f", getRunningTime()) + " sec");
        System.out.println("   Total Classes Processed: " + totalClasses.get());
        System.out.println("   Target Classes: " + targetClasses.get());
        System.out.println("   Skipped Classes: " + skippedClasses.get());
        System.out.println("   Errors: " + errorCount.get());

        if (totalClasses.get() > 0) {
            double targetRate = (double) targetClasses.get() / totalClasses.get() * 100;
            System.out.println("   Target Class Rate: " + String.format("%.2f", targetRate) + "%");
        }
        printSeparator();

        System.out.println("■ DLL / Native Library Status");
        if (dllLoaded && dllPath != null) {
            System.out.println("   [SUCCESS] DLL Loaded");
            System.out.println("   Load Time: " + new Date(dllLoadTime));
            System.out.println("   DLL Path: " + dllPath);
            File dllFile = new File(dllPath);
            if (dllFile.exists()) {
                System.out.println("   File Size: " + dllFile.length() + " bytes");
            }
        } else {
            System.out.println("   [INFO] DLL Status: Not detected");
            System.out.println("   Possible reasons:");
            System.out.println("   1. DLL not loaded yet (game not fully started)");
            System.out.println("   2. DLL uses different name");
            System.out.println("   3. DLL is dynamically downloaded or extracted");
            System.out.println("   4. This is Linux version, DLL only loads on Windows");
        }
        printSeparator();

        System.out.println("■ System.loadLibrary() Call Record");
        if (loadLibraryCalled) {
            System.out.println("   [SUCCESS] loadLibrary Called");
            System.out.println("   Library Name: " + lastLibraryName);
            System.out.println("   Call Time: " + new Date(lastLibraryLoadTime));
            System.out.println("   Running Time: " + String.format("%.2f", (lastLibraryLoadTime - startTime) / 1000.0) + " sec");
        } else {
            System.out.println("   [INFO] loadLibrary Status: Not detected");
            System.out.println("   May indicate native library not loaded yet");
        }
        printSeparator();

        System.out.println("■ Key Event Timeline");
        if (!detectedBehaviors.isEmpty()) {
            for (int i = 0; i < detectedBehaviors.size(); i++) {
                System.out.println("   " + (i + 1) + ". " + detectedBehaviors.get(i));
            }
        } else {
            System.out.println("   (No key events)");
        }
        printSeparator();

        if (targetClasses.get() > 0) {
            System.out.println("■ Dump Results");
            System.out.println("   [SUCCESS] Dumped " + targetClasses.get() + " target classes");
            File dumpDir = new File(DUMP_DIR);
            System.out.println("   Save Directory: " + dumpDir.getAbsolutePath());

            long totalSize = calculateDumpSize(dumpDir);
            System.out.println("   Total File Size: " + formatFileSize(totalSize));

            System.out.println("   Directory Structure:");
            listDumpDirectories(dumpDir);
        } else {
            System.out.println("   [WARNING] No target classes dumped");
            System.out.println("   Please verify:");
            System.out.println("   1. Are target classes being loaded?");
            System.out.println("   2. Is TARGET_PACKAGES configuration correct?");
        }
        printSeparator();

        System.out.println("■ Next Steps");
        if (dllLoaded && dllPath != null) {
            System.out.println("   1. Copy DLL file immediately:");
            System.out.println("      cp \"" + dllPath + "\" ./analysis/");
        }
        if (targetClasses.get() > 0) {
            System.out.println("   " + (dllLoaded ? "2." : "1.") + " Decompile dumped classes:");
            System.out.println("      java -jar cfr.jar dumped_classes/ --outputdir decompiled/");
            System.out.println("   " + (dllLoaded ? "3." : "2.") + " Analyze class loader logic:");
            System.out.println("      Focus: skidonion/sAnhI/___.class");
        }
        System.out.println("   4. View full analysis report: DUMP_ANALYSIS.md");

        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Agent Execution Complete                                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

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

    static class DumpTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            String dotClassName = className.replace('/', '.');
            int currentTotal = totalClasses.incrementAndGet();

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

            if (isTarget) {
                checkKeyClass(dotClassName);
            }

            String classLoaderName = (loader == null) ? "Bootstrap" : loader.getClass().getName();
            String sourceLocation = (protectionDomain != null &&
                                     protectionDomain.getCodeSource() != null &&
                                     protectionDomain.getCodeSource().getLocation() != null) ?
                                    protectionDomain.getCodeSource().getLocation().toString() : "(unknown)";
            ClassViewer.recordClassLoad(dotClassName, classLoaderName, classfileBuffer.length, sourceLocation);

            logTransform(currentTotal, dotClassName, loader, protectionDomain,
                         classfileBuffer, isTarget, matchedPackage, classBeingRedefined);

            if (isTarget || shouldDumpAll()) {
                dumpClass(dotClassName, classfileBuffer);
            }

            return classfileBuffer;
        }

        private void logTransform(int seqNum, String className, ClassLoader loader,
                                   ProtectionDomain protectionDomain,
                                   byte[] classData, boolean isTarget,
                                   String matchedPackage, Class<?> classBeingRedefined) {

            if (!verboseLogging) {
                if (isTarget) {
                    System.out.printf("[%s] ★★★ [TARGET] %s (%d bytes)%n",
                            timestamp(), className, classData.length);
                }
                if (seqNum % 500 == 1) {
                    System.out.println();
                    printSeparator();
                    System.out.println("[STATS] " + timestamp() + " Running " +
                        String.format("%.1f", getRunningTime()) + "s - Processed " +
                        seqNum + " classes");
                    System.out.println("   Target: " + targetClasses.get() +
                        " | Skipped: " + skippedClasses.get() +
                        " | Errors: " + errorCount.get());
                    printSeparator();
                }
                return;
            }

            if (seqNum % 100 == 1) {
                System.out.println();
                printSeparator();
                System.out.println("[STATS] " + timestamp() + " Running " +
                    String.format("%.1f", getRunningTime()) + "s - Processed " +
                    seqNum + " classes");
                System.out.println("   Target: " + targetClasses.get() +
                    " | Skipped: " + skippedClasses.get() +
                    " | Errors: " + errorCount.get());
            }

            String logPrefix;
            if (className.startsWith("skidonion/")) {
                logPrefix = "★★★ [TARGET]";
            } else if (className.startsWith("net/hachimi/")) {
                logPrefix = "★★☆ [TARGET]";
            } else if (className.startsWith("tech/skidonion/")) {
                logPrefix = "★☆☆ [TARGET]";
            } else {
                logPrefix = "    [SKIP] ";
            }

            System.out.printf("[%s] %s%s%n",
                    timestamp(),
                    logPrefix,
                    className);

            if (isTarget) {
                System.out.printf("   [INFO] Seq: %d | Size: %d bytes | Running Time: %.2f sec%n",
                        seqNum, classData.length, getRunningTime());

                String loaderName = (loader == null) ? "Bootstrap ClassLoader" :
                                    loader.getClass().getName();
                System.out.println("   [INFO] ClassLoader: " + loaderName);

                if (protectionDomain != null && protectionDomain.getCodeSource() != null) {
                    String location = protectionDomain.getCodeSource().getLocation() != null ?
                                      protectionDomain.getCodeSource().getLocation().toString() :
                                      "(unknown)";
                    if (location.length() > 60) {
                        location = "..." + location.substring(location.length() - 57);
                    }
                    System.out.println("   [INFO] Source: " + location);
                }

                if (classBeingRedefined != null) {
                    System.out.println("   [INFO] Redefined Class: " + classBeingRedefined.getName());
                }

                if (className.contains("PhantomShield") ||
                    className.contains("___") ||
                    className.contains("sAnhI")) {
                    System.out.println("   [!!!] ★★★ Key Class Detected! ★★★");
                }

                if (className.contains("Native") || className.contains("JNI")) {
                    System.out.println("   [!!!] Native/JNI class detected");
                }
            }
        }

        private void dumpClass(String className, byte[] classData) {
            try {
                String packagePath = "";
                int lastDot = className.lastIndexOf('.');
                if (lastDot > 0) {
                    packagePath = className.substring(0, lastDot).replace('.', File.separatorChar);
                }

                File packageDir = new File(DUMP_DIR, packagePath);
                if (!packageDir.exists()) {
                    boolean created = packageDir.mkdirs();
                    System.out.printf("   [DEBUG] Created directory: %s -> %s%n",
                            packageDir.getAbsolutePath(), created ? "Success" : "Failed");
                }

                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                simpleName = simpleName.replaceAll("[^a-zA-Z0-9_$]", "_");
                String fileName = simpleName + ".class";

                File outputFile = new File(packageDir, fileName);

                if (outputFile.exists()) {
                    String baseName = simpleName;
                    int counter = 1;
                    do {
                        fileName = baseName + "_" + counter + ".class";
                        outputFile = new File(packageDir, fileName);
                        counter++;
                    } while (outputFile.exists());
                    System.out.printf("   [DEBUG] File exists, using new name: %s%n", fileName);
                }

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(classData);
                    System.out.printf("   [DUMP] Saved: %s (%d bytes)%n",
                            outputFile.getAbsolutePath(), classData.length);
                }

            } catch (IOException e) {
                System.err.println("   [ERROR] Failed to dump class: " + className);
                System.err.println("   Error: " + e.getMessage());
                errorCount.incrementAndGet();
            }
        }

        private boolean shouldDumpAll() {
            return false;
        }
    }
}
