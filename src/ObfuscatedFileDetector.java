package com.hachimi.dump;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 混淆文件檢測器 - 檢測被混淆的 DLL 和 SO 文件
 * 
 * 功能：
 * 1. 檢測副檔名被混淆的文件（如 .dl_, .so_ 等）
 * 2. 檢測文件名被混淆的動態庫文件
 * 3. 掃描暫存目錄中的可疑文件
 * 4. 檢測偽裝成其他文件的動態庫
 */
public class ObfuscatedFileDetector {

    // 檢測到的混淆文件記錄
    private static final List<ObfuscatedFileInfo> detectedFiles = new ArrayList<>();
    
    // 掃描路徑
    private static final Set<String> scannedPaths = ConcurrentHashMap.newKeySet();
    
    // 可疑擴展名
    private static final String[] SUSPICIOUS_EXTENSIONS = {
        ".dl_", ".so_", ".dy_", ".pa_", ".bin", ".dat", ".tmp", ".temp",
        ".dl$", ".so$", ".encrypted", ".encoded", ".obf", ".packed"
    };
    
    // 目標動態庫擴展名
    private static final String[] LIBRARY_EXTENSIONS = {
        ".dll", ".so", ".dylib", ".jnilib"
    };
    
    // 可疑文件名模式
    private static final String[] SUSPICIOUS_PATTERNS = {
        "^[a-z]{1,3}\\d{0,2}\\.",  // 短文件名 + 數字
        "^lib[a-z]{1,3}\\.",        // lib + 短名
        "^\\d+\\.",                  // 純數字開頭
        "^[a-f0-9]{8,}\\.",         // 哈希樣式
        "^_\\."                      // 下劃線開頭
    };

    /**
     * 混淆文件信息
     */
    public static class ObfuscatedFileInfo {
        public final String filePath;
        public final String originalName;
        public final String suspectedRealExtension;
        public final long fileSize;
        public final long createTime;
        public final String obfuscationType;
        public final String location;
        public final boolean isLibrary;

        public ObfuscatedFileInfo(String filePath, String suspectedRealExtension,
                                   String obfuscationType, boolean isLibrary) {
            this.filePath = filePath;
            this.originalName = new File(filePath).getName();
            this.suspectedRealExtension = suspectedRealExtension;
            this.fileSize = new File(filePath).length();
            this.createTime = ClassViewer.getLoadedClasses().isEmpty() ?
                             0 : ClassViewer.getLoadedClasses().iterator().next().loadTime;
            this.obfuscationType = obfuscationType;
            this.location = new File(filePath).getParent();
            this.isLibrary = isLibrary;
        }
    }

    /**
     * 掃描暫存目錄中的混淆文件
     */
    public static void scanTempDirectory() {
        String tempDir = System.getProperty("java.io.tmpdir");
        if (tempDir != null) {
            scanDirectoryRecursive(new File(tempDir), 3);
        }
    }

