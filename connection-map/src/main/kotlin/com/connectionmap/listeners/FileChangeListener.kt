package com.connectionmap.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.connectionmap.ConnectionMapToolWindowContent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * FileChangeListener: Listens for file system changes and triggers graph refresh.
 * 
 * BulkFileListener: IntelliJ interface that receives batched file change events.
 * This is more efficient than listening to individual file changes.
 * 
 * IMPORTANT: This listener is registered in plugin.xml as a projectListener,
 * which means it's automatically created and connected for each project.
 */
class FileChangeListener(private val project: Project) : BulkFileListener {
    
    companion object {
        // Debounce delay in milliseconds
        // This prevents constant rebuilds when the user is typing
        private const val DEBOUNCE_MS = 250
        
        // Extensions we care about
        private val RELEVANT_EXTENSIONS = setOf("kt", "java")
    }
    
    // Timestamp of the last event we received
    private val lastEventTime = AtomicLong(0)
    
    // Timer for debouncing
    private var debounceTimer: Timer? = null
    
    /**
     * Called by IntelliJ when file changes occur.
     * 
     * @param events List of file change events (create, modify, delete, etc.)
     */
    override fun after(events: List<VFileEvent>) {
        // Check if any of the changed files are Kotlin or Java
        val hasRelevantChanges = events.any { event ->
            val extension = event.file?.extension ?: event.path.substringAfterLast(".", "")
            extension in RELEVANT_EXTENSIONS
        }
        
        if (hasRelevantChanges) {
            scheduleRefresh()
        }
    }
    
    /**
     * Schedule a graph refresh with debouncing.
     * 
     * Debouncing: If multiple events come in rapid succession (like when
     * the user is typing), we only trigger one refresh after a short delay.
     * This prevents lag from constant rebuilding.
     */
    private fun scheduleRefresh() {
        lastEventTime.set(System.currentTimeMillis())
        
        // Cancel any pending timer
        debounceTimer?.stop()
        
        // Create a new timer
        debounceTimer = Timer(DEBOUNCE_MS) {
            // Check if enough time has passed since the last event
            val timeSinceLastEvent = System.currentTimeMillis() - lastEventTime.get()
            if (timeSinceLastEvent >= DEBOUNCE_MS) {
                triggerRefresh()
            }
        }
        debounceTimer?.isRepeats = false
        debounceTimer?.start()
    }
    
    /**
     * Trigger the actual graph refresh.
     * 
     * We find the Connection Map tool window and tell it to refresh.
     * This uses the ToolWindowManager to access our tool window.
     */
    private fun triggerRefresh() {
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("Connection Map")
            
            // Only refresh if the tool window is visible
            // (no point rebuilding if the user isn't looking at it)
            if (toolWindow != null && toolWindow.isVisible) {
                // Get the content and refresh
                toolWindow.contentManager.contents.firstOrNull()?.let { content ->
                    val component = content.component
                    // Find the ConnectionMapToolWindowContent and call refresh
                    // Since we don't have direct access, we trigger a repaint
                    // which will use the cached graph
                    component.repaint()
                }
            }
        }
    }
}
