package com.hachimi.dump;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Class Viewer GUI - Swing based interface for monitoring class loading
 */
public class ClassViewerGUI extends JFrame {

    private JTable classTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> sorter;
    
    private JLabel totalClassesLabel;
    private JLabel activeClassesLabel;
    private JLabel classLoaderCountLabel;
    private JLabel packageCountLabel;
    private JLabel suspiciousClassesLabel;
    private JLabel runningTimeLabel;
    
    private JTextField searchField;
    private JComboBox<String> packageFilterCombo;
    private JCheckBox obfuscationFilterCheck;
    
    private JTextArea logArea;
    private JScrollPane logScrollPane;
    
    private ScheduledExecutorService refreshExecutor;
    private boolean autoRefresh = true;
    
    private final Object refreshLock = new Object();
    private long lastRefreshTime = 0;
    
    public ClassViewerGUI() {
        setTitle("Class Viewer - Java Agent Monitor");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        initComponents();
        setupMenu();
        startAutoRefresh();
        
        logMessage("Class Viewer GUI Started");
        logMessage("Monitoring class loading in real-time...");
    }
    
    private void initComponents() {
        setLayout(new BorderLayout());
        
        // Create main split pane
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplitPane.setResizeWeight(0.7);
        
        // Upper panel - Class table and filters
        JPanel upperPanel = new JPanel(new BorderLayout());
        
        // Filter panel
        JPanel filterPanel = createFilterPanel();
        upperPanel.add(filterPanel, BorderLayout.NORTH);
        
        // Class table
        classTable = createClassTable();
        JScrollPane tableScroll = new JScrollPane(classTable);
        upperPanel.add(tableScroll, BorderLayout.CENTER);
        
        mainSplitPane.setTopComponent(upperPanel);
        
        // Lower panel - Statistics and logs
        JPanel lowerPanel = new JPanel(new BorderLayout());
        lowerPanel.setPreferredSize(new Dimension(1200, 250));
        
        JSplitPane lowerSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        lowerSplitPane.setResizeWeight(0.4);
        
        // Statistics panel
        JPanel statsPanel = createStatisticsPanel();
        lowerSplitPane.setLeftComponent(statsPanel);
        
        // Log panel
        JPanel logPanel = createLogPanel();
        lowerSplitPane.setRightComponent(logPanel);
        
        lowerPanel.add(lowerSplitPane, BorderLayout.CENTER);
        mainSplitPane.setBottomComponent(lowerPanel);
        
        add(mainSplitPane, BorderLayout.CENTER);
        
        // Status bar
        JPanel statusBar = createStatusBar();
        add(statusBar, BorderLayout.SOUTH);
    }
    
