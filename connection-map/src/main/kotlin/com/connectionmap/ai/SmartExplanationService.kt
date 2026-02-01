package com.connectionmap.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object SmartExplanationService {
    // --- CONFIGURATION ---
    // TODO: Replace with your OpenAI API key or use environment variable
    private const val API_KEY = "YOUR_OPENAI_API_KEY_HERE"
    private const val MODEL = "gpt-4o-mini"

    // Cache to make repeat clicks instant
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    // FEATURE 1: DETAILED File Analysis
    fun getExplanation(project: Project, path: String, forceRefresh: Boolean): String {
        val fileIo = File(path)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(fileIo) ?: return "File not found"
        val currentStamp = virtualFile.modificationStamp

        if (!forceRefresh && cache.containsKey(path)) {
            val (cachedStamp, explanation) = cache[path]!!
            if (cachedStamp == currentStamp) return "$explanation <div style='margin-top:15px; font-size:10px; color:#555;'>(Cached Result)</div>"
        }

        val content = try { String(virtualFile.contentsToByteArray()).take(3000) } catch (e: Exception) { "" }

        // --- DETAILED PROMPT ---
        val prompt = """
            You are a Senior Software Architect. Analyze '${virtualFile.name}' in depth.
            CODE SNIPPET:
            ${content.take(2000)}
            
            TASK:
            1. **Architectural Role**: Explain exactly what this file manages and why it exists in the system.
            2. **Logic Deep Dive**: Don't just list functions. Explain the *flow* of data. How does it handle errors? What algorithms are used?
            3. **Dependencies**: Mention key imports or external systems it interacts with.
            
            FORMAT (HTML ONLY):
            <h3>CORE RESPONSIBILITY</h3>
            [2-3 detailed sentences explaining the high-level purpose]
            
            <h3>TECHNICAL LOGIC</h3>
            <ul>
            <li><b>[Function/Class Name]</b>: [Detailed explanation of how it works, inputs, and outputs]</li>
            <li><b>[Key Logic Block]</b>: [Explanation of specific algorithms or handling]</li>
            </ul>
            
            <h3>CONTEXT</h3>
            [Explain how this fits into the larger application flow]
        """.trimIndent()

        val aiResponse = callOpenAI(prompt, maxTokens = 1000)
        cache[path] = Pair(currentStamp, aiResponse)
        return aiResponse
    }

    // FEATURE 2: Detailed Connection Analysis
    fun getRelationshipExplanation(sourcePath: String, targetPath: String): String {
        val s = File(sourcePath).name
        val t = File(targetPath).name

        val prompt = """
            Analyze the relationship: Why does '$s' depend on '$t'?
            
            TASK:
            Explain the data flow between these two components. 
            - What data is passed?
            - Is it a control dependency (calling a function) or a data dependency (using a model)?
            - Why is this link necessary?
            
            Format: HTML. Use <b> for key terms.
        """.trimIndent()

        return callOpenAI(prompt, maxTokens = 300)
    }

    // FEATURE 3: Cluster Labeling
    fun getClusterLabel(fileNames: String): String {
        return callOpenAI("Label this group of files: [$fileNames]. Max 2 words. Example: 'Auth Module'.", maxTokens = 10)
            .replace(Regex("<[^>]*>"), "").trim()
    }

    private fun callOpenAI(userPrompt: String, maxTokens: Int): String {
        if (API_KEY.contains("YOUR_KEY")) return "<b>Error:</b> API Key Missing."
        try {
            val url = URL("https://api.openai.com/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val jsonInput = """
                {
                    "model": "$MODEL",
                    "messages": [{"role": "user", "content": "${userPrompt.replace("\"", "\\\"").replace("\n", " ")}"}],
                    "temperature": 0.3,
                    "max_tokens": $maxTokens
                }
            """.trimIndent()

            OutputStreamWriter(conn.outputStream).use { it.write(jsonInput) }
            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val jsonObject = Gson().fromJson(response, JsonObject::class.java)
            return jsonObject.getAsJsonArray("choices").get(0).asJsonObject.get("message").asJsonObject.get("content").asString
        } catch (e: Exception) { return "Error: ${e.message}" }
    }
}
