package com.connectionmap

import com.connectionmap.builder.GraphBuilder
import com.connectionmap.model.ConnectionGraph
import com.connectionmap.model.GraphNode
import com.connectionmap.ui.DisplayMode
import com.connectionmap.ui.GraphPanel
import com.connectionmap.ui.NodeFilter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class ConnectionMapToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(ConnectionMapToolWindowContent(project).getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class ConnectionMapToolWindowContent(private val project: Project) {
    private val connectedPanel = GraphPanel(project).apply { setDisplayMode(DisplayMode.CONNECTED) }
    private val allFilesPanel = GraphPanel(project).apply { setDisplayMode(DisplayMode.ALL) }
    private val impactPanel = com.connectionmap.ui.ImpactPanel(project) // [NEW] Impact Panel
    private val isolatedTableModel = IsolatedFilesTableModel()
    private val isolatedTable = createTable(isolatedTableModel, false)
    private val allFilesTableModel = AllFilesTableModel()
    private val allFilesTable: JBTable
    
    private var allFilesShowMap = true
    private val allFilesCardLayout = CardLayout()
    private val allFilesCardPanel = JPanel(allFilesCardLayout)
    
    private val connectedStatus = JBLabel("Loading...")
    private val isolatedStatus = JBLabel("Loading...")
    private val allFilesStatus = JBLabel("Loading...")
    private val mainStatusLabel = JBLabel("Loading...")
    private var tabbedPane: JTabbedPane? = null
    
    private var fullGraph: ConnectionGraph? = null
    private var connectedPaths = mutableSetOf<String>()
    private var connectionCounts = mutableMapOf<String, Int>()
    
    // Details panel for showing connections
    private var detailsPanel: JPanel? = null
    private var detailsSplitPane: JSplitPane? = null
    private var tableScrollPane: JBScrollPane? = null
    private var selectedFilePath: String? = null
    
    private val mainPanel = JPanel(BorderLayout())
    
    init {
        allFilesTable = createAllFilesTable()
        setupUI()
        setupEditorListener()
        refreshGraph()
    }
    
    private fun createTable(model: AbstractTableModel, hasConnections: Boolean): JBTable {
        val table = JBTable(model)
        table.font = Font("Segoe UI", Font.PLAIN, 13); table.rowHeight = 36
        table.showHorizontalLines = true; table.showVerticalLines = false
        table.gridColor = JBColor(Color(230, 230, 230), Color(60, 60, 60))
        table.selectionBackground = JBColor(Color(59, 130, 246, 40), Color(59, 130, 246, 60))
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.tableHeader.font = Font("Segoe UI Semibold", Font.BOLD, 12)
        table.tableHeader.preferredSize = Dimension(0, 40)
        
        table.columnModel.getColumn(0).preferredWidth = 50
        table.columnModel.getColumn(1).preferredWidth = 60
        table.columnModel.getColumn(2).preferredWidth = 160
        table.columnModel.getColumn(3).preferredWidth = 200
        if (hasConnections) table.columnModel.getColumn(4).preferredWidth = 100
        
        // # column centered
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable?, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int) =
                super.getTableCellRendererComponent(t, v, s, f, r, c).also { horizontalAlignment = CENTER; foreground = JBColor(Color(148, 163, 184), Color(100, 116, 139)) }
        }
        // Type column colored
        table.columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable?, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int) =
                super.getTableCellRendererComponent(t, v, s, f, r, c).also { horizontalAlignment = CENTER; foreground = if (v == "Kotlin") Color(127, 82, 255) else Color(248, 81, 73); font = Font("Segoe UI Semibold", Font.BOLD, 11) }
        }
        // File name
        table.columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable?, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int) =
                super.getTableCellRendererComponent(t, v, s, f, r, c).also { border = JBUI.Borders.empty(0, 12); font = Font("Segoe UI Semibold", Font.PLAIN, 13) }
        }
        // Package
        table.columnModel.getColumn(3).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable?, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int) =
                super.getTableCellRendererComponent(t, v, s, f, r, c).also { border = JBUI.Borders.empty(0, 12); if (!s) foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184)); font = Font("Segoe UI", Font.PLAIN, 12) }
        }
        
        if (hasConnections) {
            table.columnModel.getColumn(4).cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: JTable?, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int) =
                    super.getTableCellRendererComponent(t, v, s, f, r, c).also {
                        horizontalAlignment = CENTER
                        val count = v?.toString()?.toIntOrNull() ?: 0
                        if (count > 0) { foreground = Color(34, 197, 94); font = Font("Segoe UI Semibold", Font.BOLD, 12); text = "✓ $count" }
                        else { foreground = JBColor(Color(148, 163, 184), Color(100, 116, 139)); text = "—" }
                    }
            }
        }
        
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    val path = if (hasConnections) allFilesTableModel.getFilePathAt(table.selectedRow) else isolatedTableModel.getFilePathAt(table.selectedRow)
                    openFile(path)
                }
            }
        })
        return table
    }
    
    private fun openFile(path: String) {
        LocalFileSystem.getInstance().findFileByPath(path)?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true) }
    }
    
    private fun setupUI() {
        mainPanel.border = JBUI.Borders.empty()
        mainPanel.add(createHeader(), BorderLayout.NORTH)
        
        tabbedPane = JTabbedPane(JTabbedPane.TOP).apply {
            font = Font("Segoe UI Semibold", Font.PLAIN, 13)
            addTab("🎯 Impact", null, impactPanel) // [NEW] Impact Tab (First)
            addTab("🔗 Connected", null, createGraphTab(connectedPanel, connectedStatus))
            addTab("📦 Isolated", null, createIsolatedTab())
            addTab("📂 All Files", null, createAllFilesTab())
        }
        mainPanel.add(tabbedPane, BorderLayout.CENTER)
    }
    
    private fun createHeader(): JPanel {
        val header = JPanel(BorderLayout())
        header.background = JBColor(Color(250, 250, 250), Color(43, 43, 43))
        header.border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(Color(220, 220, 220), Color(60, 60, 60))), JBUI.Borders.empty(8, 12))
        
        header.add(JBLabel("Connection Map").apply { font = Font("Segoe UI Semibold", Font.BOLD, 16) }, BorderLayout.WEST)
        
        val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        btns.isOpaque = false
        btns.add(createBtn("↻ Refresh") { refreshGraph() })
        btns.add(createBtn("⊡ Reset View") { connectedPanel.resetView(); allFilesPanel.resetView() })
        header.add(btns, BorderLayout.EAST)
        
        val status = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        status.isOpaque = false
        mainStatusLabel.font = Font("Segoe UI", Font.PLAIN, 12)
        mainStatusLabel.foreground = JBColor(Color(100, 100, 100), Color(160, 160, 160))
        status.add(mainStatusLabel)
        header.add(status, BorderLayout.SOUTH)
        
        return header
    }
    
    private fun createBtn(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = Font("Segoe UI", Font.PLAIN, 12)
            isFocusPainted = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor(Color(200, 200, 200), Color(80, 80, 80)), 1), JBUI.Borders.empty(4, 12))
            background = JBColor(Color(255, 255, 255), Color(60, 63, 65))
            addActionListener { action() }
        }
    }
    
    private fun createGraphTab(panel: GraphPanel, status: JBLabel): JPanel {
        val p = JPanel(BorderLayout())
        p.add(JBScrollPane(panel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        p.add(createBottomBar(status, true), BorderLayout.SOUTH)
        return p
    }
    
    private fun createIsolatedTab(): JPanel {
        val p = JPanel(BorderLayout())
        val info = JPanel(BorderLayout())
        info.background = JBColor(Color(254, 243, 199), Color(45, 40, 30))
        info.border = JBUI.Borders.empty(12, 16)
        info.add(JBLabel("📦 Files with no import connections.").apply { font = Font("Segoe UI", Font.PLAIN, 13); foreground = JBColor(Color(146, 64, 14), Color(251, 191, 36)) }, BorderLayout.WEST)
        info.add(JBLabel("Double-click to open").apply { font = Font("Segoe UI", Font.ITALIC, 12); foreground = JBColor(Color(180, 130, 60), Color(200, 170, 100)) }, BorderLayout.EAST)
        p.add(info, BorderLayout.NORTH)
        p.add(JBScrollPane(isolatedTable).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        isolatedStatus.font = Font("Segoe UI", Font.PLAIN, 12)
        p.add(createBottomBar(isolatedStatus, false), BorderLayout.SOUTH)
        return p
    }
    
    private fun createAllFilesTable(): JBTable {
        val table = createTable(allFilesTableModel, true)
        
        // Add click listener for Connections column
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                
                if (row >= 0 && col == 4) {  // Connections column
                    val path = allFilesTableModel.getFilePathAt(row)
                    val count = connectionCounts[path] ?: 0
                    if (count > 0) {
                        showConnectionDetails(path)
                    }
                } else if (e.clickCount == 2 && row >= 0) {
                    openFile(allFilesTableModel.getFilePathAt(row))
                }
            }
        })
        
        // Make connections column look clickable
        table.columnModel.getColumn(4).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable?, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int): Component {
                val comp = super.getTableCellRendererComponent(t, v, s, f, r, c)
                horizontalAlignment = CENTER
                val count = v?.toString()?.toIntOrNull() ?: 0
                if (count > 0) {
                    foreground = Color(34, 197, 94)
                    font = Font("Segoe UI Semibold", Font.BOLD, 12)
                    text = "✓ $count ▼"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Click to view connections"
                } else {
                    foreground = JBColor(Color(148, 163, 184), Color(100, 116, 139))
                    text = "—"
                    toolTipText = null
                }
                return comp
            }
        }
        
        return table
    }
    
    private fun showConnectionDetails(filePath: String) {
        val graph = fullGraph ?: return
        selectedFilePath = filePath
        
        // Get connections
        val imports = graph.getImports(filePath)
        val importedBy = graph.getImportedBy(filePath)
        val node = graph.getNode(filePath) ?: return
        val fileName = node.fileName.removeSuffix(".kt").removeSuffix(".java")
        
        // Create or update details panel
        detailsPanel?.let { detailsSplitPane?.remove(it) } // Remove old panel
        
        detailsPanel = JPanel(BorderLayout()).apply {
            background = JBColor(Color(248, 250, 252), Color(30, 35, 40))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(Color(200, 200, 200), Color(70, 70, 70))),
                JBUI.Borders.empty()
            )
            
            // Header with close button
            val header = JPanel(BorderLayout())
            header.background = JBColor(Color(241, 245, 249), Color(45, 50, 55))
            header.border = JBUI.Borders.empty(8, 12)
            
            val titleLabel = JBLabel("🔗 Connections for: $fileName").apply {
                font = Font("Segoe UI Semibold", Font.BOLD, 14)
            }
            header.add(titleLabel, BorderLayout.WEST)
            
            val closeBtn = JButton("✕ Close").apply {
                font = Font("Segoe UI", Font.PLAIN, 11)
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(180, 180, 180), Color(80, 80, 80)), 1),
                    JBUI.Borders.empty(3, 10)
                )
                background = JBColor(Color(255, 255, 255), Color(60, 63, 65))
                addActionListener { hideConnectionDetails() }
            }
            header.add(closeBtn, BorderLayout.EAST)
            add(header, BorderLayout.NORTH)
            
            // Content with two tables
            val content = JPanel(GridLayout(1, 2, 10, 0))
            content.background = JBColor(Color(248, 250, 252), Color(30, 35, 40))
            content.border = JBUI.Borders.empty(10)
            
            // Imports table (files this file imports)
            val importsPanel = createConnectionListPanel("⬇️ Imports (${imports.size})", imports, Color(59, 130, 246))
            content.add(importsPanel)
            
            // Imported By table (files that import this file)
            val importedByPanel = createConnectionListPanel("⬆️ Imported By (${importedBy.size})", importedBy, Color(34, 197, 94))
            content.add(importedByPanel)
            
            add(content, BorderLayout.CENTER)
            preferredSize = Dimension(preferredSize.width, 200)
        }
        
        // Update split pane
        detailsSplitPane?.let { split ->
            split.bottomComponent = detailsPanel
            split.dividerLocation = split.height - 220
            split.revalidate()
            split.repaint()
        }
    }
    
    private fun createConnectionListPanel(title: String, nodes: List<GraphNode>, accentColor: Color): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = JBColor(Color(255, 255, 255), Color(40, 45, 50))
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(220, 220, 220), Color(60, 60, 60)), 1),
            JBUI.Borders.empty()
        )
        
        val header = JBLabel(title).apply {
            font = Font("Segoe UI Semibold", Font.BOLD, 12)
            foreground = accentColor
            border = JBUI.Borders.empty(8, 12)
        }
        panel.add(header, BorderLayout.NORTH)
        
        if (nodes.isEmpty()) {
            val emptyLabel = JBLabel("No connections").apply {
                font = Font("Segoe UI", Font.ITALIC, 12)
                foreground = JBColor(Color(148, 163, 184), Color(100, 116, 139))
                horizontalAlignment = SwingConstants.CENTER
            }
            panel.add(emptyLabel, BorderLayout.CENTER)
        } else {
            val listModel = DefaultListModel<String>()
            nodes.sortedBy { it.fileName.lowercase() }.forEach { node ->
                val type = if (node.fileName.endsWith(".kt")) "K" else "J"
                listModel.addElement("[$type] ${node.fileName.removeSuffix(".kt").removeSuffix(".java")}  •  ${node.packageName.ifEmpty { "(default)" }}")
            }
            
            val list = JList(listModel)
            list.font = Font("Segoe UI", Font.PLAIN, 12)
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list.fixedCellHeight = 28
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && list.selectedIndex >= 0) {
                        openFile(nodes.sortedBy { it.fileName.lowercase() }[list.selectedIndex].filePath)
                    }
                }
            })
            
            val scroll = JBScrollPane(list)
            scroll.border = JBUI.Borders.empty()
            panel.add(scroll, BorderLayout.CENTER)
        }
        
        return panel
    }
    
    private fun hideConnectionDetails() {
        selectedFilePath = null
        detailsPanel?.let { panel ->
            detailsSplitPane?.let { split ->
                split.bottomComponent = null
                split.dividerLocation = split.height
                split.revalidate()
                split.repaint()
            }
        }
        detailsPanel = null
    }
    
    private fun createAllFilesTab(): JPanel {
        val p = JPanel(BorderLayout())
        
        val toggle = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6))
        toggle.background = JBColor(Color(241, 245, 249), Color(38, 42, 46))
        toggle.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(Color(220, 220, 220), Color(60, 60, 60)))
        toggle.add(JBLabel("View: ").apply { font = Font("Segoe UI", Font.PLAIN, 12) })
        
        val mapBtn = createToggleBtn("🗺️ Mindmap", true)
        val tableBtn = createToggleBtn("📋 Table", false)
        mapBtn.addActionListener {
            allFilesShowMap = true
            mapBtn.background = Color(59, 130, 246); mapBtn.foreground = Color.WHITE
            tableBtn.background = JBColor(Color(255, 255, 255), Color(60, 63, 65)); tableBtn.foreground = JBColor(Color(60, 60, 60), Color(200, 200, 200))
            allFilesCardLayout.show(allFilesCardPanel, "map")
            hideConnectionDetails()
        }
        tableBtn.addActionListener {
            allFilesShowMap = false
            tableBtn.background = Color(59, 130, 246); tableBtn.foreground = Color.WHITE
            mapBtn.background = JBColor(Color(255, 255, 255), Color(60, 63, 65)); mapBtn.foreground = JBColor(Color(60, 60, 60), Color(200, 200, 200))
            allFilesCardLayout.show(allFilesCardPanel, "table")
        }
        toggle.add(mapBtn); toggle.add(tableBtn)
        p.add(toggle, BorderLayout.NORTH)
        
        // Create split pane for table view with details
        tableScrollPane = JBScrollPane(allFilesTable).apply { border = JBUI.Borders.empty() }
        detailsSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = tableScrollPane
            bottomComponent = null
            dividerSize = 5
            resizeWeight = 1.0
            isContinuousLayout = true
        }
        
        allFilesCardPanel.add(JBScrollPane(allFilesPanel).apply { border = JBUI.Borders.empty() }, "map")
        allFilesCardPanel.add(detailsSplitPane, "table")
        p.add(allFilesCardPanel, BorderLayout.CENTER)
        
        allFilesStatus.font = Font("Segoe UI", Font.PLAIN, 12)
        p.add(createBottomBar(allFilesStatus, true), BorderLayout.SOUTH)
        return p
    }
    
    private fun createToggleBtn(text: String, selected: Boolean): JButton {
        return JButton(text).apply {
            font = Font("Segoe UI", Font.PLAIN, 12); isFocusPainted = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor(Color(180, 180, 180), Color(80, 80, 80)), 1), JBUI.Borders.empty(4, 14))
            if (selected) { background = Color(59, 130, 246); foreground = Color.WHITE } else { background = JBColor(Color(255, 255, 255), Color(60, 63, 65)); foreground = JBColor(Color(60, 60, 60), Color(200, 200, 200)) }
        }
    }
    
    private fun createBottomBar(status: JBLabel, legend: Boolean): JPanel {
        val bar = JPanel(BorderLayout())
        bar.background = JBColor(Color(245, 245, 245), Color(40, 40, 40))
        bar.border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(Color(220, 220, 220), Color(60, 60, 60))), JBUI.Borders.empty(6, 12))
        status.border = JBUI.Borders.empty(0, 4)
        bar.add(status, BorderLayout.WEST)
        if (legend) {
            val leg = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
            leg.isOpaque = false
            
            val items = listOf(
                Triple(Color(124, 58, 237), "Hub (5+)", NodeFilter.HUB),
                Triple(Color(34, 197, 94), "Parent", NodeFilter.PARENT),
                Triple(Color(59, 130, 246), "Child", NodeFilter.CHILD),
                Triple(Color(239, 68, 68), "Current", NodeFilter.CURRENT),
                Triple(Color(148, 163, 184), "Isolated", NodeFilter.ISOLATED)
            )
            
            items.forEach { (color, label, filter) ->
                val dot = JPanel()
                dot.background = color
                dot.preferredSize = Dimension(12, 12)
                dot.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                dot.toolTipText = "Right-click to filter by $label"
                
                val lbl = JBLabel(label)
                lbl.font = Font("Segoe UI", Font.PLAIN, 10)
                lbl.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                lbl.toolTipText = "Right-click to filter by $label"
                
                // Create popup menu for filtering
                val popup = JPopupMenu()
                val showOnlyItem = JMenuItem("🔍 Show Only: $label")
                showOnlyItem.addActionListener {
                    connectedPanel.setNodeFilter(filter)
                    allFilesPanel.setNodeFilter(filter)
                }
                val showAllItem = JMenuItem("👁 Show All")
                showAllItem.addActionListener {
                    connectedPanel.setNodeFilter(NodeFilter.ALL)
                    allFilesPanel.setNodeFilter(NodeFilter.ALL)
                }
                popup.add(showOnlyItem)
                popup.addSeparator()
                popup.add(showAllItem)
                
                val mouseListener = object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) popup.show(e.component, e.x, e.y)
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) popup.show(e.component, e.x, e.y)
                    }
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON1) {
                            // Left-click toggles filter
                            val currentFilter = connectedPanel.getNodeFilter()
                            if (currentFilter == filter) {
                                connectedPanel.setNodeFilter(NodeFilter.ALL)
                                allFilesPanel.setNodeFilter(NodeFilter.ALL)
                            } else {
                                connectedPanel.setNodeFilter(filter)
                                allFilesPanel.setNodeFilter(filter)
                            }
                        }
                    }
                }
                
                dot.addMouseListener(mouseListener)
                lbl.addMouseListener(mouseListener)
                
                leg.add(dot)
                leg.add(lbl)
            }
            bar.add(leg, BorderLayout.EAST)
        }
        return bar
    }
    
    private fun setupEditorListener() {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(e: FileEditorManagerEvent) { 
                val currentPath = getCurrentFilePath()
                connectedPanel.setCurrentFile(currentPath)
                allFilesPanel.setCurrentFile(currentPath)
                impactPanel.setCurrentFile(currentPath) // [NEW] Update Impact Panel
            }
        })
    }
    
    fun refreshGraph() {
        mainStatusLabel.text = "⏳ Refreshing..."
        val ds = DumbService.getInstance(project)
        if (ds.isDumb) { mainStatusLabel.text = "⏳ Waiting for indexing..."; ds.runWhenSmart { doRefresh() } } else doRefresh()
    }
    
    private fun doRefresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                fullGraph = ApplicationManager.getApplication().runReadAction<ConnectionGraph> { GraphBuilder(project).buildGraph() }
                SwingUtilities.invokeLater { updatePanels() }
            } catch (e: Exception) { SwingUtilities.invokeLater { mainStatusLabel.text = "❌ Error: ${e.message}" } }
        }
    }
    
    private fun updatePanels() {
        val g = fullGraph ?: return
        connectedPaths.clear(); connectionCounts.clear()
        g.getAllEdges().forEach { connectedPaths.add(it.from.filePath); connectedPaths.add(it.to.filePath); connectionCounts[it.from.filePath] = (connectionCounts[it.from.filePath] ?: 0) + 1; connectionCounts[it.to.filePath] = (connectionCounts[it.to.filePath] ?: 0) + 1 }
        
        val currentPath = getCurrentFilePath()
        connectedPanel.setGraph(g, currentPath)
        allFilesPanel.setGraph(g, currentPath)
        impactPanel.updateGraph(g) // [NEW] Update Impact Graph
        impactPanel.setCurrentFile(currentPath) // Ensure current file is set
        
        isolatedTableModel.setFiles(g.getAllNodes().filter { it.filePath !in connectedPaths })
        allFilesTableModel.setFiles(g.getAllNodes(), connectionCounts)
        
        val total = g.nodeCount(); val edges = g.getAllEdges().size; val conn = connectedPaths.size; val iso = total - conn
        mainStatusLabel.text = "📊 $total files • $edges connections"
        connectedStatus.text = "🔗 $conn connected files"; isolatedStatus.text = "📦 $iso isolated files"; allFilesStatus.text = "📂 $total total • $conn connected • $iso isolated"
        // Tab indices shifted by +1 because Impact is first now
        tabbedPane?.setTitleAt(1, "🔗 Connected ($conn)"); tabbedPane?.setTitleAt(2, "📦 Isolated ($iso)"); tabbedPane?.setTitleAt(3, "📂 All Files ($total)")
    }
    
    private fun getCurrentFilePath() = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path
    fun getContent() = mainPanel
}

