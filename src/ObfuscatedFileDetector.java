package com.hachimi.dump;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Obfuscated File Detector - Detects obfuscated DLL and SO files
 */
public class ObfuscatedFileDetector {

    private static final List<ObfuscatedFileInfo> detectedFiles = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> scannedPaths = Collections.synchronizedSet(new HashSet<>());

    private static final String[] SUSPICIOUS_EXTENSIONS = {
        ".dl_", ".so_", ".dy_", ".pa_", ".bin", ".dat", ".tmp", ".temp",
        ".dl$", ".so$", ".encrypted", ".encoded", ".obf", ".packed"
    };

    private static final String[] LIBRARY_EXTENSIONS = {
        ".dll", ".so", ".dylib", ".jnilib"
    };

    private static final String[] SUSPICIOUS_PATTERNS = {
        "^[a-z]{1,3}\\d{0,2}\\.",
        "^lib[a-z]{1,3}\\.",
        "^\\d+\\.",
        "^[a-f0-9]{8,}\\.",
        "^_\\."
    };

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
            this.createTime = 0;
            this.obfuscationType = obfuscationType;
            this.location = new File(filePath).getParent();
            this.isLibrary = isLibrary;
        }
    }

    public static void scanTempDirectory() {
        String tempDir = System.getProperty("java.io.tmpdir");
        if (tempDir != null) {
            scanDirectoryRecursive(new File(tempDir), 3);
        }
    }

    public static void scanUserHome() {
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
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

    public static void scanWorkingDirectory() {
        String workDir = System.getProperty("user.dir");
        if (workDir != null) {
            scanDirectoryRecursive(new File(workDir), 3);
        }
    }

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

    private static void scanDirectoryRecursive(File dir, int maxDepth) {
        scanDirectoryRecursiveImpl(dir, maxDepth, 0);
    }

    private static void scanDirectoryRecursiveImpl(File dir, int maxDepth, int currentDepth) {
        if (currentDepth >= maxDepth || !dir.exists() || !dir.isDirectory()) {
            return;
        }

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
                            System.out.println("[OBFUSCATED FILE] Suspicious file found: " +
                                info.filePath);
                            System.out.println("   Type: " + info.obfuscationType);
                            System.out.println("   Size: " + formatSize(info.fileSize));
                            System.out.println("   Location: " + info.location);
                            if (info.isLibrary) {
                                System.out.println("   ★★★ Possible library ★★★");
                            }
                            System.out.println();
                        }
                    }
                }
            }
        }
    }

    private static ObfuscatedFileInfo analyzeFile(File file) {
        String fileName = file.getName().toLowerCase();
        String filePath = file.getAbsolutePath();

        for (String ext : SUSPICIOUS_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                String realExt = guessRealExtension(ext);
                boolean isLibrary = isLikelyLibrary(realExt);
                return new ObfuscatedFileInfo(filePath, realExt,
                    "Extension obfuscation: " + ext + " -> " + realExt, isLibrary);
            }
        }

        for (String pattern : SUSPICIOUS_PATTERNS) {
            if (fileName.matches(pattern)) {
                boolean isLibrary = fileName.endsWith(".dll") ||
                                   fileName.endsWith(".so") ||
                                   fileName.endsWith(".dylib");
                return new ObfuscatedFileInfo(filePath, "(unknown)",
                    "Suspicious filename pattern: " + fileName, isLibrary);
            }
        }

        if (!fileName.contains(".") || fileName.lastIndexOf('.') == 0) {
            if (file.length() > 1024) {
                String magic = readFileHeader(file);
                if (magic != null && (magic.startsWith("MZ") ||
                    magic.contains("ELF") || magic.contains("dylib"))) {
                    return new ObfuscatedFileInfo(filePath, "(binary)",
                        "Binary without extension", true);
                }
            }
        }

        if (fileName.endsWith(".txt") || fileName.endsWith(".log") ||
            fileName.endsWith(".cfg") || fileName.endsWith(".ini")) {
            if (file.length() > 100 * 1024) {
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

    private static boolean isLikelyLibrary(String extension) {
        return extension.equals(".dll") ||
               extension.equals(".so") ||
               extension.equals(".dylib") ||
               extension.equals(".jnilib");
    }

    private static String readFileHeader(File file) {
        try {
            byte[] header = new byte[16];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int read = fis.read(header);
                if (read < 2) {
                    return null;
                }
            }

            if (header[0] == 'M' && header[1] == 'Z') {
                return "MZ (PE/EXE/DLL)";
            }

            if (header[0] == 0x7F && header[1] == 'E' &&
                header[2] == 'L' && header[3] == 'F') {
                return "ELF (Shared Object)";
            }

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

    private static boolean isDuplicate(String filePath) {
        for (ObfuscatedFileInfo info : detectedFiles) {
            if (info.filePath.equals(filePath)) {
                return true;
            }
        }
        return false;
    }

    public static List<ObfuscatedFileInfo> getDetectedFiles() {
        synchronized (detectedFiles) {
            return new ArrayList<>(detectedFiles);
        }
    }

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

    public static void printReport() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Obfuscated File Detection Report                        ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        List<ObfuscatedFileInfo> files = getDetectedFiles();
        List<ObfuscatedFileInfo> libs = getLibraryFiles();

        if (files.isEmpty()) {
            System.out.println("  No suspicious obfuscated files found");
            return;
        }

        System.out.println("■ Summary");
        System.out.println("   Total Suspicious Files: " + files.size());
        System.out.println("   Possible Libraries: " + libs.size());
        System.out.println();

        Map<String, List<ObfuscatedFileInfo>> byType = new HashMap<>();
        for (ObfuscatedFileInfo info : files) {
            byType.computeIfAbsent(info.obfuscationType, k -> new ArrayList<>()).add(info);
        }

        System.out.println("■ By Obfuscation Type");
        for (Map.Entry<String, List<ObfuscatedFileInfo>> entry : byType.entrySet()) {
            System.out.println("   [" + entry.getKey() + "]: " + entry.getValue().size() + " files");
        }
        System.out.println();

        if (!libs.isEmpty()) {
            System.out.println("■ Suspicious Library Files");
            System.out.println();
            System.out.printf("%-60s %10s %15s%n", "File Path", "Size", "Obfuscation Type");
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

        System.out.println("■ Detected Files (max 50)");
        System.out.println();
        int count = 0;
        for (ObfuscatedFileInfo info : files) {
            if (count >= 50) {
                System.out.println("... and " + (files.size() - 50) + " more files");
                break;
            }
            System.out.println("  [" + (count + 1) + "] " + info.filePath);
            System.out.println("      Type: " + info.obfuscationType);
            System.out.println("      Size: " + formatSize(info.fileSize));
            if (info.isLibrary) {
                System.out.println("      ★★★ Possible library ★★★");
            }
            System.out.println();
            count++;
        }
    }

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
                    System.out.println("[COPY] Copied: " + info.filePath + " -> " + dest.getAbsolutePath());
                    copied++;
                } catch (IOException e) {
                    System.err.println("[COPY ERROR] Failed to copy: " + info.filePath);
                }
            }
        }

        System.out.println("Total copied: " + copied + " files to: " + destDir);
    }

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

    public static void scanAll() {
        System.out.println("[OBFUSCATED FILE DETECTOR] Starting full scan...");
        System.out.println();

        System.out.println("  [1/4] Scanning temp directory...");
        scanTempDirectory();

        System.out.println("  [2/4] Scanning user home...");
        scanUserHome();

        System.out.println("  [3/4] Scanning working directory...");
        scanWorkingDirectory();

        System.out.println("  [4/4] Scanning java.library.path...");
        scanLibraryPath();

        System.out.println();
        System.out.println("  Scan complete! Detected " + getDetectedFiles().size() + " suspicious files");
        System.out.println("  Libraries: " + getLibraryFiles().size());
    }
}
