package com.connectionmap.ui

import com.connectionmap.model.ConnectionGraph
import com.connectionmap.model.GraphNode
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.RadialGradientPaint
import javax.swing.JPanel
import kotlin.math.*

enum class DisplayMode { CONNECTED, ISOLATED, ALL }
enum class NodeFilter { ALL, HUB, PARENT, CHILD, CURRENT, ISOLATED }

class GraphPanel(private val project: Project) : JPanel() {
    
    private var graph: ConnectionGraph = ConnectionGraph()
    private var currentFilePath: String? = null
    private var hoveredNode: GraphNode? = null
    private var selectedNode: GraphNode? = null
    private var focusedNode: GraphNode? = null
    private var focusedConnections = mutableSetOf<String>()
    private var displayMode = DisplayMode.ALL
    private var nodeFilter = NodeFilter.ALL
    
    // Node sizes for mindmap - rectangles
    private val nodeHeight = 36
    private val nodeMinWidth = 100
    private val nodeMaxWidth = 250
    private val cornerRadius = 8.0
    
    private var zoomLevel = 0.65
    private var panX = 100.0
    private var panY = 50.0
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var isPanning = false
    
    // Color palette matching legend: Hub=Purple, Parent=Green, Child=Blue, Current=Red, Isolated=Gray
    val colorHub = Color(124, 58, 237)       // Purple for hub nodes (5+ connections)
    val colorParent = Color(34, 197, 94)     // Green for parent nodes (more imports)
    val colorChild = Color(59, 130, 246)     // Blue for child nodes (more imported by)
    val colorCurrentFile = Color(239, 68, 68) // Red for current file
    val colorIsolated = Color(148, 163, 184) // Gray for isolated nodes
    
    private val colorLine = Color(100, 120, 150)  // Subtle gray-blue lines
    private val colorBgLight = Color(255, 255, 255)
    private val colorBgDark = Color(30, 35, 45)
    
    private var connectedPaths = mutableSetOf<String>()
    private var displayNodes = listOf<GraphNode>()
    private var displayEdges = listOf<com.connectionmap.model.GraphEdge>()
    
    private var importCount = mutableMapOf<String, Int>()
    private var importedByCount = mutableMapOf<String, Int>()
    
    // Node level for hierarchical display
    private var nodeLevel = mutableMapOf<String, Int>()
    
    init {
        background = JBColor(colorBgLight, colorBgDark)
        isOpaque = true
        setupMouseListeners()
    }
    
