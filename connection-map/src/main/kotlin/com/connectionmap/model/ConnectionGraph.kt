package com.connectionmap.model

/**
 * GraphNode: Represents a single file in the connection map.
 * 
 * @param filePath Full path to the file (used for opening in editor)
 * @param fileName Display name (just the file name, not full path)
 * @param packageName The package this file belongs to (e.g., "com.example.app")
 * @param x Horizontal position for drawing (calculated by layout algorithm)
 * @param y Vertical position for drawing (calculated by layout algorithm)
 * @param depth Distance from the focused file (0 = focused, 1 = direct import, 2 = second level)
 */
data class GraphNode(
    val filePath: String,
    val fileName: String,
    val packageName: String,
    var x: Double = 0.0,
    var y: Double = 0.0,
    var depth: Int = 0
) {
    /**
     * Gets the fully qualified name (package + class name).
     * Used for matching import statements to files.
     */
    fun getFullyQualifiedName(): String {
        val className = fileName.removeSuffix(".kt").removeSuffix(".java")
        return if (packageName.isNotEmpty()) "$packageName.$className" else className
    }
}

/**
 * GraphEdge: Represents a dependency connection between two files.
 * Direction: "from" file imports "to" file.
 * 
 * @param from The file that contains the import statement
 * @param to The file being imported
 * @param importStatement The actual import line (for "Why connected?" feature)
 */
data class GraphEdge(
    val from: GraphNode,
    val to: GraphNode,
    val importStatement: String = ""
)

/**
 * ConnectionGraph: The complete graph model holding all nodes and edges.
 * 
 * This is the main data structure that the GraphBuilder creates
 * and the GraphPanel renders.
 */
class ConnectionGraph {
    private val nodes = mutableMapOf<String, GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    
    // Index for quick lookup: which files does a given file import?
    private val outgoingEdges = mutableMapOf<String, MutableList<GraphEdge>>()
    
    // Index for quick lookup: which files import a given file? (reverse imports)
    private val incomingEdges = mutableMapOf<String, MutableList<GraphEdge>>()
    
    fun addNode(node: GraphNode) {
        nodes[node.filePath] = node
    }
    
    fun addEdge(edge: GraphEdge) {
        edges.add(edge)
        outgoingEdges.getOrPut(edge.from.filePath) { mutableListOf() }.add(edge)
        incomingEdges.getOrPut(edge.to.filePath) { mutableListOf() }.add(edge)
    }
    
    fun getNode(filePath: String): GraphNode? = nodes[filePath]
    
    fun getAllNodes(): List<GraphNode> = nodes.values.toList()
    
    fun getAllEdges(): List<GraphEdge> = edges.toList()
    
    /**
     * Get files that the given file imports (outgoing connections).
     */
    fun getImports(filePath: String): List<GraphNode> {
        return outgoingEdges[filePath]?.map { it.to } ?: emptyList()
    }
    
    /**
     * Get files that import the given file (incoming connections / reverse imports).
     */
    fun getImportedBy(filePath: String): List<GraphNode> {
        return incomingEdges[filePath]?.map { it.from } ?: emptyList()
    }
    
    /**
     * Get a subgraph centered on a specific file, limited to a certain depth.
     * Used for the "Focus Current File" feature.
     * 
     * @param centerFilePath The file to focus on
     * @param maxDepth How many levels of connections to include (1 or 2)
     * @return A new ConnectionGraph with only the relevant nodes and edges
     */
    fun getSubgraph(centerFilePath: String, maxDepth: Int = 2): ConnectionGraph {
        val subgraph = ConnectionGraph()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>() // (filePath, depth)
        
        val centerNode = nodes[centerFilePath] ?: return subgraph
        centerNode.depth = 0
        subgraph.addNode(centerNode.copy(depth = 0))
        visited.add(centerFilePath)
        queue.add(centerFilePath to 0)
        
        while (queue.isNotEmpty()) {
            val (currentPath, currentDepth) = queue.removeFirst()
            
            if (currentDepth >= maxDepth) continue
            
            // Add outgoing connections (files this file imports)
            outgoingEdges[currentPath]?.forEach { edge ->
                val targetPath = edge.to.filePath
                if (targetPath !in visited) {
                    val newNode = edge.to.copy(depth = currentDepth + 1)
                    subgraph.addNode(newNode)
                    visited.add(targetPath)
                    queue.add(targetPath to currentDepth + 1)
                }
                // Always add the edge if both nodes are in subgraph
                if (targetPath in visited) {
                    subgraph.addEdge(edge)
                }
            }
            
            // Add incoming connections (files that import this file)
            incomingEdges[currentPath]?.forEach { edge ->
                val sourcePath = edge.from.filePath
                if (sourcePath !in visited) {
                    val newNode = edge.from.copy(depth = currentDepth + 1)
                    subgraph.addNode(newNode)
                    visited.add(sourcePath)
                    queue.add(sourcePath to currentDepth + 1)
                }
                // Always add the edge if both nodes are in subgraph
                if (sourcePath in visited) {
                    subgraph.addEdge(edge)
                }
            }
        }
        
        return subgraph
    }
    
    fun clear() {
        nodes.clear()
        edges.clear()
        outgoingEdges.clear()
        incomingEdges.clear()
    }
    
    fun isEmpty(): Boolean = nodes.isEmpty()
    
    fun nodeCount(): Int = nodes.size
    
    fun edgeCount(): Int = edges.size
}
