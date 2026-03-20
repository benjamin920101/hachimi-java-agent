package com.hachimi.dump;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.util.List;

/**
 * Java Agent 運行時附加工具
 *
 * 用於在 JVM 運行時動態附加 Agent，無需重啟 JVM
 *
 * 使用方法:
 * java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> [options]
 * 
 * 選項:
 *   taskmgr      - 啟用任務管理器（查看已加載的類）
 *   obfuscated   - 啟用混淆文件檢測（DLL/SO）
 *   all          - 啟用所有功能
 */
public class AgentAttacher {

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Java Agent 運行時附加工具（增強版）                      ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        if (args.length < 1) {
            // 無參數時列出所有 Java 進程
            listJavaProcesses();
            System.out.println();
            System.out.println("功能選項:");
            System.out.println("  taskmgr      - 啟用任務管理器（查看已加載的類）");
            System.out.println("  obfuscated   - 啟用混淆文件檢測（DLL/SO）");
            System.out.println("  all          - 啟用所有功能");
            System.out.println();
            System.out.println("使用示例:");
            System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> taskmgr");
            System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> obfuscated");
            System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> all");
            return;
        }

        String pid = args[0];
        String agentPath = new File("ClassDumpAgent.jar").getAbsolutePath();
        
        // 構建 agent 參數
        StringBuilder agentArgs = new StringBuilder();
        if (args.length >= 2) {
            // 檢查是否為選項
            for (int i = 1; i < args.length; i++) {
                String arg = args[i].trim().toLowerCase();
                if ("taskmgr".equals(arg) || "taskmanager".equals(arg) || "tm".equals(arg)) {
                    if (agentArgs.length() > 0) agentArgs.append(",");
                    agentArgs.append("taskmgr");
                } else if ("obfuscated".equals(arg) || "obf".equals(arg) || "obfscan".equals(arg)) {
                    if (agentArgs.length() > 0) agentArgs.append(",");
                    agentArgs.append("obfuscated");
                } else if ("all".equals(arg) || "full".equals(arg)) {
                    if (agentArgs.length() > 0) agentArgs.append(",");
                    agentArgs.append("all");
                } else {
                    // 假設是 agent 路徑
                    agentPath = args[i];
                }
            }
        }

        if (agentArgs.length() == 0) {
            agentArgs.append("all");  // 預設啟用所有功能
        }

        System.out.println("[INFO] 目標 PID: " + pid);
        System.out.println("[INFO] Agent 路徑：" + agentPath);
        System.out.println("[INFO] Agent 參數：" + agentArgs);
        System.out.println();
        
        try {
            // 附加到目標 JVM
            VirtualMachine vm = VirtualMachine.attach(pid);
            System.out.println("[INFO] 已連接到 JVM");

            // 加載 Agent（帶參數）
            vm.loadAgent(agentPath, agentArgs.toString());
            System.out.println("[INFO] Agent 已加載 - 開始攔截類加載");

            // 分離
            vm.detach();
            System.out.println("[INFO] 已與 JVM 分離");

            System.out.println();
            System.out.println("★★★ Agent 已成功附加 ★★★");
            System.out.println("被攔截的類將保存到 dumped_classes/ 目錄");
            System.out.println();
            System.out.println("已啟用的功能:");
            System.out.println("  " + agentArgs);
            System.out.println();
            System.out.println("查看目標 JVM 的輸出以獲取詳細信息");

        } catch (Exception e) {
            System.err.println("[ERROR] 附加失敗：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * 列出所有 Java 進程
     */
    private static void listJavaProcesses() {
        System.out.println("可用的 Java 進程:");
        System.out.println();
        
        List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();
        
        if (descriptors.isEmpty()) {
            System.out.println("  (無運行中的 Java 進程)");
            return;
        }
        
        System.out.printf("%-10s %s%n", "PID", "描述");
        System.out.println("─────────────────────────────────────────");
        
        for (VirtualMachineDescriptor vmd : descriptors) {
            String displayName = vmd.displayName();
            // 簡化顯示名稱
            if (displayName.contains("/")) {
                String[] parts = displayName.split("/");
                displayName = parts[parts.length - 1];
            }
            System.out.printf("%-10s %s%n", vmd.id(), displayName);
        }
        
        System.out.println();
        System.out.println("使用示例:");
        System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid>");
    }
}