    private fun setupMouseListeners() {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val clickedNode = findNodeAt(e.x, e.y)
                
                if (clickedNode != null) {
                    if (displayMode == DisplayMode.CONNECTED) {
                        if (focusedNode == clickedNode) clearFocus()
                        else setFocusedNode(clickedNode)
                    }
                    if (e.clickCount == 2) openFile(clickedNode.filePath)
                    selectedNode = clickedNode
                    repaint()
                } else {
                    if (focusedNode != null) clearFocus()
                }
            }
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON3 || e.button == MouseEvent.BUTTON2) {
                    isPanning = true; lastMouseX = e.x; lastMouseY = e.y
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }
            override fun mouseReleased(e: MouseEvent) { isPanning = false; cursor = Cursor.getDefaultCursor() }
        })
        
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val node = findNodeAt(e.x, e.y)
                if (node != hoveredNode) { hoveredNode = node; repaint() }
                cursor = if (node != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
            override fun mouseDragged(e: MouseEvent) {
                if (isPanning) {
                    panX += (e.x - lastMouseX) / zoomLevel
                    panY += (e.y - lastMouseY) / zoomLevel
                    lastMouseX = e.x; lastMouseY = e.y
                    repaint()
                }
            }
        })
        
        addMouseWheelListener { e: MouseWheelEvent ->
            val oldZoom = zoomLevel
            zoomLevel = max(0.2, min(2.5, zoomLevel - e.wheelRotation * 0.08))
            panX += e.x / oldZoom - e.x / zoomLevel
            panY += e.y / oldZoom - e.y / zoomLevel
            repaint()
        }
    }
    
    fun setGraph(newGraph: ConnectionGraph, currentPath: String? = null) {
        graph = newGraph
        currentFilePath = currentPath
        
        connectedPaths.clear()
        importCount.clear()
        importedByCount.clear()
        
        graph.getAllEdges().forEach { edge ->
            connectedPaths.add(edge.from.filePath)
            connectedPaths.add(edge.to.filePath)
            importCount[edge.from.filePath] = (importCount[edge.from.filePath] ?: 0) + 1
            importedByCount[edge.to.filePath] = (importedByCount[edge.to.filePath] ?: 0) + 1
        }
        
        updateDisplayedNodes()
    }
    
    fun setCurrentFile(path: String?) { currentFilePath = path; repaint() }
    fun setDisplayMode(mode: DisplayMode) { displayMode = mode; clearFocus(); updateDisplayedNodes() }
    fun setNodeFilter(filter: NodeFilter) { nodeFilter = filter; repaint() }
    fun getNodeFilter() = nodeFilter
    fun resetView() { zoomLevel = 0.65; panX = 100.0; panY = 50.0; clearFocus(); repaint() }
    fun getConnectedCount() = connectedPaths.size
    fun getIsolatedCount() = graph.nodeCount() - connectedPaths.size
    
    private fun setFocusedNode(node: GraphNode) {
        focusedNode = node
        focusedConnections.clear()
        focusedConnections.add(node.filePath)
        displayEdges.forEach { edge ->
            if (edge.from.filePath == node.filePath) focusedConnections.add(edge.to.filePath)
            if (edge.to.filePath == node.filePath) focusedConnections.add(edge.from.filePath)
        }
        repaint()
    }
    
    private fun clearFocus() { focusedNode = null; focusedConnections.clear(); repaint() }
    private fun isNodeInFocus(node: GraphNode) = focusedNode == null || node.filePath in focusedConnections
    private fun isEdgeInFocus(edge: com.connectionmap.model.GraphEdge) = 
        focusedNode == null || edge.from.filePath == focusedNode?.filePath || edge.to.filePath == focusedNode?.filePath
    
    fun getNodeType(node: GraphNode): NodeFilter {
        if (node.filePath == currentFilePath) return NodeFilter.CURRENT
        val imports = importCount[node.filePath] ?: 0
        val importedBy = importedByCount[node.filePath] ?: 0
        val total = imports + importedBy
        return when {
            node.filePath !in connectedPaths -> NodeFilter.ISOLATED
            total >= 5 -> NodeFilter.HUB
            imports > importedBy -> NodeFilter.PARENT
            else -> NodeFilter.CHILD
        }
    }
    
    private fun isNodeVisible(node: GraphNode): Boolean {
        if (nodeFilter == NodeFilter.ALL) return true
        if (node.filePath == currentFilePath) return true
        return getNodeType(node) == nodeFilter
    }
    
    private fun updateDisplayedNodes() {
        displayNodes = when (displayMode) {
            DisplayMode.CONNECTED -> graph.getAllNodes().filter { it.filePath in connectedPaths }
            DisplayMode.ISOLATED -> graph.getAllNodes().filter { it.filePath !in connectedPaths }
            DisplayMode.ALL -> graph.getAllNodes()
        }
        val paths = displayNodes.map { it.filePath }.toSet()
        displayEdges = graph.getAllEdges().filter { it.from.filePath in paths && it.to.filePath in paths }
        calculateRadialLayout()
        repaint()
    }
    
    private fun calculateRadialLayout() {
        if (displayNodes.isEmpty()) return
        
        val connected = displayNodes.filter { it.filePath in connectedPaths }
        val isolated = displayNodes.filter { it.filePath !in connectedPaths }
        
        if (connected.isNotEmpty()) layoutRadialMindmap(connected)
        if (isolated.isNotEmpty()) {
            val startX = if (connected.isNotEmpty()) connected.maxOf { it.x } + 400 else 100.0
            layoutIsolatedGrid(isolated, startX)
        }
        
        updatePreferredSize()
    }
    
    private fun layoutRadialMindmap(nodes: List<GraphNode>) {
        if (nodes.isEmpty()) return
        
        nodeLevel.clear()
        
        // Calculate total connections for each node
        val totalConns = nodes.associateWith { (importCount[it.filePath] ?: 0) + (importedByCount[it.filePath] ?: 0) }
        
        // Build connections map
        val connections = mutableMapOf<String, MutableSet<String>>()
        displayEdges.forEach { edge ->
            connections.getOrPut(edge.from.filePath) { mutableSetOf() }.add(edge.to.filePath)
            connections.getOrPut(edge.to.filePath) { mutableSetOf() }.add(edge.from.filePath)
        }
        
        // Categorize nodes by connectivity (for rings)
        val hubs = nodes.filter { (totalConns[it] ?: 0) >= 5 }.sortedByDescending { totalConns[it] ?: 0 }
        val highConnected = nodes.filter { (totalConns[it] ?: 0) in 3..4 }
        val mediumConnected = nodes.filter { (totalConns[it] ?: 0) == 2 }
        val lowConnected = nodes.filter { (totalConns[it] ?: 0) == 1 }
        
        // Center point for the spider web
        val centerX = 600.0
        val centerY = 400.0
        
        // Radii for concentric rings
        val ringRadii = listOf(
            0.0,      // Center (single most connected hub)
            200.0,    // Inner ring (other hubs)
            380.0,    // Mid-inner ring (highly connected)
            560.0,    // Mid-outer ring (medium connected)
            740.0     // Outer ring (lightly connected)
        )
        
        // Place nodes in rings
        placeNodesInRing(if (hubs.isNotEmpty()) listOf(hubs.first()) else emptyList(), centerX, centerY, ringRadii[0], 0)
        placeNodesInRing(hubs.drop(1), centerX, centerY, ringRadii[1], 0)
        placeNodesInRing(highConnected, centerX, centerY, ringRadii[2], 1)
        placeNodesInRing(mediumConnected, centerX, centerY, ringRadii[3], 2)
        placeNodesInRing(lowConnected, centerX, centerY, ringRadii[4], 3)
        
        // FINAL PASS: Global collision resolution across ALL nodes
        resolveAllCollisions(nodes)
    }
    
    private fun resolveAllCollisions(nodes: List<GraphNode>) {
        val minDistance = nodeMaxWidth + 70.0  // Even larger spacing
        var globalAdjusted = true
        var globalIterations = 0
        
        // Keep adjusting until no more collisions or max iterations
        while (globalAdjusted && globalIterations < 200) {
            globalAdjusted = false
            globalIterations++
            
            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val node1 = nodes[i]
                    val node2 = nodes[j]
                    
                    val dx = node1.x - node2.x
                    val dy = node1.y - node2.y
                    val distance = sqrt(dx * dx + dy * dy)
                    
                    if (distance < minDistance && distance > 0.1) {
                        // Push both nodes apart equally
                        val pushFactor = (minDistance - distance) / (2 * distance)
                        node1.x += dx * pushFactor
                        node1.y += dy * pushFactor
                        node2.x -= dx * pushFactor
                        node2.y -= dy * pushFactor
                        globalAdjusted = true
                    }
                }
            }
        }
    }
    
    private fun placeNodesInRing(nodes: List<GraphNode>, cx: Double, cy: Double, radius: Double, level: Int) {
        if (nodes.isEmpty()) return
        
        val nodeCount = nodes.size
        val angleStep = (2 * PI) / nodeCount.coerceAtLeast(1)
        
        // Add some randomness to start angle to avoid uniform grid look
        val startAngle = if (level > 0) (level * 0.3) else 0.0
        
        nodes.forEachIndexed { index, node ->
            val angle = startAngle + index * angleStep
            
            if (radius == 0.0) {
                // Center node
                node.x = cx
                node.y = cy
            } else {
                // Radial distribution with slight jitter to break uniformity
                val jitterRadius = radius + (index % 3 - 1) * 20  // Slightly larger radial variation
                node.x = cx + jitterRadius * cos(angle)
                node.y = cy + jitterRadius * sin(angle)
            }
            
            nodeLevel[node.filePath] = level
        }
        
        // ENHANCED collision detection and adjustment - check against ALL nodes
        val minDistance = nodeMaxWidth + 60.0  // Increased minimum space between node centers
        
        nodes.forEach { node ->
            var adjusted = true
            var iterations = 0
            
            // More iterations and check against ALL display nodes
            while (adjusted && iterations < 100) {
                adjusted = false
                iterations++
                
                // Check collision with all other nodes globally
                displayNodes.filter { it != node }.forEach { other ->
                    val dx = node.x - other.x
                    val dy = node.y - other.y
                    val distance = sqrt(dx * dx + dy * dy)
                    
                    if (distance < minDistance && distance > 0.1) {
                        // Push nodes apart more aggressively
                        val pushFactor = (minDistance - distance) / distance * 0.6
                        node.x += dx * pushFactor
                        node.y += dy * pushFactor
                        adjusted = true
                    }
                }
            }
        }
    }
    
    private fun layoutIsolatedGrid(nodes: List<GraphNode>, startX: Double) {
        val cols = 4
        val spacingX = nodeMaxWidth + 40
        val spacingY = nodeHeight + 25
        nodes.sortedBy { it.fileName.lowercase() }.forEachIndexed { i, node ->
            node.x = startX + (i % cols) * spacingX
            node.y = 80.0 + (i / cols) * spacingY
            nodeLevel[node.filePath] = 3
        }
    }
    
    private fun updatePreferredSize() {
        if (displayNodes.isEmpty()) { preferredSize = Dimension(1200, 900); return }
        val maxX = displayNodes.maxOf { it.x + nodeMaxWidth }.toInt() + 200
        val maxY = displayNodes.maxOf { it.y + nodeHeight }.toInt() + 200
        preferredSize = Dimension(maxX, maxY)
        revalidate()
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        
        val orig = g2.transform
        g2.translate(panX * zoomLevel, panY * zoomLevel)
        g2.scale(zoomLevel, zoomLevel)
        
        if (displayNodes.isEmpty()) { g2.transform = orig; drawEmpty(g2); return }
        
        drawEdges(g2)
        drawNodes(g2)
        
        g2.transform = orig
        drawFocusIndicator(g2)
        drawZoomHint(g2)
    }
    
    private fun drawEdges(g2: Graphics2D) {
        for (edge in displayEdges) {
            if (!isNodeVisible(edge.from) || !isNodeVisible(edge.to)) continue
            
            val inFocus = isEdgeInFocus(edge)
            val isHover = hoveredNode?.filePath == edge.from.filePath || hoveredNode?.filePath == edge.to.filePath
            
            // For rectangles, connect from center
            val fromX = edge.from.x
            val fromY = edge.from.y
            val toX = edge.to.x
            val toY = edge.to.y
            
            // Line style
            val alpha = when {
                !inFocus && focusedNode != null -> 25
                isHover -> 200
                else -> 100
            }
            val thickness = when {
                isHover -> 2f
                !inFocus && focusedNode != null -> 0.5f
                else -> 1.2f
            }
            
            g2.stroke = BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.color = Color(colorLine.red, colorLine.green, colorLine.blue, alpha)
            g2.draw(Line2D.Double(fromX, fromY, toX, toY))
        }
    }
    
    private fun drawNodes(g2: Graphics2D) {
        // Draw in order: outer first, then inner (so center is on top)
        val sortedNodes = displayNodes.sortedByDescending { nodeLevel[it.filePath] ?: 3 }
        
        for (node in sortedNodes) {
            val visible = isNodeVisible(node) && isNodeInFocus(node)
            
            val isCurrent = node.filePath == currentFilePath
            val isHover = node == hoveredNode
            val isFocused = node == focusedNode
            
            val alpha = if (visible) 255 else 35
            
            // Get file name without extension
            val fileName = node.fileName.removeSuffix(".kt").removeSuffix(".java")
            
            // Calculate node width based on text - larger bold font for readability
            g2.font = Font("Segoe UI Semibold", Font.BOLD, 13)
            val fm = g2.fontMetrics
            val textWidth = fm.stringWidth(fileName)
            val nodeWidth = (textWidth + 24).coerceIn(nodeMinWidth, nodeMaxWidth)
            
            val x = node.x - nodeWidth / 2
            val y = node.y - nodeHeight / 2
            
            // Determine colors based on node TYPE (matching legend exactly)
            val nodeType = getNodeType(node)
            
            val (fillColor, strokeColor, textColor) = when {
                isCurrent -> Triple(colorCurrentFile, colorCurrentFile.darker(), Color.WHITE)
                isHover -> Triple(Color(251, 191, 36), Color(245, 158, 11), Color(50, 50, 50))
                isFocused -> Triple(Color(255, 215, 0), Color(218, 165, 32), Color(50, 50, 50))
                nodeType == NodeFilter.HUB -> Triple(colorHub, colorHub.darker(), Color.WHITE)
                nodeType == NodeFilter.PARENT -> Triple(colorParent, colorParent.darker(), Color.WHITE)
                nodeType == NodeFilter.CHILD -> Triple(colorChild, colorChild.darker(), Color.WHITE)
                nodeType == NodeFilter.ISOLATED -> Triple(colorIsolated, colorIsolated.darker(), Color.WHITE)
                else -> Triple(colorChild, colorChild.darker(), Color.WHITE)
            }
            
            // === VERY LIGHT GLOW (only if visible) ===
            if (visible) {
                // Single subtle glow layer
                g2.color = Color(fillColor.red, fillColor.green, fillColor.blue, 15)
                g2.fill(java.awt.geom.RoundRectangle2D.Double(x - 4, y - 4, nodeWidth.toDouble() + 8, nodeHeight.toDouble() + 8, cornerRadius + 4, cornerRadius + 4))
            }
            
            // === SUBTLE SHADOW ===
            if (visible) {
                g2.color = Color(0, 0, 0, 20)
                g2.fill(java.awt.geom.RoundRectangle2D.Double(x + 2, y + 2, nodeWidth.toDouble(), nodeHeight.toDouble(), cornerRadius, cornerRadius))
            }
            
            // === MAIN RECTANGLE ===
            g2.color = Color(fillColor.red, fillColor.green, fillColor.blue, alpha)
            g2.fill(java.awt.geom.RoundRectangle2D.Double(x, y, nodeWidth.toDouble(), nodeHeight.toDouble(), cornerRadius, cornerRadius))
            
            // === BORDER ===
            g2.color = Color(strokeColor.red, strokeColor.green, strokeColor.blue, alpha)
            g2.stroke = BasicStroke(1.5f)
            g2.draw(java.awt.geom.RoundRectangle2D.Double(x, y, nodeWidth.toDouble(), nodeHeight.toDouble(), cornerRadius, cornerRadius))
            
            if (!visible) continue
            
            // === TEXT (full file name) ===
            val textX = node.x - fm.stringWidth(fileName) / 2
            val textY = node.y + fm.ascent / 2 - 2
            
            g2.color = Color(textColor.red, textColor.green, textColor.blue, alpha)
            g2.drawString(fileName, textX.toFloat(), textY.toFloat())
        }
    }
    
    private fun drawFocusIndicator(g2: Graphics2D) {
        if (focusedNode == null || displayMode != DisplayMode.CONNECTED) return
        
        g2.font = Font("Segoe UI Semibold", Font.BOLD, 12)
        val name = focusedNode?.fileName?.removeSuffix(".kt")?.removeSuffix(".java") ?: ""
        val text = "🎯 Focused: $name (click elsewhere to reset)"
        val textWidth = g2.fontMetrics.stringWidth(text)
        
        g2.color = JBColor(Color(255, 255, 255, 240), Color(40, 40, 40, 240))
        g2.fillRoundRect(width / 2 - textWidth / 2 - 15, 10, textWidth + 30, 28, 8, 8)
        g2.color = Color(41, 128, 185)
        g2.stroke = BasicStroke(2f)
        g2.drawRoundRect(width / 2 - textWidth / 2 - 15, 10, textWidth + 30, 28, 8, 8)
        g2.color = JBColor(Color(60, 60, 60), Color(220, 220, 220))
        g2.drawString(text, width / 2 - textWidth / 2, 29)
    }
    
    private fun drawZoomHint(g2: Graphics2D) {
        g2.font = Font("Segoe UI", Font.PLAIN, 11)
        g2.color = JBColor(Color(120, 130, 140), Color(140, 150, 160))
        val hint = if (displayMode == DisplayMode.CONNECTED) "Click=focus  •  " else ""
        g2.drawString("${hint}Scroll=zoom  •  Right-drag=pan  •  Double-click=open  •  Zoom: ${(zoomLevel * 100).toInt()}%", 15, height - 8)
    }
    
    private fun drawEmpty(g2: Graphics2D) {
        g2.font = Font("Segoe UI", Font.PLAIN, 40)
        g2.color = JBColor(Color(200, 210, 220), Color(80, 90, 100))
        val icon = when (displayMode) { DisplayMode.CONNECTED -> "🔗"; DisplayMode.ISOLATED -> "📦"; else -> "📂" }
        g2.drawString(icon, width / 2 - 20, height / 2)
        g2.font = Font("Segoe UI", Font.PLAIN, 14)
        val msg = when (displayMode) { DisplayMode.CONNECTED -> "No connected files"; DisplayMode.ISOLATED -> "No isolated files"; else -> "No files" }
        g2.drawString(msg, width / 2 - g2.fontMetrics.stringWidth(msg) / 2, height / 2 + 40)
    }
    
    private fun findNodeAt(mx: Int, my: Int): GraphNode? {
        val tx = mx / zoomLevel - panX
        val ty = my / zoomLevel - panY
        return displayNodes.find { node ->
            // Rectangle hit test
            val halfWidth = nodeMaxWidth / 2.0
            val halfHeight = nodeHeight / 2.0
            abs(tx - node.x) <= halfWidth && abs(ty - node.y) <= halfHeight
        }
    }
    
    private fun openFile(path: String) {
        LocalFileSystem.getInstance().findFileByPath(path)?.let { 
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true) 
        }
    }
}
