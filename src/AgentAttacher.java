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
 * java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid>
 */
public class AgentAttacher {
    
    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Java Agent 運行時附加工具                                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        
        if (args.length < 1) {
            // 無參數時列出所有 Java 進程
            listJavaProcesses();
            return;
        }
        
        String pid = args[0];
        String agentPath = new File("ClassDumpAgent.jar").getAbsolutePath();
        
        if (args.length >= 2) {
            agentPath = args[1];
        }
        
        System.out.println("[INFO] 目標 PID: " + pid);
        System.out.println("[INFO] Agent 路徑：" + agentPath);
        System.out.println();
        
        try {
            // 附加到目標 JVM
            VirtualMachine vm = VirtualMachine.attach(pid);
            System.out.println("[INFO] 已連接到 JVM");
            
            // 加載 Agent
            vm.loadAgent(agentPath);
            System.out.println("[INFO] Agent 已加載 - 開始攔截類加載");
            
            // 分離
            vm.detach();
            System.out.println("[INFO] 已與 JVM 分離");
            
            System.out.println();
            System.out.println("★★★ Agent 已成功附加 ★★★");
            System.out.println("被攔截的類將保存到 dumped_classes/ 目錄");
            
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
