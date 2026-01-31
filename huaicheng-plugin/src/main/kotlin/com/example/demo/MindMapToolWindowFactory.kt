package com.example.demo

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

class MindMapToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val structureService = ProjectStructureService(project)
        val browser = JBCefBrowser()

        val htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <script src="https://cdnjs.cloudflare.com/ajax/libs/d3/7.8.5/d3.min.js"></script>
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600&display=swap" rel="stylesheet">
                <style>
                    body { margin: 0; overflow: hidden; background: #1e1f22; font-family: 'Inter', sans-serif; display: flex; height: 100vh; color: #dfe1e5; }
                    
                    /* LAYOUT */
                    #app-container { display: flex; width: 100%; height: 100%; position: relative; }
                    #graph-area { flex-grow: 1; position: relative; background: #1e1f22; }
                    
                    /* --- MODERN SIDEBAR (Unified Design) --- */
                    #sidebar { 
                        width: 400px; 
                        background: #111214; 
                        border-left: 1px solid #2b2d30; 
                        display: flex; flex-direction: column; 
                        z-index: 30; 
                        box-shadow: -10px 0 40px rgba(0,0,0,0.6);
                    }
                    
                    .sidebar-header { 
                        padding: 25px 25px 15px; 
                        border-bottom: 1px solid #232426; 
                        background: transparent;
                    }
                    .sidebar-header h2 {
                        margin: 0; font-size: 12px; font-weight: 600; 
                        text-transform: uppercase; letter-spacing: 1.5px; 
                        color: #6c6f78; 
                    }

                    #details-content { 
                        padding: 25px; 
                        flex-grow: 1; 
                        overflow-y: auto; 
                        font-size: 13px; 
                        line-height: 1.7; 
                        color: #cfd0d6; 
                    }

                    /* AI TEXT STYLING */
                    #details-content h3 { 
                        color: #fff; font-size: 14px; margin-top: 20px; margin-bottom: 12px; 
                        font-weight: 600; text-transform: uppercase; border-left: 3px solid #3574f0; padding-left: 10px;
                    }
                    #details-content h3:first-child { margin-top: 0; }
                    
                    #details-content b { color: #3574f0; font-weight: 600; } 
                    #details-content ul { padding-left: 20px; margin-bottom: 15px; }
                    #details-content li { margin-bottom: 10px; color: #b0b2b8; }
                    
                    /* CUSTOM SCROLLBAR */
                    #details-content::-webkit-scrollbar { width: 6px; }
                    #details-content::-webkit-scrollbar-track { background: #111214; }
                    #details-content::-webkit-scrollbar-thumb { background: #333; border-radius: 3px; }

                    /* ACTION BUTTON */
                    .btn-area { padding: 20px; border-top: 1px solid #232426; background: #111214; }
                    .action-btn { 
                        background: #3574f0; color: white; border: none; 
                        padding: 10px 12px; border-radius: 6px; cursor: pointer; width: 100%; 
                        font-family: 'Inter', sans-serif; font-weight: 500; font-size: 13px;
                        transition: background 0.2s;
                    }
                    .action-btn:hover { background: #4080ff; }
                    .action-btn:disabled { background: #2b2d30; color: #555; }

                    /* GRAPH ELEMENTS */
                    .node { cursor: pointer; transition: opacity 0.3s ease; }
                    .node circle { stroke: #fff; stroke-width: 2px; transition: all 0.2s; }
                    .node text { font-size: 10px; fill: #eee; pointer-events: none; text-shadow: 0 1px 4px #000; font-weight: 600; }
                    .node:hover circle { stroke: #3574f0; stroke-width: 4px; filter: drop-shadow(0 0 8px rgba(53, 116, 240, 0.6)); }

                    .link-visible { stroke: #666; stroke-width: 1.5px; marker-end: url(#arrow); opacity: 0.15; transition: all 0.3s; pointer-events: none; }
                    
                    /* ZONE LABELS */
                    .cluster-label { font-size: 12px; fill: #666; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; pointer-events: none; opacity: 0.5; }

                    #title-badge { position:absolute; top:20px; left:20px; background:#3574f0; color:white; padding:5px 12px; border-radius:15px; font-size:12px; font-weight:bold; pointer-events:none; }
                    .zone-badge { position:absolute; bottom:20px; right:20px; border: 2px dashed #333; padding: 10px 15px; border-radius: 10px; color: #555; font-size: 11px; font-weight: bold; pointer-events: none; }
                </style>
            </head>
            <body>
                <div id="app-container">
                    <div id="graph-area">
                        <div id="title-badge">FILE CLUSTERS</div>
                        <div class="zone-badge">ISOLATED FILES</div>
                    </div>
                    
                    <div id="sidebar">
                        <div class="sidebar-header"><h2>File Inspector</h2></div>
                        <div id="details-content">
                            <div style="text-align:center; margin-top:60px; color:#555;">
                                <div style="font-size:24px; margin-bottom:10px; opacity:0.3;">📂</div>
                                Select a file to understand its role.
                            </div>
                        </div>
                        <div class="btn-area">
                            <button id="refresh-btn" class="action-btn" onclick="triggerRefresh()" disabled>↻ Re-Analyze</button>
                        </div>
                    </div>
                </div>

                <svg style="position: absolute; width:0; height:0;">
                    <defs>
                        <marker id="arrow" viewBox="0 -5 10 10" refX="22" refY="0" markerWidth="6" markerHeight="6" orient="auto">
                            <path d="M0,-5L10,0L0,5" fill="#666"></path>
                        </marker>
                    </defs>
                </svg>

                <script>
                    let rawData = { nodes: [], links: [] }; 
                    let simulation;
                    let currentPayload = null;
                    const TYPE_COLORS = { 'SERVICE': '#815BF5', 'UTIL': '#2ECC71', 'CORE': '#3498DB', 'UI': '#E67E22', 'OTHER': '#95A5A6' };

                    function updateGraph(data) {
                        rawData = data;
                        render(data);
                        
                        // Async Labeling for Clusters
                        if(data.groups) {
                            Object.keys(data.groups).forEach(groupId => {
                                if(groupId !== 'unlinked') {
                                    const fileNames = data.groups[groupId].join(",");
                                    window.location.href = "label:" + groupId + "|" + fileNames;
                                }
                            });
                        }
                    }

                    function render(data) {
                        d3.select("#graph-area svg").remove();
                        const container = document.getElementById('graph-area');
                        const width = container.clientWidth;
                        const height = container.clientHeight;

                        const svg = d3.select("#graph-area").append("svg").attr("width", "100%").attr("height", "100%")
                            .call(d3.zoom().on("zoom", (e) => g.attr("transform", e.transform))).append("g");
                        const g = svg.append("g");

                        // CLUSTER PHYSICS
                        simulation = d3.forceSimulation(data.nodes)
                            .force("collide", d3.forceCollide(30))
                            .force("charge", d3.forceManyBody().strength(-80)); // Gentle repulsion

                        // Zone Separation
                        simulation.force("x", d3.forceX(d => d.clusterId === 'unlinked' ? width * 0.8 : width / 2).strength(0.08));
                        simulation.force("y", d3.forceY(d => d.clusterId === 'unlinked' ? height * 0.8 : height / 2).strength(0.08));

                        // Links (Faint in this view)
                        const link = g.append("g").selectAll(".link").data(data.links).enter().append("line")
                            .attr("class", "link-visible");

                        // Nodes
                        const node = g.append("g").selectAll(".node").data(data.nodes).enter().append("g")
                            .attr("class", "node")
                            .on("click", (e, d) => {
                                currentPayload = d.id;
                                document.getElementById('refresh-btn').disabled = false;
                                triggerAI(d.id);
                                window.location.href = "openfile:" + d.id;
                            })
                            .call(d3.drag().on("start", dragStart).on("drag", dragging).on("end", dragEnd));

                        node.append("circle").attr("r", 15).attr("fill", d => TYPE_COLORS[d.type] || '#888');
                        node.append("text").attr("dy", -25).attr("text-anchor", "middle").text(d => d.name);

                        // Cluster Labels
                        let labels;
                        if(data.groups) {
                             labels = g.append("g").selectAll(".cluster-label")
                                 .data(Object.keys(data.groups)).enter().append("text")
                                 .attr("class", "cluster-label")
                                 .attr("id", d => "label-" + d)
                                 .text(d => d === 'unlinked' ? "" : ""); // Wait for AI label
                        }

                        simulation.on("tick", () => {
                            link.attr("x1", d => d.source.x).attr("y1", d => d.source.y).attr("x2", d => d.target.x).attr("y2", d => d.target.y);
                            node.attr("transform", d => `translate(` + d.x + `,` + d.y + `)`);
                            
                            if(labels) {
                                labels.attr("x", groupId => {
                                    const nodesInGroup = data.nodes.filter(n => n.clusterId === groupId);
                                    return nodesInGroup.length ? d3.mean(nodesInGroup, n => n.x) : 0;
                                }).attr("y", groupId => {
                                    const nodesInGroup = data.nodes.filter(n => n.clusterId === groupId);
                                    return nodesInGroup.length ? d3.min(nodesInGroup, n => n.y) - 40 : 0;
                                });
                            }
                        });
                    }

                    function setLabel(id, text) { d3.select("#label-" + id).text(text); }

                    function triggerAI(payload) {
                        document.getElementById('details-content').innerHTML = `<div style='text-align:center; padding-top:60px; color:#555;'><b>Analyzing File...</b></div>`;
                        window.location.href = "explain:file|" + payload + "|cached";
                    }

                    function triggerRefresh() { if(currentPayload) window.location.href = "explain:file|" + currentPayload + "|force"; }
                    function updateSidebar(html) { document.getElementById('details-content').innerHTML = html; }
                    function dragStart(e, d) { if(!e.active) simulation.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; }
                    function dragging(e, d) { d.fx = e.x; d.fy = e.y; }
                    function dragEnd(e, d) { if(!e.active) simulation.alphaTarget(0); d.fx = null; d.fy = null; }
                </script>
            </body>
            </html>
        """.trimIndent()

        browser.loadHTML(htmlContent, "http://mindmap/index.html")

        // HANDLERS (Same standard handlers)
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean): Boolean {
                val url = request?.url ?: return false
                if (url.startsWith("openfile:")) { structureService.openFile(url.substring("openfile:".length)); return true }
                if (url.startsWith("label:")) {
                    val parts = url.substring("label:".length).split("|")
                    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                        val label = SmartExplanationService.getClusterLabel(parts[1])
                        SwingUtilities.invokeLater { browser?.executeJavaScript("setLabel('${parts[0]}', '$label')", browser.url, 0) }
                    }
                    return true
                }
                if (url.startsWith("explain:")) {
                    val parts = url.substring("explain:".length).split("|")
                    val force = if (parts.size > 2) parts[2] == "force" else false
                    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                        val text = if (parts[0] == "file") SmartExplanationService.getExplanation(project, parts[1], force)
                        else SmartExplanationService.getRelationshipExplanation(parts[1], parts[2])
                        val safeHtml = text.replace("'", "\\'").replace("\n", "")
                        SwingUtilities.invokeLater { browser?.executeJavaScript("updateSidebar('$safeHtml')", browser.url, 0) }
                    }
                    return true
                }
                return false
            }
        }, browser.cefBrowser)

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                val json = structureService.getSmartStructureJson()
                browser?.executeJavaScript("updateGraph($json)", browser.url, 0)
            }
        }, browser.cefBrowser)

        val panel = JPanel(BorderLayout())
        val refreshBtn = JButton("Reset Clusters")
        refreshBtn.addActionListener {
            val json = structureService.getSmartStructureJson()
            browser.cefBrowser.executeJavaScript("updateGraph($json)", browser.cefBrowser.url, 0)
        }
        panel.add(refreshBtn, BorderLayout.NORTH)
        panel.add(browser.component, BorderLayout.CENTER)

        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }
}