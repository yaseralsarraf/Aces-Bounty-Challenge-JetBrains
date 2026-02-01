package com.connectionmap.analysis

import com.connectionmap.model.ConnectionGraph
import com.connectionmap.model.GraphEdge

/**
 * Analyzes the impact/blast radius of changing a file.
 * Uses BFS to find files affected at various depths (hops).
 */
class ImpactAnalyzer(private val graph: ConnectionGraph) {
    
    data class ImpactedFile(
        val filePath: String,
        val fileName: String,
        val hopDistance: Int,
        val isTest: Boolean
    )
    
    /**
     * Get all files impacted by changing the given file.
     * @param filePath The file being changed
     * @param maxDepth Maximum hop distance (1, 2, or 3)
     * @return List of impacted files sorted by hop distance
     */
    fun getImpactedFiles(filePath: String, maxDepth: Int): List<ImpactedFile> {
        val impacted = mutableMapOf<String, Int>() // path -> hop distance
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>() // (path, depth)
        
        // Build adjacency map: file -> files that DEPEND ON it (importedBy)
        // When we change a file, we affect files that import it
        val dependents = buildDependentsMap()
        
        queue.add(filePath to 0)
        visited.add(filePath)
        
        while (queue.isNotEmpty()) {
            val (currentPath, depth) = queue.removeFirst()
            
            if (depth > 0 && depth <= maxDepth) {
                impacted[currentPath] = depth
            }
            
            if (depth < maxDepth) {
                dependents[currentPath]?.forEach { dependent ->
                    if (dependent !in visited) {
                        visited.add(dependent)
                        queue.add(dependent to depth + 1)
                    }
                }
            }
        }
        
        return impacted.map { (path, hop) ->
            val fileName = path.substringAfterLast("/").substringAfterLast("\\")
            ImpactedFile(
                filePath = path,
                fileName = fileName,
                hopDistance = hop,
                isTest = isTestFile(path, fileName)
            )
        }.sortedWith(compareBy({ it.hopDistance }, { it.fileName }))
    }
    
    /**
     * Build a map of file -> files that depend on it (import it)
     */
    private fun buildDependentsMap(): Map<String, Set<String>> {
        val dependents = mutableMapOf<String, MutableSet<String>>()
        
        graph.getAllEdges().forEach { edge ->
            // edge.from imports edge.to
            // So edge.to is depended on by edge.from
            dependents.getOrPut(edge.to.filePath) { mutableSetOf() }.add(edge.from.filePath)
        }
        
        return dependents
    }
    
    /**
     * Check if a file is a test file based on path/naming conventions
     */
    private fun isTestFile(path: String, fileName: String): Boolean {
        val pathLower = path.lowercase()
        val nameLower = fileName.lowercase()
        
        return pathLower.contains("/test/") ||
               pathLower.contains("\\test\\") ||
               pathLower.contains("/tests/") ||
               pathLower.contains("\\tests\\") ||
               nameLower.endsWith("test.kt") ||
               nameLower.endsWith("test.java") ||
               nameLower.endsWith("tests.kt") ||
               nameLower.endsWith("tests.java") ||
               nameLower.startsWith("test")
    }
    
    /**
     * Get only test files from the impacted list
     */
    fun getImpactedTests(impactedFiles: List<ImpactedFile>): List<ImpactedFile> {
        return impactedFiles.filter { it.isTest }
    }
    
    /**
     * Format impacted files as Markdown for PR descriptions
     */
    fun formatAsMarkdown(sourceFile: String, impactedFiles: List<ImpactedFile>): String {
        val sb = StringBuilder()
        val sourceFileName = sourceFile.substringAfterLast("/").substringAfterLast("\\")
        
        sb.appendLine("## 🎯 Impact Analysis: `$sourceFileName`")
        sb.appendLine()
        
        if (impactedFiles.isEmpty()) {
            sb.appendLine("No impacted files detected.")
            return sb.toString()
        }
        
        val byHop = impactedFiles.groupBy { it.hopDistance }
        val tests = impactedFiles.filter { it.isTest }
        
        byHop.keys.sorted().forEach { hop ->
            val files = byHop[hop] ?: emptyList()
            val icon = when (hop) {
                1 -> "🔴"
                2 -> "🟠"
                else -> "🟡"
            }
            sb.appendLine("### $icon ${hop}-hop dependencies (${files.size} files)")
            files.forEach { file ->
                val testIcon = if (file.isTest) " 🧪" else ""
                sb.appendLine("- `${file.fileName}`$testIcon")
            }
            sb.appendLine()
        }
        
        if (tests.isNotEmpty()) {
            sb.appendLine("### 🧪 Tests to run (${tests.size})")
            tests.forEach { test ->
                sb.appendLine("- `${test.fileName}`")
            }
        }
        
        return sb.toString()
    }
}
