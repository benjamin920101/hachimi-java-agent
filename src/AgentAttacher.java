package com.hachimi.dump;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.util.List;

/**
 * Java Agent Runtime Attacher Tool
 * 
 * Usage:
 * java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> [options]
 * 
 * Options:
 *   taskmgr      - Enable Task Manager (view loaded classes)
 *   obfuscated   - Enable Obfuscated File Detection (DLL/SO)
 *   all          - Enable all features
 */
public class AgentAttacher {

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   Java Agent Runtime Attacher Tool                        ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        if (args.length < 1) {
            listJavaProcesses();
            System.out.println();
            System.out.println("Options:");
            System.out.println("  taskmgr      - Enable Task Manager (view loaded classes)");
            System.out.println("  obfuscated   - Enable Obfuscated File Detection (DLL/SO)");
            System.out.println("  all          - Enable all features");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> taskmgr");
            System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> obfuscated");
            System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid> all");
            return;
        }

        String pid = args[0];
        String agentPath = new File("ClassDumpAgent.jar").getAbsolutePath();

        StringBuilder agentArgs = new StringBuilder();
        if (args.length >= 2) {
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
                    agentPath = args[i];
                }
            }
        }

        if (agentArgs.length() == 0) {
            agentArgs.append("all");
        }

        System.out.println("[INFO] Target PID: " + pid);
        System.out.println("[INFO] Agent Path: " + agentPath);
        System.out.println("[INFO] Agent Args: " + agentArgs);
        System.out.println();

        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            System.out.println("[INFO] Connected to JVM");

            vm.loadAgent(agentPath, agentArgs.toString());
            System.out.println("[INFO] Agent Loaded - Starting class interception");

            vm.detach();
            System.out.println("[INFO] Detached from JVM");

            System.out.println();
            System.out.println("★★★ Agent Successfully Attached ★★★");
            System.out.println("Intercepted classes will be saved to dumped_classes/");
            System.out.println();
            System.out.println("Enabled Features:");
            System.out.println("  " + agentArgs);
            System.out.println();
            System.out.println("Check target JVM output for details");

        } catch (Exception e) {
            System.err.println("[ERROR] Attachment failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void listJavaProcesses() {
        System.out.println("Available Java Processes:");
        System.out.println();

        List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();

        if (descriptors.isEmpty()) {
            System.out.println("  (No running Java processes)");
            return;
        }

        System.out.printf("%-10s %s%n", "PID", "Description");
        System.out.println("─────────────────────────────────────────");

        for (VirtualMachineDescriptor vmd : descriptors) {
            String displayName = vmd.displayName();
            if (displayName.contains("/")) {
                String[] parts = displayName.split("/");
                displayName = parts[parts.length - 1];
            }
            System.out.printf("%-10s %s%n", vmd.id(), displayName);
        }

        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -cp ClassDumpAgent.jar com.hachimi.dump.AgentAttacher <pid>");
    }
}