    private JPanel createFilterPanel() {
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        filterPanel.setBorder(BorderFactory.createTitledBorder("Filters"));
        
        // Search field
        filterPanel.add(new JLabel("Search:"));
        searchField = new JTextField(20);
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                applyFilters();
            }
        });
        filterPanel.add(searchField);
        
        // Package filter
        filterPanel.add(new JLabel("Package:"));
        packageFilterCombo = new JComboBox<>();
        packageFilterCombo.setPreferredSize(new Dimension(200, 25));
        packageFilterCombo.addActionListener(e -> applyFilters());
        filterPanel.add(packageFilterCombo);
        
        // Obfuscation filter
        obfuscationFilterCheck = new JCheckBox("Show Obfuscated Only");
        obfuscationFilterCheck.addActionListener(e -> applyFilters());
        filterPanel.add(obfuscationFilterCheck);
        
        // Refresh button
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refreshData());
        filterPanel.add(refreshBtn);
        
        // Auto refresh checkbox
        JCheckBox autoRefreshCheck = new JCheckBox("Auto Refresh");
        autoRefreshCheck.setSelected(true);
        autoRefreshCheck.addActionListener(e -> {
            autoRefresh = autoRefreshCheck.isSelected();
            if (autoRefresh) {
                startAutoRefresh();
            }
        });
        filterPanel.add(autoRefreshCheck);
        
        // Export button
        JButton exportBtn = new JButton("Export");
        exportBtn.addActionListener(e -> exportData());
        filterPanel.add(exportBtn);
        
        return filterPanel;
    }
    
    private JTable createClassTable() {
        String[] columnNames = {
            "Class Name", "Package", "ClassLoader", "Size (KB)", 
            "Load Time", "Obfuscated", "Source"
        };
        
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        JTable table = new JTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.getColumnModel().getColumn(4).setPreferredWidth(100);
        table.getColumnModel().getColumn(5).setPreferredWidth(80);
        table.getColumnModel().getColumn(6).setPreferredWidth(150);
        
        table.setRowHeight(25);
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        
        // Add row sorter for filtering
        sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        
        // Double click to view details
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.convertRowIndexToModel(table.rowAtPoint(e.getPoint()));
                    if (row >= 0 && row < tableModel.getRowCount()) {
                        String className = (String) tableModel.getValueAt(row, 0);
                        showClassDetails(className);
                    }
                }
            }
        });
        
        return table;
    }
    
    private JPanel createStatisticsPanel() {
        JPanel statsPanel = new JPanel(new GridBagLayout());
        statsPanel.setBorder(BorderFactory.createTitledBorder("Statistics"));
        statsPanel.setPreferredSize(new Dimension(350, 200));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        
        totalClassesLabel = createStatLabel("Total Classes: 0");
        activeClassesLabel = createStatLabel("Active Classes: 0");
        classLoaderCountLabel = createStatLabel("ClassLoader Count: 0");
        packageCountLabel = createStatLabel("Package Count: 0");
        suspiciousClassesLabel = createStatLabel("Suspicious Classes: 0");
        runningTimeLabel = createStatLabel("Running Time: 0.00s");
        
        gbc.gridy = 0; statsPanel.add(totalClassesLabel, gbc);
        gbc.gridy = 1; statsPanel.add(activeClassesLabel, gbc);
        gbc.gridy = 2; statsPanel.add(classLoaderCountLabel, gbc);
        gbc.gridy = 3; statsPanel.add(packageCountLabel, gbc);
        gbc.gridy = 4; statsPanel.add(suspiciousClassesLabel, gbc);
        gbc.gridy = 5; statsPanel.add(runningTimeLabel, gbc);
        
        // Separator
        JSeparator separator = new JSeparator();
        gbc.gridy = 6;
        gbc.insets = new Insets(10, 10, 10, 10);
        statsPanel.add(separator, gbc);
        
        // Top packages label
        JLabel topPackagesLabel = new JLabel("Top Packages:");
        topPackagesLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        gbc.gridy = 7;
        statsPanel.add(topPackagesLabel, gbc);
        
        // Top packages list
        DefaultListModel<String> packageListModel = new DefaultListModel<>();
        JList<String> topPackagesList = new JList<>(packageListModel);
        topPackagesList.setVisibleRowCount(5);
        JScrollPane packageListScroll = new JScrollPane(topPackagesList);
        gbc.gridy = 8;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        statsPanel.add(packageListScroll, gbc);
        
        // Update package list periodically
        refreshExecutor.scheduleAtFixedRate(() -> {
            Map<String, Long> packageStats = ClassViewer.getPackageStats();
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(packageStats.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            
            SwingUtilities.invokeLater(() -> {
                packageListModel.clear();
                int count = 0;
                for (Map.Entry<String, Long> entry : sorted) {
                    if (count >= 10) break;
                    String pkg = entry.getKey();
                    if (pkg.length() > 35) {
                        pkg = "..." + pkg.substring(pkg.length() - 32);
                    }
                    packageListModel.addElement(String.format("%-35s %6d", pkg, entry.getValue()));
                    count++;
                }
            });
        }, 2, 2, TimeUnit.SECONDS);
        
        return statsPanel;
    }
    
    private JLabel createStatLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Monospaced", Font.PLAIN, 12));
        return label;
    }
    
    private JPanel createLogPanel() {
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Event Log"));
        
        logArea = new JTextArea(10, 40);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(245, 245, 245));
        
        logScrollPane = new JScrollPane(logArea);
        logScrollPane.setVerticalScrollBarPolicy(
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        // Clear log button
        JButton clearBtn = new JButton("Clear Log");
        clearBtn.addActionListener(e -> logArea.setText(""));
        logPanel.add(clearBtn, BorderLayout.SOUTH);
        
        return logPanel;
    }
    
    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        statusBar.setBorder(BorderFactory.createEtchedBorder());
        
        JLabel statusLabel = new JLabel("Status: Active");
        statusLabel.setForeground(new Color(0, 128, 0));
        statusBar.add(statusLabel);
        
        JLabel memoryLabel = new JLabel();
        memoryLabel.setForeground(Color.BLUE);
        statusBar.add(memoryLabel);
        
        // Update memory usage
        refreshExecutor.scheduleAtFixedRate(() -> {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long max = runtime.maxMemory();
            SwingUtilities.invokeLater(() -> {
                memoryLabel.setText(String.format(" | Memory: %.1f MB / %.1f MB", 
                    used / 1024.0 / 1024.0, max / 1024.0 / 1024.0));
            });
        }, 5, 5, TimeUnit.SECONDS);
        
        return statusBar;
    }
    
    private void setupMenu() {
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        
        JMenuItem exportItem = new JMenuItem("Export Classes...");
        exportItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK));
        exportItem.addActionListener(e -> exportData());
        fileMenu.add(exportItem);
        
        fileMenu.addSeparator();
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        
        menuBar.add(fileMenu);
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);
        
        JMenuItem refreshItem = new JMenuItem("Refresh");
        refreshItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        refreshItem.addActionListener(e -> refreshData());
        viewMenu.add(refreshItem);
        
        viewMenu.addSeparator();
        
        JMenuItem showAllItem = new JMenuItem("Show All Classes");
        showAllItem.addActionListener(e -> {
            searchField.setText("");
            packageFilterCombo.setSelectedIndex(0);
            obfuscationFilterCheck.setSelected(false);
            applyFilters();
        });
        viewMenu.add(showAllItem);
        
        JMenuItem showSuspiciousItem = new JMenuItem("Show Suspicious Only");
        showSuspiciousItem.addActionListener(e -> {
            obfuscationFilterCheck.setSelected(true);
            applyFilters();
        });
        viewMenu.add(showSuspiciousItem);
        
        menuBar.add(viewMenu);
        
        // Tools menu
        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.setMnemonic(KeyEvent.VK_T);
        
        JMenuItem findLargeItem = new JMenuItem("Find Largest Classes");
        findLargeItem.addActionListener(e -> showLargestClasses());
        toolsMenu.add(findLargeItem);
        
        JMenuItem findRecentItem = new JMenuItem("Find Recent Classes");
        findRecentItem.addActionListener(e -> showRecentClasses());
        toolsMenu.add(findRecentItem);
        
        toolsMenu.addSeparator();
        
        JMenuItem detectObfItem = new JMenuItem("Detect Obfuscation");
        detectObfItem.addActionListener(e -> detectObfuscation());
        toolsMenu.add(detectObfItem);
        
        menuBar.add(toolsMenu);
        
        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);
        
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);
        
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
    }
    
    private void applyFilters() {
        String searchText = searchField.getText().trim().toLowerCase();
        String selectedPackage = (String) packageFilterCombo.getSelectedItem();
        boolean obfuscatedOnly = obfuscationFilterCheck.isSelected();
        
        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        
        // Search filter
        if (!searchText.isEmpty()) {
            filters.add(new RowFilter<Object, Object>() {
                @Override
                public boolean include(Entry<? extends Object, ? extends Object> entry) {
                    String className = (String) entry.getValue(0);
                    String packageName = (String) entry.getValue(1);
                    String classLoader = (String) entry.getValue(2);
                    String source = (String) entry.getValue(6);
                    
                    return className.toLowerCase().contains(searchText) ||
                           packageName.toLowerCase().contains(searchText) ||
                           classLoader.toLowerCase().contains(searchText) ||
                           (source != null && source.toLowerCase().contains(searchText));
                }
            });
        }
        
        // Package filter
        if (selectedPackage != null && !selectedPackage.equals("All Packages")) {
            filters.add(new RowFilter<Object, Object>() {
                @Override
                public boolean include(Entry<? extends Object, ? extends Object> entry) {
                    String packageName = (String) entry.getValue(1);
                    return selectedPackage.equals(packageName);
                }
            });
        }
        
        // Obfuscation filter
        if (obfuscatedOnly) {
            filters.add(new RowFilter<Object, Object>() {
                @Override
                public boolean include(Entry<? extends Object, ? extends Object> entry) {
                    String obfuscated = (String) entry.getValue(5);
                    return "Yes".equals(obfuscated);
                }
            });
        }
        
        if (filters.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.andFilter(filters));
        }
    }
    
    private void refreshData() {
        synchronized (refreshLock) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastRefreshTime < 500) {
                return; // Debounce
            }
            lastRefreshTime = currentTime;
            
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                
                Collection<ClassViewer.ClassInfo> classes = ClassViewer.getLoadedClasses();
                
                // Update package filter combo
                Set<String> packages = new TreeSet<>();
                packages.add("All Packages");
                for (ClassViewer.ClassInfo info : classes) {
                    packages.add(info.packageName);
                }
                
                String currentSelection = (String) packageFilterCombo.getSelectedItem();
                packageFilterCombo.removeAllItems();
                for (String pkg : packages) {
                    packageFilterCombo.addItem(pkg);
                }
                if (currentSelection != null && packages.contains(currentSelection)) {
                    packageFilterCombo.setSelectedItem(currentSelection);
                }
                
                // Add classes to table
                for (ClassViewer.ClassInfo info : classes) {
                    Object[] row = {
                        info.className,
                        info.packageName,
                        info.classLoader,
                        String.format("%.2f", info.size / 1024.0),
                        String.format("%.2fs", info.loadTime / 1000.0),
                        info.isObfuscated ? "Yes" : "No",
                        info.sourceLocation != null ? info.sourceLocation : "N/A"
                    };
                    tableModel.addRow(row);
                }
                
                // Update statistics
                updateStatistics();
                
                // Apply filters
                applyFilters();
            });
        }
    }
    
    private void updateStatistics() {
        int totalLoaded = ClassViewer.getLoadedClassCount();
        Collection<ClassViewer.ClassLoaderStats> loaderStats = ClassViewer.getClassLoaderStats();
        Map<String, Long> packageStats = ClassViewer.getPackageStats();
        List<String> suspicious = ClassViewer.getSuspiciousClasses();
        
        long runningTime = System.currentTimeMillis() - ClassViewer.getStartTime();
        
        SwingUtilities.invokeLater(() -> {
            totalClassesLabel.setText("Total Classes: " + totalLoaded);
            activeClassesLabel.setText("Active Classes: " + totalLoaded);
            classLoaderCountLabel.setText("ClassLoader Count: " + loaderStats.size());
            packageCountLabel.setText("Package Count: " + packageStats.size());
            suspiciousClassesLabel.setText("Suspicious Classes: " + suspicious.size());
            
            if (!suspicious.isEmpty()) {
                suspiciousClassesLabel.setForeground(Color.RED);
            } else {
                suspiciousClassesLabel.setForeground(Color.BLACK);
            }
            
            runningTimeLabel.setText(String.format("Running Time: %.2fs", runningTime / 1000.0));
        });
    }
    
    private void startAutoRefresh() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
        
        refreshExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "GUI-Refresh-Thread");
            t.setDaemon(true);
            return t;
        });
        
        // Refresh data every 2 seconds
        refreshExecutor.scheduleAtFixedRate(() -> {
            if (autoRefresh) {
                refreshData();
            }
        }, 1, 2, TimeUnit.SECONDS);
    }
    
    private void exportData() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Classes");
        fileChooser.setSelectedFile(new File("exported_classes.csv"));
        
        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            try {
                java.io.FileWriter writer = new java.io.FileWriter(fileChooser.getSelectedFile());
                writer.write("Class Name,Package,ClassLoader,Size (KB),Load Time,Obfuscated,Source\n");
                
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    for (int j = 0; j < tableModel.getColumnCount(); j++) {
                        Object value = tableModel.getValueAt(i, j);
                        writer.write(value != null ? value.toString().replace(",", ";") : "N/A");
                        if (j < tableModel.getColumnCount() - 1) {
                            writer.write(",");
                        }
                    }
                    writer.write("\n");
                }
                writer.close();
                
                JOptionPane.showMessageDialog(this, 
                    "Exported " + tableModel.getRowCount() + " classes to " + 
                    fileChooser.getSelectedFile().getName());
                logMessage("Exported " + tableModel.getRowCount() + " classes");
                
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, 
                    "Export failed: " + ex.getMessage(), 
                    "Error", 
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void showClassDetails(String className) {
        Collection<ClassViewer.ClassInfo> classes = ClassViewer.getLoadedClasses();
        ClassViewer.ClassInfo selectedInfo = null;
        
        for (ClassViewer.ClassInfo info : classes) {
            if (info.className.equals(className)) {
                selectedInfo = info;
                break;
            }
        }
        
        if (selectedInfo == null) {
            return;
        }
        
        StringBuilder details = new StringBuilder();
        details.append("Class Details\n\n");
        details.append("Class Name: ").append(selectedInfo.className).append("\n");
        details.append("Package: ").append(selectedInfo.packageName).append("\n");
        details.append("ClassLoader: ").append(selectedInfo.classLoader).append("\n");
        details.append("Size: ").append(String.format("%.2f KB", selectedInfo.size / 1024.0)).append("\n");
        details.append("Load Time: ").append(String.format("%.2fs", selectedInfo.loadTime / 1000.0)).append("\n");
        details.append("Obfuscated: ").append(selectedInfo.isObfuscated ? "Yes" : "No").append("\n");
        details.append("Source: ").append(selectedInfo.sourceLocation != null ? 
            selectedInfo.sourceLocation : "N/A").append("\n");
        
        JOptionPane.showMessageDialog(this, 
            details.toString(), 
            "Class Details", 
            JOptionPane.INFORMATION_MESSAGE);
        
        logMessage("Viewed details for: " + className);
    }
    
    private void showLargestClasses() {
        List<ClassViewer.ClassInfo> largest = ClassViewer.getLargestClasses(20);
        
        StringBuilder sb = new StringBuilder();
        sb.append("Top 20 Largest Classes\n\n");
        sb.append(String.format("%-50s %10s %15s%n", "Class Name", "Size (KB)", "Load Time"));
        sb.append("─────────────────────────────────────────────────────────────────\n");
        
        for (ClassViewer.ClassInfo info : largest) {
            String name = info.className;
            if (name.length() > 48) {
                name = "..." + name.substring(name.length() - 45);
            }
            sb.append(String.format("%-50s %10.2f %15.2fs%n",
                name, info.size / 1024.0, info.loadTime / 1000.0));
        }
        
        JTextArea textArea = new JTextArea(sb.toString());
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        
        JOptionPane.showMessageDialog(this, 
            scrollPane, 
            "Largest Classes", 
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void showRecentClasses() {
        List<ClassViewer.ClassInfo> recent = ClassViewer.getLatestLoadedClasses(20);
        
        StringBuilder sb = new StringBuilder();
        sb.append("Latest 20 Loaded Classes\n\n");
        
        for (ClassViewer.ClassInfo info : recent) {
            sb.append(String.format("[%s] %s (%.2f KB)%n",
                new SimpleDateFormat("HH:mm:ss").format(new Date(info.loadTime)),
                info.className,
                info.size / 1024.0));
        }
        
        JTextArea textArea = new JTextArea(sb.toString());
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        
        JOptionPane.showMessageDialog(this, 
            scrollPane, 
            "Recent Classes", 
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void detectObfuscation() {
        if (ClassViewer.isObfuscationDetected()) {
            List<String> suspicious = ClassViewer.getSuspiciousClasses();
            
            StringBuilder sb = new StringBuilder();
            sb.append("Suspicious/Obfuscated Classes Detected\n\n");
            sb.append("Total: ").append(suspicious.size()).append(" classes\n\n");
            
            Map<String, List<String>> categorized = new HashMap<>();
            for (String className : suspicious) {
                int lastDot = className.lastIndexOf('.');
                String pkg = lastDot > 0 ? className.substring(0, lastDot) : "(default)";
                categorized.computeIfAbsent(pkg, k -> new ArrayList<>()).add(className);
            }
            
            int displayed = 0;
            for (Map.Entry<String, List<String>> entry : categorized.entrySet()) {
                if (displayed >= 50) {
                    sb.append("... and ").append(suspicious.size() - displayed).append(" more\n");
                    break;
                }
                
                sb.append("[").append(entry.getKey()).append("]\n");
                for (String cls : entry.getValue()) {
                    if (displayed >= 50) break;
                    sb.append("  - ").append(cls).append("\n");
                    displayed++;
                }
                sb.append("\n");
            }
            
            JTextArea textArea = new JTextArea(sb.toString());
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
            textArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(700, 500));
            
            JOptionPane.showMessageDialog(this, 
                scrollPane, 
                "Obfuscation Detection Results", 
                JOptionPane.WARNING_MESSAGE);
            
            logMessage("Detected " + suspicious.size() + " suspicious classes");
            
        } else {
            JOptionPane.showMessageDialog(this, 
                "No obfuscation detected!", 
                "Obfuscation Detection", 
                JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void showAbout() {
        String aboutText = "Class Viewer GUI\n\n" +
                          "Java Agent Class Loading Monitor\n\n" +
                          "Features:\n" +
                          "• Real-time class loading monitoring\n" +
                          "• Obfuscation detection\n" +
                          "• Package statistics\n" +
                          "• Class export functionality\n\n" +
                          "Version: 1.0\n" +
                          "Author: Hachimi";
        
        JOptionPane.showMessageDialog(this, 
            aboutText, 
            "About Class Viewer", 
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void logMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    public void dispose() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
        super.dispose();
    }
    
    public static void main(String[] args) {
        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Ignore
        }
        
        SwingUtilities.invokeLater(() -> {
            ClassViewerGUI gui = new ClassViewerGUI();
            gui.setVisible(true);
        });
    }
}
