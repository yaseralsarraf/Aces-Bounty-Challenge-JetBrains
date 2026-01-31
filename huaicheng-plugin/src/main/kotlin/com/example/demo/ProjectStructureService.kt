package com.example.demo

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.openapi.vcs.FileStatusManager
import java.io.File
import java.util.*

// Updated Node with Professional Metadata
data class GraphNode(
    val id: String,
    val name: String,
    val type: String,      // "SERVICE", "UTIL", "CORE", "UI", "OTHER"
    val hasErrors: Boolean,
    val isModified: Boolean,
    var clusterId: String = ""
)

data class GraphLink(val source: String, val target: String)
data class GraphData(val nodes: List<GraphNode>, val links: List<GraphLink>, val groups: Map<String, List<String>>)

class ProjectStructureService(private val project: Project) {

    fun getSmartStructureJson(): String {
        val nodes = mutableListOf<GraphNode>()
        val links = mutableListOf<GraphLink>()
        val fileMap = mutableMapOf<String, VirtualFile>()
        val problemSolver = WolfTheProblemSolver.getInstance(project)
        val statusManager = FileStatusManager.getInstance(project)

        // 1. Index All Files & Analyze Metadata
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && isValidFile(file)) {
                val type = determineFileType(file.name, file.path)
                val hasErrors = problemSolver.isProblemFile(file)
                // "Unknown" status usually means not tracked or modified
                val status = statusManager.getStatus(file)
                val isModified = status.text != "NOT_CHANGED"

                nodes.add(GraphNode(file.path, file.name, type, hasErrors, isModified))
                fileMap[file.name] = file
            }
            true
        }

        // 2. RAW TEXT SCANNING (Bypasses IDE Errors)
        fileMap.values.forEach { file ->
            try {
                val text = String(file.contentsToByteArray())
                findAssociationsRaw(file, text, fileMap, links)
            } catch (e: Exception) {
                // Ignore read errors
            }
        }

        // 3. Cluster Algorithm
        val groups = calculateClusters(nodes, links)

        return Gson().toJson(GraphData(nodes, links, groups))
    }

    private fun determineFileType(name: String, path: String): String {
        val lowerName = name.lowercase()
        val lowerPath = path.lowercase()

        return when {
            lowerName.contains("service") || lowerName.contains("controller") || lowerName.contains("manager") -> "SERVICE"
            lowerName.contains("util") || lowerName.contains("helper") || lowerName.contains("config") -> "UTIL"
            lowerName.contains("model") || lowerName.contains("entity") || lowerName.contains("dto") || lowerName.contains("database") -> "CORE"
            lowerName.contains("view") || lowerName.contains("activity") || lowerName.contains("fragment") || lowerName.contains("ui") -> "UI"
            else -> "OTHER"
        }
    }

    private fun findAssociationsRaw(currentFile: VirtualFile, text: String, fileMap: Map<String, VirtualFile>, links: MutableList<GraphLink>) {
        val myName = currentFile.nameWithoutExtension
        fileMap.keys.forEach { otherFileName ->
            val otherNameSimple = File(otherFileName).nameWithoutExtension
            if (otherNameSimple != myName && text.contains(otherNameSimple)) {
                links.add(GraphLink(currentFile.path, fileMap[otherFileName]!!.path))
            }
        }
    }

    private fun calculateClusters(nodes: List<GraphNode>, links: List<GraphLink>): Map<String, List<String>> {
        val adj = mutableMapOf<String, MutableList<String>>()
        nodes.forEach { adj[it.id] = mutableListOf() }
        links.forEach {
            adj[it.source]?.add(it.target)
            adj[it.target]?.add(it.source)
        }

        val visited = mutableSetOf<String>()
        val clusters = mutableMapOf<String, List<String>>()
        var clusterCounter = 1

        for (node in nodes) {
            if (!visited.contains(node.id)) {
                val component = mutableListOf<String>()
                val queue: Queue<String> = LinkedList()
                queue.add(node.id)
                visited.add(node.id)

                while (queue.isNotEmpty()) {
                    val current = queue.poll()
                    component.add(current)
                    adj[current]?.forEach { neighbor ->
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }

                val cId = if (component.size > 1) "cluster_$clusterCounter" else "unlinked"
                if (component.size > 1) clusterCounter++

                component.forEach { nodeId -> nodes.find { it.id == nodeId }?.clusterId = cId }
                val fileNames = component.map { id -> nodes.find { it.id == id }!!.name }
                clusters[cId] = fileNames
            }
        }
        return clusters
    }

    fun openFile(path: String) {
        val file = File(path)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
        if (virtualFile != null) {
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }
    }

    private fun isValidFile(file: VirtualFile): Boolean {
        return file.extension == "java" || file.extension == "kt" || file.extension == "py"
    }
}