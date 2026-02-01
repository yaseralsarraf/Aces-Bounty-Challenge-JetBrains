package com.connectionmap.builder

import com.connectionmap.model.ConnectionGraph
import com.connectionmap.model.GraphEdge
import com.connectionmap.model.GraphNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * GraphBuilder: Scans project files and builds the dependency graph.
 * 
 * Uses two methods for finding files:
 * 1. FilenameIndex (fast, but requires indexing to be complete)
 * 2. VFS traversal fallback (slower, but always works)
 */
class GraphBuilder(private val project: Project) {
    
    private val packageRegex = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    // Handle 'import static' (Java) and 'import' (Java/Kotlin)
    // Matches: "import [static] com.example.Class"
    private val importRegex = Regex("""^\s*import\s+(?:static\s+)?([\w.]+)(?:\.\*)?""", RegexOption.MULTILINE)
    
    /**
     * Builds the complete connection graph for the project.
     */
    fun buildGraph(): ConnectionGraph {
        val graph = ConnectionGraph()
        
        // Find all Kotlin and Java files using both methods
        val allFiles = findAllSourceFiles()
        
        if (allFiles.isEmpty()) {
            return graph // Return empty graph, UI will show "no files found"
        }
        
        // Build nodes and extract info
        val fileInfoMap = mutableMapOf<String, FileInfo>()
        val fqnToFilePath = mutableMapOf<String, String>()
        
        for (file in allFiles) {
            try {
                // Skip if file is not valid or too large (>1MB)
                if (!file.isValid || file.length > 1_000_000) continue
                
                val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                
                val packageName = extractPackage(content)
                val imports = extractImports(content)
                
                val node = GraphNode(
                    filePath = file.path,
                    fileName = file.name,
                    packageName = packageName
                )
                
                graph.addNode(node)
                fileInfoMap[file.path] = FileInfo(node, imports)
                fqnToFilePath[node.getFullyQualifiedName()] = file.path
                
            } catch (e: Exception) {
                // Log but continue - don't let one bad file break everything
                continue
            }
        }
        
        // Create edges based on import statements
        for ((filePath, info) in fileInfoMap) {
            // 1. Check explicit imports
            for (importStatement in info.imports) {
                val targetFilePath = resolveImport(importStatement, fqnToFilePath, fileInfoMap)
                
                if (targetFilePath != null && targetFilePath != filePath) {
                    val targetNode = graph.getNode(targetFilePath)
                    if (targetNode != null) {
                        graph.addEdge(GraphEdge(
                            from = info.node,
                            to = targetNode,
                            importStatement = "import $importStatement"
                        ))
                    }
                }
            }
            
            // 2. Check same-package dependencies (files in same package don't need imports)
            // We scan the content for the class names of other files in the same package
            val packageSiblings = fileInfoMap.values.filter { 
                it.node.packageName == info.node.packageName && 
                it.node.filePath != filePath 
            }
            
            if (packageSiblings.isNotEmpty()) {
                val content = try {
                    String(com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)?.contentsToByteArray() ?: ByteArray(0), Charsets.UTF_8)
                } catch (e: Exception) { "" }
                
                for (sibling in packageSiblings) {
                    val siblingClassName = sibling.node.fileName.removeSuffix(".kt").removeSuffix(".java")
                    // Simple heuristic: if the file contains the sibling's class name, it likely depends on it
                    // Use \b boundary to avoid partial matches
                    if (content.contains(Regex("\\b$siblingClassName\\b"))) {
                        graph.addEdge(GraphEdge(
                            from = info.node,
                            to = sibling.node,
                            importStatement = "(same package)"
                        ))
                    }
                }
            }
        }
        
        return graph
    }
    
    /**
     * Find all source files using multiple methods for reliability.
     */
    private fun findAllSourceFiles(): List<VirtualFile> {
        val files = mutableSetOf<VirtualFile>()
        
        // Method 1: Try FilenameIndex (fast, but needs indexing)
        try {
            val scope = GlobalSearchScope.projectScope(project)
            files.addAll(FilenameIndex.getAllFilesByExt(project, "kt", scope))
            files.addAll(FilenameIndex.getAllFilesByExt(project, "java", scope))
        } catch (e: Exception) {
            // Index not ready, will use fallback
        }
        
        // Method 2: VFS traversal fallback (always works)
        if (files.isEmpty()) {
            try {
                val contentRoots = ProjectRootManager.getInstance(project).contentRoots
                for (root in contentRoots) {
                    VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                        override fun visitFile(file: VirtualFile): Boolean {
                            if (!file.isDirectory) {
                                val ext = file.extension?.lowercase()
                                if (ext == "kt" || ext == "java") {
                                    // Skip build directories and hidden files
                                    val path = file.path.lowercase()
                                    if (!path.contains("/build/") && 
                                        !path.contains("\\build\\") &&
                                        !path.contains("/.") &&
                                        !file.name.startsWith(".")) {
                                        files.add(file)
                                    }
                                }
                            }
                            return true // Continue visiting
                        }
                    })
                }
            } catch (e: Exception) {
                // If this also fails, return what we have
            }
        }
        
        return files.toList()
    }
    
    private fun extractPackage(content: String): String {
        return packageRegex.find(content)?.groupValues?.get(1) ?: ""
    }
    
    private fun extractImports(content: String): List<String> {
        return importRegex.findAll(content)
            .map { it.groupValues[1] }
            .filter { 
                !it.startsWith("java.") && 
                !it.startsWith("kotlin.") && 
                !it.startsWith("javax.") &&
                !it.startsWith("android.")
            }
            .toList()
    }
    
    private fun resolveImport(
        importStatement: String,
        fqnToFilePath: Map<String, String>,
        fileInfoMap: Map<String, FileInfo>
    ): String? {
        // Direct match
        fqnToFilePath[importStatement]?.let { return it }
        
        // Match by class name
        val className = importStatement.substringAfterLast(".")
        val packagePrefix = importStatement.substringBeforeLast(".", "")
        
        for ((filePath, info) in fileInfoMap) {
            val fileClassName = info.node.fileName.removeSuffix(".kt").removeSuffix(".java")
            if (fileClassName == className && info.node.packageName == packagePrefix) {
                return filePath
            }
        }
        
        // Match by class name only (for same-package imports)
        for ((filePath, info) in fileInfoMap) {
            val fileClassName = info.node.fileName.removeSuffix(".kt").removeSuffix(".java")
            if (fileClassName == className) {
                return filePath
            }
        }
        
        // Partial match fallback
        for ((fqn, path) in fqnToFilePath) {
            if (importStatement.startsWith(fqn)) {
                return path
            }
        }
        
        return null
    }
    
    private data class FileInfo(
        val node: GraphNode,
        val imports: List<String>
    )
}
