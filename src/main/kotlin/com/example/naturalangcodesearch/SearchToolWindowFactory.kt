package com.example.naturalangcodesearch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField

class SearchToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {//creates the textbox for user input
        // 1. Create UI
        val panel = JPanel(BorderLayout())
        val searchField = JTextField()
        val searchButton = JButton("Ask Gemini")
        val resultArea = JTextArea()

        resultArea.isEditable = false
        resultArea.lineWrap = true
        resultArea.wrapStyleWord = true

        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(searchField, BorderLayout.CENTER)
        inputPanel.add(searchButton, BorderLayout.EAST)

        panel.add(inputPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(resultArea), BorderLayout.CENTER)

        // 2. Button Logic
        searchButton.addActionListener {
            val query = searchField.text
            if (query.isNotEmpty()) {

                // --- NEW: Grab the code from the user's open editor ---
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                val currentCode = editor?.document?.text ?: "No code file is currently open."
                // -----------------------------------------------------

                resultArea.text = "Reading your code and thinking..."

                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        // We combine your question + the code into one prompt
                        val fullPrompt = "Here is the code I am working on:\n\n$currentCode\n\nMy Question: $query"

                        val service = GeminiService()
                        val answer = service.sendPrompt(fullPrompt)

                        ApplicationManager.getApplication().invokeLater {
                            resultArea.text = answer
                        }
                    } catch (e: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            resultArea.text = "Error: ${e.message}"
                        }
                    }
                }
            }
        }

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}