class IsolatedFilesTableModel : AbstractTableModel() {
    private var files: List<GraphNode> = emptyList()
    private val cols = arrayOf("#", "Type", "File Name", "Package")
    fun setFiles(f: List<GraphNode>) { files = f.sortedBy { it.fileName.lowercase() }; fireTableDataChanged() }
    fun getFilePathAt(r: Int) = files[r].filePath
    override fun getRowCount() = files.size
    override fun getColumnCount() = cols.size
    override fun getColumnName(c: Int) = cols[c]
    override fun getValueAt(r: Int, c: Int): Any { val f = files[r]; return when (c) { 0 -> (r + 1).toString(); 1 -> if (f.fileName.endsWith(".kt")) "Kotlin" else "Java"; 2 -> f.fileName.removeSuffix(".kt").removeSuffix(".java"); 3 -> f.packageName.ifEmpty { "(default)" }; else -> "" } }
}

class AllFilesTableModel : AbstractTableModel() {
    private var files: List<GraphNode> = emptyList()
    private var conns: Map<String, Int> = emptyMap()
    private val cols = arrayOf("#", "Type", "File Name", "Package", "Connections")
    fun setFiles(f: List<GraphNode>, c: Map<String, Int>) { conns = c; files = f.sortedWith(compareByDescending<GraphNode> { c[it.filePath] ?: 0 }.thenBy { it.fileName.lowercase() }); fireTableDataChanged() }
    fun getFilePathAt(r: Int) = files[r].filePath
    override fun getRowCount() = files.size
    override fun getColumnCount() = cols.size
    override fun getColumnName(c: Int) = cols[c]
    override fun getValueAt(r: Int, c: Int): Any { val f = files[r]; return when (c) { 0 -> (r + 1).toString(); 1 -> if (f.fileName.endsWith(".kt")) "Kotlin" else "Java"; 2 -> f.fileName.removeSuffix(".kt").removeSuffix(".java"); 3 -> f.packageName.ifEmpty { "(default)" }; 4 -> conns[f.filePath] ?: 0; else -> "" } }
}
