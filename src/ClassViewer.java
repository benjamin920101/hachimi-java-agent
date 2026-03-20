package com.hachimi.dump;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 類加載查看器 - 類似任務管理器的類監控功能
 * 
 * 功能：
 * 1. 實時查看所有已加載的類
 * 2. 監控類加載器的活動
 * 3. 顯示類加載統計信息
 * 4. 檢測異常類加載行為
 */
public class ClassViewer {

    // 已加載的類記錄
    private static final Map<String, ClassInfo> loadedClasses = new ConcurrentHashMap<>();
    
    // 類加載器統計
    private static final Map<String, ClassLoaderStats> classLoaderStats = new ConcurrentHashMap<>();
    
    // 統計信息
    private static final Map<String, Long> packageStats = new ConcurrentHashMap<>();
    
    // 啟動時間
    private static final long startTime = System.currentTimeMillis();
    
    // 總加載類數
    private static long totalLoadedCount = 0;
    
    // 可疑類檢測
    private static final List<String> suspiciousClasses = new ArrayList<>();
    
    // 混淆檢測標記
    private static boolean obfuscationDetected = false;

    /**
     * 類信息記錄
     */
    public static class ClassInfo {
        public final String className;
        public final String classLoader;
        public final long loadTime;
        public final int size;
        public final String packageName;
        public final boolean isObfuscated;
        public final String sourceLocation;

        public ClassInfo(String className, String classLoader, int size, String sourceLocation) {
            this.className = className;
            this.classLoader = classLoader;
            this.loadTime = System.currentTimeMillis() - startTime;
            this.size = size;
            this.packageName = extractPackageName(className);
            this.sourceLocation = sourceLocation;
            this.isObfuscated = detectObfuscation(className);
        }

