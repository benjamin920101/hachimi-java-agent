package com.hachimi.dump;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class Loader Viewer - Class loading monitoring
 */
public class ClassViewer {

    private static final Map<String, ClassInfo> loadedClasses = new ConcurrentHashMap<>();
    private static final Map<String, ClassLoaderStats> classLoaderStats = new ConcurrentHashMap<>();
    private static final Map<String, Long> packageStats = new ConcurrentHashMap<>();
    private static final long startTime = System.currentTimeMillis();
    private static long totalLoadedCount = 0;
    private static final List<String> suspiciousClasses = new ArrayList<>();
    private static boolean obfuscationDetected = false;

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
            String simpleName = className.substring(className.lastIndexOf('.') + 1);

            if (simpleName.length() <= 2 && simpleName.matches("[a-zA-Z]+")) {
                return true;
            }

            if (simpleName.matches("^[a-zA-Z]{1,3}\\d*$")) {
                return true;
            }

            if (simpleName.contains("$") && simpleName.split("\\$").length > 3) {
                return true;
            }

            return false;
        }
    }

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

    public static void recordClassLoad(String className, String classLoader, int size, String sourceLocation) {
        ClassInfo info = new ClassInfo(className, classLoader, size, sourceLocation);
        loadedClasses.put(className, info);
        totalLoadedCount++;

        classLoaderStats.computeIfAbsent(classLoader, k -> new ClassLoaderStats(classLoader))
                       .recordClass(size);

        packageStats.merge(info.packageName, 1L, Long::sum);

        if (info.isObfuscated) {
            synchronized (suspiciousClasses) {
                if (!suspiciousClasses.contains(className)) {
                    suspiciousClasses.add(className);
                }
            }
            obfuscationDetected = true;
        }
    }

    public static Collection<ClassInfo> getLoadedClasses() {
        return new ArrayList<>(loadedClasses.values());
    }

    public static int getLoadedClassCount() {
        return loadedClasses.size();
    }

    public static Collection<ClassLoaderStats> getClassLoaderStats() {
        return new ArrayList<>(classLoaderStats.values());
    }

    public static Map<String, Long> getPackageStats() {
        return new HashMap<>(packageStats);
    }

    public static List<String> getSuspiciousClasses() {
        synchronized (suspiciousClasses) {
            return new ArrayList<>(suspiciousClasses);
        }
    }

    public static boolean isObfuscationDetected() {
        return obfuscationDetected;
    }

    public static void printClassLoaderInfo() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Class Loader Information                                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        List<ClassLoaderStats> stats = new ArrayList<>(classLoaderStats.values());
        stats.sort((a, b) -> Long.compare(b.loadedCount, a.loadedCount));

        System.out.printf("%-60s %10s %12s %15s%n", "ClassLoader", "Classes", "Total (KB)", "Last Activity");
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
        System.out.printf("%-60s %10d %12.2f%n", "Total", totalLoadedCount,
                stats.stream().mapToLong(s -> s.totalSize).sum() / 1024.0);
    }

    public static void printPackageStats() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Package Statistics                                      ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        Map<String, Long> sortedStats = new HashMap<>(packageStats);
        List<Map.Entry<String, Long>> list = new ArrayList<>(sortedStats.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        System.out.printf("%-50s %10s %10s%n", "Package", "Classes", "Percentage");
        System.out.println("─────────────────────────────────────────────────────────────────");

        for (Map.Entry<String, Long> entry : list) {
            String packageName = entry.getKey();
            if (packageName.length() > 48) {
                packageName = "..." + packageName.substring(packageName.length() - 45);
            }
            double percentage = (double) entry.getValue() / totalLoadedCount * 100;
            System.out.printf("%-50s %10d %9.2f%%%n", packageName, entry.getValue(), percentage);
        }

        int count = 0;
        for (Map.Entry<String, Long> entry : list) {
            if (++count >= 20) break;
        }
        if (list.size() > 20) {
            System.out.println("... and " + (list.size() - 20) + " more packages");
        }
    }

    public static void printSuspiciousClasses() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Suspicious/Obfuscated Classes Detected                  ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        List<String> suspicious = getSuspiciousClasses();

        if (suspicious.isEmpty()) {
            System.out.println("  No suspicious classes found");
            return;
        }

        System.out.println("  Detected " + suspicious.size() + " suspicious classes:");
        System.out.println();

        Map<String, List<String>> categorized = new HashMap<>();
        for (String className : suspicious) {
            String pkg = className.substring(0, Math.min(className.lastIndexOf('.'), className.length()));
            categorized.computeIfAbsent(pkg, k -> new ArrayList<>()).add(className);
        }

        int displayed = 0;
        for (Map.Entry<String, List<String>> entry : categorized.entrySet()) {
            if (displayed >= 50) {
                System.out.println("  ... and " + (suspicious.size() - displayed) + " more");
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

    public static void printSummary() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Class Loading Summary                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("  Running Time: " + String.format("%.2f", (System.currentTimeMillis() - startTime) / 1000.0) + " sec");
        System.out.println("  Total Loaded Classes: " + totalLoadedCount);
        System.out.println("  Active Classes: " + loadedClasses.size());
        System.out.println("  ClassLoader Count: " + classLoaderStats.size());
        System.out.println("  Package Count: " + packageStats.size());
        System.out.println("  Suspicious Classes: " + getSuspiciousClasses().size());

        if (obfuscationDetected) {
            System.out.println("  ★★★ Obfuscation Detected ★★★");
        }
    }

    public static List<ClassInfo> searchClasses(String keyword) {
        List<ClassInfo> results = new ArrayList<>();
        for (ClassInfo info : loadedClasses.values()) {
            if (info.className.toLowerCase().contains(keyword.toLowerCase())) {
                results.add(info);
            }
        }
        return results;
    }

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

    public static List<ClassInfo> getLargestClasses(int limit) {
        List<ClassInfo> all = new ArrayList<>(loadedClasses.values());
        all.sort((a, b) -> Integer.compare(b.size, a.size));
        return all.subList(0, Math.min(limit, all.size()));
    }

    public static List<ClassInfo> getLatestLoadedClasses(int limit) {
        List<ClassInfo> all = new ArrayList<>(loadedClasses.values());
        all.sort((a, b) -> Long.compare(b.loadTime, a.loadTime));
        return all.subList(0, Math.min(limit, all.size()));
    }

    public static long getStartTime() {
        return startTime;
    }
}
