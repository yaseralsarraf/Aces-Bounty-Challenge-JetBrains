package com.connectionmap.ui

import com.connectionmap.analysis.ImpactAnalyzer
import com.connectionmap.model.ConnectionGraph
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Panel showing Impact/Blast Radius analysis for the current file.
 */
class ImpactPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private var graph: ConnectionGraph? = null
    private var currentFilePath: String? = null
    private var currentDepth = 2
    
    private val currentFileLabel = JBLabel("📍 No file selected")
    private val impactCountLabel = JBLabel("")
    private val listModel = DefaultListModel<ImpactAnalyzer.ImpactedFile>()
    private val impactList = JList(listModel)
    private val statusLabel = JBLabel("")
    
    // Depth buttons
    private val depth1Button = createDepthButton("1 hop", 1)
    private val depth2Button = createDepthButton("2 hops", 2)
    private val depth3Button = createDepthButton("3 hops", 3)
    
    init {
        background = JBColor(Color(250, 251, 252), Color(30, 32, 34))
        border = JBUI.Borders.empty(12)
        
        add(createTopPanel(), BorderLayout.NORTH)
        add(createCenterPanel(), BorderLayout.CENTER)
        add(createBottomPanel(), BorderLayout.SOUTH)
        
        updateDepthButtons()
    }
    
    private fun createTopPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyBottom(12)
        
        // Title
        val titleLabel = JBLabel("🎯 Impact / Blast Radius")
        titleLabel.font = Font("Segoe UI Semibold", Font.BOLD, 16)
        titleLabel.foreground = JBColor(Color(30, 41, 59), Color(226, 232, 240))
        
        // Current file
        currentFileLabel.font = Font("Segoe UI", Font.PLAIN, 13)
        currentFileLabel.foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184))
        currentFileLabel.border = JBUI.Borders.emptyTop(4)
        
        // Depth selector
        val depthPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        depthPanel.isOpaque = false
        depthPanel.border = JBUI.Borders.emptyTop(8)
        
        val depthLabel = JBLabel("Depth: ")
        depthLabel.font = Font("Segoe UI", Font.PLAIN, 12)
        depthLabel.foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184))
        
        depthPanel.add(depthLabel)
        depthPanel.add(depth1Button)
        depthPanel.add(depth2Button)
        depthPanel.add(depth3Button)
        
        val topContent = JPanel()
        topContent.layout = BoxLayout(topContent, BoxLayout.Y_AXIS)
        topContent.isOpaque = false
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        currentFileLabel.alignmentX = Component.LEFT_ALIGNMENT
        depthPanel.alignmentX = Component.LEFT_ALIGNMENT
        topContent.add(titleLabel)
        topContent.add(currentFileLabel)
        topContent.add(depthPanel)
        
        panel.add(topContent, BorderLayout.CENTER)
        
        // Impact count on right
        impactCountLabel.font = Font("Segoe UI Semibold", Font.BOLD, 14)
        impactCountLabel.foreground = JBColor(Color(239, 68, 68), Color(248, 113, 113))
        panel.add(impactCountLabel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createDepthButton(text: String, depth: Int): JButton {
        val button = JButton(text)
        button.font = Font("Segoe UI", Font.PLAIN, 11)
        button.isFocusPainted = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.preferredSize = Dimension(70, 28)
        
        button.addActionListener {
            currentDepth = depth
            updateDepthButtons()
            refreshImpact()
        }
        
        return button
    }
    
    private fun updateDepthButtons() {
        listOf(depth1Button to 1, depth2Button to 2, depth3Button to 3).forEach { (btn, depth) ->
            if (depth == currentDepth) {
                btn.background = JBColor(Color(59, 130, 246), Color(37, 99, 235))
                btn.foreground = Color.WHITE
            } else {
                btn.background = JBColor(Color(241, 245, 249), Color(51, 65, 85))
                btn.foreground = JBColor(Color(71, 85, 105), Color(203, 213, 225))
            }
        }
    }
    
    private fun createCenterPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        
        // List styling
        impactList.font = Font("Segoe UI", Font.PLAIN, 13)
        impactList.background = JBColor(Color.WHITE, Color(36, 40, 45))
        impactList.selectionBackground = JBColor(Color(59, 130, 246, 40), Color(59, 130, 246, 60))
        impactList.fixedCellHeight = 40
        
        impactList.cellRenderer = ImpactListCellRenderer()
        
        // Double-click to open file
        impactList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = impactList.selectedValue
                    if (selected != null) {
                        openFile(selected.filePath)
                    }
                }
            }
        })
        
        val scrollPane = JBScrollPane(impactList)
        scrollPane.border = BorderFactory.createLineBorder(JBColor(Color(226, 232, 240), Color(51, 65, 85)), 1)
        
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createBottomPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyTop(12)
        
        // Button row
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttonPanel.isOpaque = false
        
        val copyButton = createActionButton("📋 Copy List", "Copy impacted files as Markdown")
        copyButton.addActionListener { copyToClipboard() }
        
        val openAllButton = createActionButton("📂 Open All", "Open impacted files (max 10)")
        openAllButton.addActionListener { openAllFiles() }
        
        val openTestsButton = createActionButton("🧪 Open Tests", "Open only test files")
        openTestsButton.addActionListener { openTestFiles() }
        
        buttonPanel.add(copyButton)
        buttonPanel.add(openAllButton)
        buttonPanel.add(openTestsButton)
        
        panel.add(buttonPanel, BorderLayout.CENTER)
        
        // Status label
        statusLabel.font = Font("Segoe UI", Font.PLAIN, 11)
        statusLabel.foreground = JBColor(Color(34, 197, 94), Color(74, 222, 128))
        panel.add(statusLabel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createActionButton(text: String, tooltip: String): JButton {
        val button = JButton(text)
        button.font = Font("Segoe UI", Font.PLAIN, 12)
        button.toolTipText = tooltip
        button.isFocusPainted = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.background = JBColor(Color(241, 245, 249), Color(51, 65, 85))
        button.foreground = JBColor(Color(51, 65, 85), Color(226, 232, 240))
        return button
    }
    
    fun updateGraph(newGraph: ConnectionGraph) {
        this.graph = newGraph
        refreshImpact()
    }
    
    fun setCurrentFile(filePath: String?) {
        this.currentFilePath = filePath
        val fileName = filePath?.substringAfterLast("/")?.substringAfterLast("\\") ?: "No file selected"
        currentFileLabel.text = "📍 $fileName"
        refreshImpact()
    }
    
    private fun refreshImpact() {
        listModel.clear()
        statusLabel.text = ""
        
        val graph = this.graph ?: return
        val filePath = this.currentFilePath ?: return
        
        val analyzer = ImpactAnalyzer(graph)
        val impacted = analyzer.getImpactedFiles(filePath, currentDepth)
        
        impacted.forEach { listModel.addElement(it) }
        
        val testCount = impacted.count { it.isTest }
        impactCountLabel.text = if (impacted.isEmpty()) "✅ No impact" else "⚠️ ${impacted.size} files (${testCount} tests)"
        impactCountLabel.foreground = if (impacted.isEmpty()) 
            JBColor(Color(34, 197, 94), Color(74, 222, 128))
        else 
            JBColor(Color(239, 68, 68), Color(248, 113, 113))
    }
    
    private fun copyToClipboard() {
        val graph = this.graph ?: return
        val filePath = this.currentFilePath ?: return
        
        val analyzer = ImpactAnalyzer(graph)
        val impacted = analyzer.getImpactedFiles(filePath, currentDepth)
        val markdown = analyzer.formatAsMarkdown(filePath, impacted)
        
        val selection = StringSelection(markdown)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
        
        statusLabel.text = "✅ Copied to clipboard!"
        Timer(2000) { statusLabel.text = "" }.apply { isRepeats = false; start() }
    }
    
    private fun openAllFiles() {
        val items = (0 until listModel.size()).map { listModel.getElementAt(it) }
        val toOpen = items.take(10)
        toOpen.forEach { openFile(it.filePath) }
        statusLabel.text = "✅ Opened ${toOpen.size} files"
        Timer(2000) { statusLabel.text = "" }.apply { isRepeats = false; start() }
    }
    
    private fun openTestFiles() {
        val items = (0 until listModel.size()).map { listModel.getElementAt(it) }
        val tests = items.filter { it.isTest }.take(10)
        tests.forEach { openFile(it.filePath) }
        statusLabel.text = if (tests.isEmpty()) "ℹ️ No test files found" else "✅ Opened ${tests.size} test files"
        Timer(2000) { statusLabel.text = "" }.apply { isRepeats = false; start() }
    }
    
    private fun openFile(path: String) {
        LocalFileSystem.getInstance().findFileByPath(path)?.let {
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true)
        }
    }
    
    /**
     * Custom cell renderer for impact list
     */
    private inner class ImpactListCellRenderer : ListCellRenderer<ImpactAnalyzer.ImpactedFile> {
        override fun getListCellRendererComponent(
            list: JList<out ImpactAnalyzer.ImpactedFile>,
            value: ImpactAnalyzer.ImpactedFile,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(8, 12)
            
            if (isSelected) {
                panel.background = JBColor(Color(59, 130, 246, 40), Color(59, 130, 246, 60))
            } else {
                panel.background = if (index % 2 == 0) 
                    JBColor(Color.WHITE, Color(36, 40, 45))
                else 
                    JBColor(Color(248, 250, 252), Color(42, 46, 51))
            }
            
            // Icon based on hop distance
            val icon = when (value.hopDistance) {
                1 -> "🔴"
                2 -> "🟠"
                else -> "🟡"
            }
            val testIcon = if (value.isTest) " 🧪" else ""
            
            val nameLabel = JBLabel("$icon ${value.fileName}$testIcon")
            nameLabel.font = Font("Segoe UI", Font.PLAIN, 13)
            nameLabel.foreground = JBColor(Color(30, 41, 59), Color(226, 232, 240))
            
            val hopLabel = JBLabel("${value.hopDistance} hop")
            hopLabel.font = Font("Segoe UI", Font.PLAIN, 11)
            hopLabel.foreground = JBColor(Color(148, 163, 184), Color(100, 116, 139))
            
            panel.add(nameLabel, BorderLayout.CENTER)
            panel.add(hopLabel, BorderLayout.EAST)
            
            return panel
        }
    }
}