        private String extractPackageName(String className) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                return className.substring(0, lastDot);
            }
            return "(default package)";
        }

        private boolean detectObfuscation(String className) {
            // 檢測混淆特徵
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            
            // 過短的類名（可能是 a, b, c 等）
            if (simpleName.length() <= 2 && simpleName.matches("[a-zA-Z]+")) {
                return true;
            }
            
            // 無意義的字符組合
            if (simpleName.matches("^[a-zA-Z]{1,3}\\d*$")) {
                return true;
            }
            
            // 包含特殊字符
            if (simpleName.contains("$") && simpleName.split("\\$").length > 3) {
                return true;
            }
            
            return false;
        }
    }

    /**
     * 類加載器統計
     */
    public static class ClassLoaderStats {
        public final String name;
        public long loadedCount = 0;
        public long totalSize = 0;
        public long firstLoadTime;
        public long lastLoadTime;

        public ClassLoaderStats(String name) {
            this.name = name;
            this.firstLoadTime = System.currentTimeMillis() - startTime;
        }

        public void recordClass(int size) {
            loadedCount++;
            totalSize += size;
            lastLoadTime = System.currentTimeMillis() - startTime;
        }
    }

    /**
     * 記錄類加載
     */
    public static void recordClassLoad(String className, String classLoader, int size, String sourceLocation) {
        ClassInfo info = new ClassInfo(className, classLoader, size, sourceLocation);
        loadedClasses.put(className, info);
        totalLoadedCount++;

        // 更新類加載器統計
        classLoaderStats.computeIfAbsent(classLoader, k -> new ClassLoaderStats(classLoader))
                       .recordClass(size);

        // 更新包統計
        packageStats.merge(info.packageName, 1L, Long::sum);

        // 檢測可疑類
        if (info.isObfuscated) {
            synchronized (suspiciousClasses) {
                if (!suspiciousClasses.contains(className)) {
                    suspiciousClasses.add(className);
                }
            }
            obfuscationDetected = true;
        }
    }

    /**
     * 獲取所有已加載的類
     */
    public static Collection<ClassInfo> getLoadedClasses() {
        return new ArrayList<>(loadedClasses.values());
    }

    /**
     * 獲取已加載類數量
     */
    public static int getLoadedClassCount() {
        return loadedClasses.size();
    }

    /**
     * 獲取類加載器統計
     */
    public static Collection<ClassLoaderStats> getClassLoaderStats() {
        return new ArrayList<>(classLoaderStats.values());
    }

    /**
     * 獲取包統計
     */
    public static Map<String, Long> getPackageStats() {
        return new HashMap<>(packageStats);
    }

    /**
     * 獲取可疑類列表
     */
    public static List<String> getSuspiciousClasses() {
        synchronized (suspiciousClasses) {
            return new ArrayList<>(suspiciousClasses);
        }
    }

    /**
     * 是否檢測到混淆
     */
    public static boolean isObfuscationDetected() {
        return obfuscationDetected;
    }

    /**
     * 打印類加載器信息
     */
    public static void printClassLoaderInfo() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   類加載器信息                                             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        List<ClassLoaderStats> stats = new ArrayList<>(classLoaderStats.values());
        stats.sort((a, b) -> Long.compare(b.loadedCount, a.loadedCount));

        System.out.printf("%-60s %10s %12s %15s%n", "類加載器", "類數量", "總大小 (KB)", "最後活動時間");
        System.out.println("─────────────────────────────────────────────────────────────────");

        for (ClassLoaderStats stat : stats) {
            String name = stat.name;
            if (name.length() > 58) {
                name = "..." + name.substring(name.length() - 55);
            }
            System.out.printf("%-60s %10d %12.2f %15.2fs%n",
                    name,
                    stat.loadedCount,
                    stat.totalSize / 1024.0,
                    stat.lastLoadTime / 1000.0);
        }

        System.out.println("─────────────────────────────────────────────────────────────────");
        System.out.printf("%-60s %10d %12.2f%n", "總計", totalLoadedCount,
                stats.stream().mapToLong(s -> s.totalSize).sum() / 1024.0);
    }

    /**
     * 打印包統計信息
     */
    public static void printPackageStats() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   包統計信息                                               ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        Map<String, Long> sortedStats = new HashMap<>(packageStats);
        List<Map.Entry<String, Long>> list = new ArrayList<>(sortedStats.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        System.out.printf("%-50s %10s %10s%n", "包名", "類數量", "百分比");
        System.out.println("─────────────────────────────────────────────────────────────────");

        for (Map.Entry<String, Long> entry : list) {
            String packageName = entry.getKey();
            if (packageName.length() > 48) {
                packageName = "..." + packageName.substring(packageName.length() - 45);
            }
            double percentage = (double) entry.getValue() / totalLoadedCount * 100;
            System.out.printf("%-50s %10d %9.2f%%%n", packageName, entry.getValue(), percentage);
        }

        // 顯示前 20 個
        int count = 0;
        for (Map.Entry<String, Long> entry : list) {
            if (++count >= 20) break;
        }
        if (list.size() > 20) {
            System.out.println("... 還有 " + (list.size() - 20) + " 個包");
        }
    }

    /**
     * 打印可疑類信息
     */
    public static void printSuspiciousClasses() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   可疑/混淆類檢測                                          ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        List<String> suspicious = getSuspiciousClasses();
        
        if (suspicious.isEmpty()) {
            System.out.println("  未發現可疑類");
            return;
        }

        System.out.println("  檢測到 " + suspicious.size() + " 個可疑類：");
        System.out.println();

        // 分類顯示
        Map<String, List<String>> categorized = new HashMap<>();
        for (String className : suspicious) {
            String pkg = className.substring(0, Math.min(className.lastIndexOf('.'), className.length()));
            categorized.computeIfAbsent(pkg, k -> new ArrayList<>()).add(className);
        }

        int displayed = 0;
        for (Map.Entry<String, List<String>> entry : categorized.entrySet()) {
            if (displayed >= 50) {
                System.out.println("  ... 還有 " + (suspicious.size() - displayed) + " 個");
                break;
            }
            
            System.out.println("  [" + entry.getKey() + "]");
            for (String cls : entry.getValue()) {
                if (displayed >= 50) break;
                System.out.println("    - " + cls);
                displayed++;
            }
            System.out.println();
        }
    }

    /**
     * 打印類加載摘要
     */
    public static void printSummary() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   類加載摘要                                               ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("  運行時間：" + String.format("%.2f", (System.currentTimeMillis() - startTime) / 1000.0) + " 秒");
        System.out.println("  已加載類總數：" + totalLoadedCount);
        System.out.println("  當前活躍類：" + loadedClasses.size());
        System.out.println("  類加載器數量：" + classLoaderStats.size());
        System.out.println("  包數量：" + packageStats.size());
        System.out.println("  可疑類數量：" + getSuspiciousClasses().size());
        
        if (obfuscationDetected) {
            System.out.println("  ★★★ 檢測到混淆跡象 ★★★");
        }
    }

    /**
     * 搜索類
     */
    public static List<ClassInfo> searchClasses(String keyword) {
        List<ClassInfo> results = new ArrayList<>();
        for (ClassInfo info : loadedClasses.values()) {
            if (info.className.toLowerCase().contains(keyword.toLowerCase())) {
                results.add(info);
            }
        }
        return results;
    }

    /**
     * 按包名過濾類
     */
    public static List<ClassInfo> filterByPackage(String packageName) {
        List<ClassInfo> results = new ArrayList<>();
        for (ClassInfo info : loadedClasses.values()) {
            if (info.packageName.equals(packageName) || 
                info.className.startsWith(packageName + ".")) {
                results.add(info);
            }
        }
        return results;
    }

    /**
     * 獲取最大類
     */
    public static List<ClassInfo> getLargestClasses(int limit) {
        List<ClassInfo> all = new ArrayList<>(loadedClasses.values());
        all.sort((a, b) -> Integer.compare(b.size, a.size));
        return all.subList(0, Math.min(limit, all.size()));
    }

    /**
     * 獲取最新加載的類
     */
    public static List<ClassInfo> getLatestLoadedClasses(int limit) {
        List<ClassInfo> all = new ArrayList<>(loadedClasses.values());
        all.sort((a, b) -> Long.compare(b.loadTime, a.loadTime));
        return all.subList(0, Math.min(limit, all.size()));
    }
}
