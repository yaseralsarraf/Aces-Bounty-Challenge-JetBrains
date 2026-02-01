package com.connectionmap

import com.connectionmap.structure.ProjectStructureService
import com.connectionmap.ai.SmartExplanationService
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

class GlobalMapToolWindowFactory : ToolWindowFactory, DumbAware {

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
                    #graph-area { flex-grow: 1; position: relative; background: #1e1f22; transition: width 0.3s ease; }
                    
                    /* --- MODERN SIDEBAR (WIDER FOR DETAILS) --- */
                    #sidebar { 
                        width: 400px; /* Widened for reading detailed text */
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
                        line-height: 1.7; /* Airy reading line-height */
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

                    /* --- FOCUS DRAWER --- */
                    #focus-drawer {
                        width: 0; opacity: 0;
                        background: #18191c; 
                        border-left: 1px solid #333; 
                        display: flex; flex-direction: column;
                        transition: all 0.4s cubic-bezier(0.25, 0.8, 0.25, 1);
                        overflow: hidden;
                        box-shadow: -10px 0 30px rgba(0,0,0,0.5); z-index: 20;
                    }
                    #focus-drawer.active { width: 450px; opacity: 1; }

                    .drawer-header { padding: 20px; background: #202124; border-bottom: 1px solid #333; display: flex; justify-content: space-between; align-items: center; }
                    .drawer-title { font-size: 13px; font-weight: 700; color: #fff; letter-spacing: 0.5px; }
                    .close-btn { cursor: pointer; font-size: 20px; color: #888; transition: 0.2s; }
                    .close-btn:hover { color: #fff; }
                    #mini-map-container { flex-grow: 1; position: relative; background: radial-gradient(circle at center, #2b2d30 0%, #232527 100%); }

                    /* GRAPH STYLES */
                    .node { cursor: pointer; transition: opacity 0.3s ease; }
                    .node circle { stroke: #fff; stroke-width: 2px; transition: all 0.2s; }
                    .node text { font-size: 10px; fill: #eee; pointer-events: none; text-shadow: 0 1px 4px #000; font-weight: 600; }
                    .link-visible { stroke: #666; stroke-width: 1.5px; marker-end: url(#arrow); opacity: 0.4; transition: all 0.3s; pointer-events: none; }
                    
                    /* EFFECTS */
                    .node.dimmed { opacity: 0.1 !important; filter: grayscale(100%); }
                    .link-visible.dimmed { opacity: 0.05 !important; }
                    .node.highlighted circle { stroke: #3574f0; stroke-width: 4px; filter: drop-shadow(0 0 8px rgba(53, 116, 240, 0.6)); }
                    .link-visible.highlighted { stroke: #3574f0 !important; opacity: 1 !important; stroke-width: 2.5px !important; marker-end: url(#arrow-blue) !important; }

                    /* LEGEND */
                    #legend-dock { position: absolute; bottom: 20px; left: 20px; display: flex; gap: 8px; padding: 8px 16px; background: rgba(17, 18, 20, 0.8); border: 1px solid #333; border-radius: 30px; z-index: 10; backdrop-filter: blur(5px); }
                    .legend-item { display: flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 20px; cursor: pointer; font-size: 11px; font-weight: 600; opacity: 0.7; color: #ccc; }
                    .legend-item:hover { background: #333; opacity: 1; color: #fff; }
                    .dot { width: 8px; height: 8px; border-radius: 50%; }

                    #title-badge { position:absolute; top:20px; left:20px; background:#E76F00; color:white; padding:5px 12px; border-radius:15px; font-size:12px; font-weight:bold; pointer-events:none; }
                </style>
            </head>
            <body>
                <div id="app-container">
                    <div id="graph-area">
                        <div id="title-badge">GLOBAL MAP</div>
                        <div id="legend-dock">
                            <div class="legend-item" onclick="resetFilters()">ALL</div>
                            <div class="legend-item"><div class="dot" style="background:#815BF5"></div>Logic</div>
                            <div class="legend-item"><div class="dot" style="background:#2ECC71"></div>Utils</div>
                            <div class="legend-item"><div class="dot" style="background:#3498DB"></div>Data</div>
                        </div>
                    </div>

                    <div id="focus-drawer">
                        <div class="drawer-header">
                            <span class="drawer-title" id="drawer-title">DIRECT RELATIONSHIPS</span>
                            <span class="close-btn" onclick="closeDrawer()">×</span>
                        </div>
                        <div id="mini-map-container"></div>
                    </div>
                    
                    <div id="sidebar">
                        <div class="sidebar-header"><h2>Code Intelligence</h2></div>
                        <div id="details-content">
                            <div style="text-align:center; margin-top:60px; color:#555;">
                                <div style="font-size:24px; margin-bottom:10px; opacity:0.3;">✨</div>
                                Select a node to generate a Detailed Analysis.
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
                        <marker id="arrow-blue" viewBox="0 -5 10 10" refX="22" refY="0" markerWidth="6" markerHeight="6" orient="auto">
                            <path d="M0,-5L10,0L0,5" fill="#3574f0"></path>
                        </marker>
                    </defs>
                </svg>

                <script>
                    let rawData = { nodes: [], links: [] }; 
                    let mainSimulation;
                    let currentPayload = null, currentType = null;
                    let isDrawerOpen = false;
                    const TYPE_COLORS = { 'SERVICE': '#815BF5', 'UTIL': '#2ECC71', 'CORE': '#3498DB', 'UI': '#E67E22', 'OTHER': '#95A5A6' };

                    function updateGraph(data) { rawData = data; renderMain(data); }

                    function renderMain(data) {
                        d3.select("#graph-area svg").remove();
                        const container = document.getElementById('graph-area');
                        const width = container.clientWidth; const height = container.clientHeight;

                        const svg = d3.select("#graph-area").append("svg").attr("width", "100%").attr("height", "100%")
                            .call(d3.zoom().on("zoom", (e) => g.attr("transform", e.transform))).append("g");
                        const g = svg.append("g");

                        mainSimulation = d3.forceSimulation(data.nodes)
                            .force("collide", d3.forceCollide(35))
                            .force("center", d3.forceCenter(width / 2, height / 2))
                            .force("charge", d3.forceManyBody().strength(-200))
                            .force("link", d3.forceLink(data.links).id(d => d.id).distance(120));

                        mainSimulation.force("x", d3.forceX(d => d.clusterId === 'unlinked' ? width * 0.9 : width / 2).strength(0.08));
                        mainSimulation.force("y", d3.forceY(d => d.clusterId === 'unlinked' ? height * 0.9 : height / 2).strength(0.08));

                        const link = g.append("g").selectAll(".link").data(data.links).enter().append("line").attr("class", "link-visible");

                        const node = g.append("g").selectAll(".node").data(data.nodes).enter().append("g")
                            .attr("class", "node")
                            .on("mouseover", (e, d) => handleHover(d, true))
                            .on("mouseout", (e, d) => handleHover(d, false))
                            .on("click", (e, d) => openDrawer(d))
                            .call(d3.drag().on("start", dragStart).on("drag", dragging).on("end", dragEnd));

                        node.append("circle").attr("r", 15).attr("fill", d => TYPE_COLORS[d.type] || '#888');
                        node.append("text").attr("dy", -25).attr("text-anchor", "middle").text(d => d.name);

                        mainSimulation.on("tick", () => {
                            link.attr("x1", d => d.source.x).attr("y1", d => d.source.y).attr("x2", d => d.target.x).attr("y2", d => d.target.y);
                            node.attr("transform", d => `translate(` + d.x + `,` + d.y + `)`);
                        });
                    }

                    function handleHover(d, isHovering) {
                        if (isDrawerOpen) return; 
                        if (!isHovering) {
                            d3.selectAll('.node').classed('dimmed', false).classed('highlighted', false);
                            d3.selectAll('.link-visible').classed('dimmed', false).classed('highlighted', false);
                            return;
                        }
                        const connectedIds = new Set([d.id]);
                        const relatedLinks = new Set();
                        rawData.links.forEach(l => {
                            if (l.source.id === d.id || l.target.id === d.id) {
                                connectedIds.add(l.source.id); connectedIds.add(l.target.id); relatedLinks.add(l);
                            }
                        });
                        d3.selectAll('.node').classed('dimmed', n => !connectedIds.has(n.id)).classed('highlighted', n => connectedIds.has(n.id));
                        d3.selectAll('.link-visible').classed('dimmed', l => !relatedLinks.has(l)).classed('highlighted', l => relatedLinks.has(l));
                    }

                    function openDrawer(centerNode) {
                        isDrawerOpen = true;
                        document.getElementById('focus-drawer').classList.add('active');
                        d3.selectAll('.node').classed('dimmed', n => n.id !== centerNode.id);
                        d3.selectAll('.link-visible').classed('dimmed', true);

                        const neighbors = [centerNode];
                        const links = [];
                        rawData.links.forEach(l => {
                            if (l.source.id === centerNode.id) { neighbors.push(rawData.nodes.find(n => n.id === l.target.id)); links.push(l); }
                            if (l.target.id === centerNode.id) { neighbors.push(rawData.nodes.find(n => n.id === l.source.id)); links.push(l); }
                        });
                        renderStarDiagram(centerNode, neighbors, links);
                        triggerAI('file', centerNode.id);
                    }

                    function closeDrawer() {
                        isDrawerOpen = false;
                        document.getElementById('focus-drawer').classList.remove('active');
                        d3.selectAll('.node').classed('dimmed', false);
                        d3.selectAll('.link-visible').classed('dimmed', false);
                    }

                    function renderStarDiagram(center, nodes, links) {
                        d3.select("#mini-map-container svg").remove();
                        const container = document.getElementById('mini-map-container');
                        const w = container.clientWidth || 400; const h = container.clientHeight || 400;
                        const svg = d3.select("#mini-map-container").append("svg").attr("width", "100%").attr("height", "100%").append("g");

                        const centerX = w / 2; const centerY = h / 2; const radius = 120;
                        const nodePositions = new Map();
                        nodePositions.set(center.id, { x: centerX, y: centerY });
                        const satellites = nodes.filter(n => n.id !== center.id);
                        satellites.forEach((n, i) => {
                            const angle = i * (2 * Math.PI / satellites.length) - (Math.PI / 2);
                            nodePositions.set(n.id, { x: centerX + radius * Math.cos(angle), y: centerY + radius * Math.sin(angle) });
                        });

                        links.forEach(l => {
                            const s = nodePositions.get(l.source.id); const t = nodePositions.get(l.target.id);
                            if(!s || !t) return;
                            svg.append("line").attr("x1", s.x).attr("y1", s.y).attr("x2", t.x).attr("y2", t.y).attr("stroke", "transparent").attr("stroke-width", 20).style("cursor", "pointer").on("click", () => triggerAI('link', l.source.name + "|" + l.target.name));
                            svg.append("line").attr("x1", s.x).attr("y1", s.y).attr("x2", t.x).attr("y2", t.y).attr("stroke", "#3574f0").attr("stroke-width", 2).attr("marker-end", "url(#arrow-blue)");
                        });

                        nodes.forEach(n => {
                            const pos = nodePositions.get(n.id);
                            const g = svg.append("g").attr("transform", `translate(`+pos.x+`,`+pos.y+`)`).style("cursor", "pointer").on("click", () => triggerAI('file', n.id));
                            g.append("circle").attr("r", n.id===center.id ? 25 : 18).attr("fill", TYPE_COLORS[n.type] || '#888').attr("stroke", n.id===center.id ? "#fff" : "none").attr("stroke-width", 3);
                            g.append("text").attr("dy", n.id===center.id ? -35 : -25).attr("text-anchor", "middle").text(n.name).style("fill", "#fff").style("font-size", "12px");
                        });
                    }

                    function triggerAI(type, payload) {
                        currentType = type; currentPayload = payload;
                        document.getElementById('refresh-btn').disabled = false;
                        const title = type === 'link' ? "Connection Analysis" : "File Analysis";
                        document.getElementById('details-content').innerHTML = `<div style='text-align:center; padding-top:60px; color:#555;'><b>Analyzing Details...</b></div>`;
                        window.location.href = "explain:" + type + "|" + payload + "|cached";
                    }

                    function triggerRefresh() { if(currentPayload) window.location.href = "explain:" + currentType + "|" + currentPayload + "|force"; }
                    function updateSidebar(html) { document.getElementById('details-content').innerHTML = html; }
                    function dragStart(e, d) { if(!e.active) mainSimulation.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; }
                    function dragging(e, d) { d.fx = e.x; d.fy = e.y; }
                    function dragEnd(e, d) { if(!e.active) mainSimulation.alphaTarget(0); d.fx = null; d.fy = null; }
                </script>
            </body>
            </html>
        """.trimIndent()

        browser.loadHTML(htmlContent, "http://globalmap/index.html")

        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean): Boolean {
                val url = request?.url ?: return false
                if (url.startsWith("openfile:")) { structureService.openFile(url.substring("openfile:".length)); return true }
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
        val refreshBtn = JButton("Reset Map")
        refreshBtn.addActionListener {
            val json = structureService.getSmartStructureJson()
            browser.cefBrowser.executeJavaScript("updateGraph($json)", browser.cefBrowser.url, 0)
        }
        panel.add(refreshBtn, BorderLayout.NORTH)
        panel.add(browser.component, BorderLayout.CENTER)

        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }
}