    /**
     * 掃描用戶主目錄
     */
    public static void scanUserHome() {
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            // 掃描常見的遊戲和應用目錄
            String[] paths = {
                userHome + "/.minecraft",
                userHome + "/.minecraft/natives",
                userHome + "/.minecraft/versions",
                userHome + "/AppData/Local",
                userHome + "/AppData/Local/Temp",
                userHome + "/AppData/Roaming",
                userHome + "/Library/Application Support"
            };
            
            for (String path : paths) {
                File dir = new File(path);
                if (dir.exists()) {
                    scanDirectoryRecursive(dir, 4);
                }
            }
        }
    }

    /**
     * 掃描當前工作目錄
     */
    public static void scanWorkingDirectory() {
        String workDir = System.getProperty("user.dir");
        if (workDir != null) {
            scanDirectoryRecursive(new File(workDir), 3);
        }
    }

    /**
     * 掃描 java.library.path
     */
    public static void scanLibraryPath() {
        String libraryPath = System.getProperty("java.library.path");
        if (libraryPath != null) {
            String[] paths = libraryPath.split(File.pathSeparator);
            for (String path : paths) {
                File dir = new File(path);
                if (dir.exists()) {
                    scanDirectoryRecursive(dir, 2);
                }
            }
        }
    }

    /**
     * 遞歸掃描目錄
     */
    private static void scanDirectoryRecursive(File dir, int maxDepth) {
        scanDirectoryRecursiveImpl(dir, maxDepth, 0);
    }

    private static void scanDirectoryRecursiveImpl(File dir, int maxDepth, int currentDepth) {
        if (currentDepth >= maxDepth || !dir.exists() || !dir.isDirectory()) {
            return;
        }

        // 跳過特殊目錄
        String dirName = dir.getName();
        if (dirName.startsWith(".") || 
            dirName.equals("node_modules") || 
            dirName.equals(".git") ||
            dirName.equals("cache")) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectoryRecursiveImpl(file, maxDepth, currentDepth + 1);
            } else {
                ObfuscatedFileInfo info = analyzeFile(file);
                if (info != null) {
                    synchronized (detectedFiles) {
                        if (!isDuplicate(info.filePath)) {
                            detectedFiles.add(info);
                            System.out.println("[OBFUSCATED FILE] 發現可疑文件：" + 
                                info.filePath);
                            System.out.println("   類型：" + info.obfuscationType);
                            System.out.println("   大小：" + formatSize(info.fileSize));
                            System.out.println("   位置：" + info.location);
                            if (info.isLibrary) {
                                System.out.println("   ★★★ 可能是動態庫 ★★★");
                            }
                            System.out.println();
                        }
                    }
                }
            }
        }
    }

    /**
     * 分析文件是否為混淆文件
     */
    private static ObfuscatedFileInfo analyzeFile(File file) {
        String fileName = file.getName().toLowerCase();
        String filePath = file.getAbsolutePath();
        
        // 檢查副檔名混淆
        for (String ext : SUSPICIOUS_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                String realExt = guessRealExtension(ext);
                boolean isLibrary = isLikelyLibrary(realExt);
                return new ObfuscatedFileInfo(filePath, realExt, 
                    "Extension obfuscation: " + ext + " → " + realExt, isLibrary);
            }
        }
        
        // 檢查文件名模式
        for (String pattern : SUSPICIOUS_PATTERNS) {
            if (fileName.matches(pattern)) {
                boolean isLibrary = fileName.endsWith(".dll") || 
                                   fileName.endsWith(".so") ||
                                   fileName.endsWith(".dylib");
                return new ObfuscatedFileInfo(filePath, "(unknown)", 
                    "Suspicious filename pattern: " + fileName, isLibrary);
            }
        }
        
        // 檢查無擴展名的二進制文件
        if (!fileName.contains(".") || fileName.lastIndexOf('.') == 0) {
            if (file.length() > 1024) { // 大於 1KB
                // 檢查文件頭
                String magic = readFileHeader(file);
                if (magic != null && (magic.startsWith("MZ") || 
                    magic.contains("ELF") || magic.contains("dylib"))) {
                    return new ObfuscatedFileInfo(filePath, "(binary)", 
                        "Binary without extension", true);
                }
            }
        }
        
        // 檢查偽裝的文件
        if (fileName.endsWith(".txt") || fileName.endsWith(".log") || 
            fileName.endsWith(".cfg") || fileName.endsWith(".ini")) {
            if (file.length() > 100 * 1024) { // 大於 100KB
                String magic = readFileHeader(file);
                if (magic != null && (magic.startsWith("MZ") || magic.contains("ELF"))) {
                    return new ObfuscatedFileInfo(filePath, ".dll/.so", 
                        "Disguised as " + fileName.substring(fileName.lastIndexOf('.')), 
                        true);
                }
            }
        }
        
        return null;
    }

    /**
     * 猜測真實的擴展名
     */
    private static String guessRealExtension(String obfuscatedExt) {
        switch (obfuscatedExt) {
            case ".dl_":
            case ".dl$":
                return ".dll";
            case ".so_":
            case ".so$":
                return ".so";
            case ".dy_":
                return ".dylib";
            case ".pa_":
                return ".pack";
            default:
                return "(unknown)";
        }
    }

    /**
     * 判斷是否為動態庫
     */
    private static boolean isLikelyLibrary(String extension) {
        return extension.equals(".dll") || 
               extension.equals(".so") || 
               extension.equals(".dylib") ||
               extension.equals(".jnilib");
    }

    /**
     * 讀取文件頭（用於識別二進制格式）
     */
    private static String readFileHeader(File file) {
        try {
            byte[] header = new byte[16];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int read = fis.read(header);
                if (read < 2) {
                    return null;
                }
            }
            
            // 檢查 MZ (Windows PE)
            if (header[0] == 'M' && header[1] == 'Z') {
                return "MZ (PE/EXE/DLL)";
            }
            
            // 檢查 ELF (Linux SO)
            if (header[0] == 0x7F && header[1] == 'E' && 
                header[2] == 'L' && header[3] == 'F') {
                return "ELF (Shared Object)";
            }
            
            // 檢查 Mach-O (macOS DYLIB)
            if ((header[0] == 0xFE && header[1] == 0xED && header[2] == 0xFA && header[3] == 0xCE) ||
                (header[0] == 0xCE && header[1] == 0xFA && header[2] == 0xED && header[3] == 0xFE) ||
                (header[0] == 0xCF && header[1] == 0xFA && header[2] == 0xED && header[3] == 0xFE) ||
                (header[0] == 0xFE && header[1] == 0xED && header[2] == 0xFA && header[3] == 0xCF)) {
                return "Mach-O (Dynamic Library)";
            }
            
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 檢查是否為重複文件
     */
    private static boolean isDuplicate(String filePath) {
        for (ObfuscatedFileInfo info : detectedFiles) {
            if (info.filePath.equals(filePath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 獲取所有檢測到的混淆文件
     */
    public static List<ObfuscatedFileInfo> getDetectedFiles() {
        synchronized (detectedFiles) {
            return new ArrayList<>(detectedFiles);
        }
    }

    /**
     * 獲取檢測到的動態庫文件
     */
    public static List<ObfuscatedFileInfo> getLibraryFiles() {
        synchronized (detectedFiles) {
            List<ObfuscatedFileInfo> libs = new ArrayList<>();
            for (ObfuscatedFileInfo info : detectedFiles) {
                if (info.isLibrary) {
                    libs.add(info);
                }
            }
            return libs;
        }
    }

    /**
     * 打印混淆文件報告
     */
    public static void printReport() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   混淆文件檢測報告                                         ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        List<ObfuscatedFileInfo> files = getDetectedFiles();
        List<ObfuscatedFileInfo> libs = getLibraryFiles();

        if (files.isEmpty()) {
            System.out.println("  未發現可疑的混淆文件");
            return;
        }

        System.out.println("■ 統計摘要");
        System.out.println("   檢測到的可疑文件總數：" + files.size());
        System.out.println("   其中可能是動態庫的：" + libs.size());
        System.out.println();

        // 按類型分類
        Map<String, List<ObfuscatedFileInfo>> byType = new HashMap<>();
        for (ObfuscatedFileInfo info : files) {
            byType.computeIfAbsent(info.obfuscationType, k -> new ArrayList<>()).add(info);
        }

        System.out.println("■ 按混淆類型分類");
        for (Map.Entry<String, List<ObfuscatedFileInfo>> entry : byType.entrySet()) {
            System.out.println("   [" + entry.getKey() + "]: " + entry.getValue().size() + " 個文件");
        }
        System.out.println();

        // 顯示動態庫文件
        if (!libs.isEmpty()) {
            System.out.println("■ 可疑的動態庫文件");
            System.out.println();
            System.out.printf("%-60s %10s %15s%n", "文件路徑", "大小", "混淆類型");
            System.out.println("─────────────────────────────────────────────────────────────────");

            for (ObfuscatedFileInfo info : libs) {
                String path = info.filePath;
                if (path.length() > 58) {
                    path = "..." + path.substring(path.length() - 55);
                }
                System.out.printf("%-60s %10s %15s%n",
                        path,
                        formatSize(info.fileSize),
                        info.obfuscationType.substring(0, 
                            Math.min(15, info.obfuscationType.length())));
            }
            System.out.println();
        }

        // 顯示所有文件（限制 50 個）
        System.out.println("■ 檢測到的文件列表（最多 50 個）");
        System.out.println();
        int count = 0;
        for (ObfuscatedFileInfo info : files) {
            if (count >= 50) {
                System.out.println("... 還有 " + (files.size() - 50) + " 個文件");
                break;
            }
            System.out.println("  [" + (count + 1) + "] " + info.filePath);
            System.out.println("      類型：" + info.obfuscationType);
            System.out.println("      大小：" + formatSize(info.fileSize));
            if (info.isLibrary) {
                System.out.println("      ★★★ 可能是動態庫 ★★★");
            }
            System.out.println();
            count++;
        }
    }

    /**
     * 格式化文件大小
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 複製檢測到的文件到分析目錄
     */
    public static void copyDetectedFiles(String destDir) {
        File destPath = new File(destDir);
        if (!destPath.exists()) {
            destPath.mkdirs();
        }

        List<ObfuscatedFileInfo> libs = getLibraryFiles();
        int copied = 0;

        for (ObfuscatedFileInfo info : libs) {
            File src = new File(info.filePath);
            if (src.exists()) {
                try {
                    File dest = new File(destPath, 
                        "obfuscated_" + System.currentTimeMillis() + "_" + src.getName());
                    copyFile(src, dest);
                    System.out.println("[COPY] 已複製：" + info.filePath + " → " + dest.getAbsolutePath());
                    copied++;
                } catch (IOException e) {
                    System.err.println("[COPY ERROR] 複製失敗：" + info.filePath);
                }
            }
        }

        System.out.println("總共複製了 " + copied + " 個文件到：" + destDir);
    }

    /**
     * 複製文件
     */
    private static void copyFile(File source, File dest) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(source);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
             java.nio.channels.FileChannel srcChannel = fis.getChannel();
             java.nio.channels.FileChannel destChannel = fos.getChannel()) {
            
            long size = srcChannel.size();
            long copied = 0;
            while (copied < size) {
                long bytes = srcChannel.transferTo(copied, size - copied, destChannel);
                if (bytes <= 0) break;
                copied += bytes;
            }
        }
    }

    /**
     * 執行全面掃描
     */
    public static void scanAll() {
        System.out.println("[OBFUSCATED FILE DETECTOR] 開始全面掃描...");
        System.out.println();
        
        System.out.println("  [1/4] 掃描暫存目錄...");
        scanTempDirectory();
        
        System.out.println("  [2/4] 掃描用戶主目錄...");
        scanUserHome();
        
        System.out.println("  [3/4] 掃描當前工作目錄...");
        scanWorkingDirectory();
        
        System.out.println("  [4/4] 掃描 java.library.path...");
        scanLibraryPath();
        
        System.out.println();
        System.out.println("  掃描完成！共檢測到 " + getDetectedFiles().size() + " 個可疑文件");
        System.out.println("  其中動態庫：" + getLibraryFiles().size() + " 個");
    }
}
