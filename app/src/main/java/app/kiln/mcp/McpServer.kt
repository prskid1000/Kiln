package app.kiln.mcp

import android.util.Log
import app.kiln.Graph
import app.kiln.tools.SessionState
import app.kiln.build.Project
import app.kiln.core.compact
import app.kiln.core.obj
import app.kiln.core.parseJson
import app.kiln.core.str
import app.kiln.tools.Tool
import app.kiln.tools.ToolContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Kiln's tool registry as an MCP server (SPEC §8.7), so Claude Code or Codex on
 * a PC can drive builds on the phone: same tools, different brain.
 *
 * Streamable HTTP, JSON responses only. Binds to the phone's tailnet address
 * (100.64.0.0/10) — never to Wi-Fi or mobile — falling back to loopback.
 * A bearer token is required (shown in Settings). Tools act on a project named
 * by the `project` argument added to every tool.
 */
object McpServer {
    val status = MutableStateFlow("stopped")
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = HashMap<String, SessionState>()

    val token: String
        get() {
            val s = Graph.secrets
            return s.get("mcp-token") ?: Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) }).also { s.put("mcp-token", it) }
        }

    private fun bindAddress(): InetAddress {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { a ->
                val b = a.address
                if ((b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xff) in 64..127) return a
            }
        }
        return InetAddress.getLoopbackAddress()
    }

    fun start(port: Int) {
        stop()
        val addr = bindAddress()
        val ss = runCatching { ServerSocket(port, 16, addr) }.getOrElse { status.value = "failed: ${it.message}"; return }
        socket = ss
        status.value = "http://${addr.hostAddress}:$port/mcp · token ${token.take(6)}…"
        job = scope.launch {
            while (isActive) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                launch { runCatching { serve(c) }.onFailure { Log.w("Kiln", "mcp", it) }; runCatching { c.close() } }
            }
        }
    }

    fun stop() { runCatching { socket?.close() }; job?.cancel(); socket = null; status.value = "stopped" }

    private fun serve(c: Socket) {
        val input = BufferedInputStream(c.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return; head.append(b.toChar()) }
        val lines = head.lines()
        val (method, path) = lines.first().split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).lowercase() to l.substring(it + 1).trim() } }.toMap()
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len).also { var n = 0; while (n < len) { val r = input.read(it, n, len - n); if (r < 0) break; n += r } }
        val out = c.getOutputStream()
        fun respond(code: Int, json: String?) {
            val bytes = (json ?: "").toByteArray()
            out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()); out.write(bytes); out.flush()
        }
        val auth = headers["authorization"]?.removePrefix("Bearer ")?.trim()
        if (auth == null || !MessageDigest.isEqual(auth.toByteArray(), token.toByteArray())) return respond(401, """{"error":"unauthorized"}""")
        if (method != "POST" || !path.startsWith("/mcp")) return respond(404, """{"error":"POST /mcp"}""")
        val req = parseJson(String(body)) as JsonObject
        val id = req["id"]
        if (id == null) return respond(202, null)   // notification
        val result: JsonElement = try { handle(req.str("method") ?: "", req["params"] as? JsonObject ?: obj()) }
            catch (e: Throwable) { return respond(200, obj("jsonrpc" to "2.0", "id" to id, "error" to obj("code" to -32000, "message" to (e.message ?: "error"))).compact()) }
        respond(200, obj("jsonrpc" to "2.0", "id" to id, "result" to result).compact())
    }

    private fun handle(method: String, params: JsonObject): JsonElement = when (method) {
        "initialize" -> obj("protocolVersion" to "2025-06-18", "capabilities" to obj("tools" to obj()),
            "serverInfo" to obj("name" to "kiln", "version" to "0.1"),
            "instructions" to "Kiln builds Android apps on this phone. Every tool takes `project` (a Kiln project name). " +
                "Kit reference: call kit_docs.")
        "ping" -> obj()
        "tools/list" -> obj("tools" to JsonArray(tools().map { t ->
            val props = (t.schema["properties"] as? JsonObject ?: obj()).toMutableMap()
            props["project"] = obj("type" to "string", "description" to "Kiln project name (Projects folder).")
            val req = ((t.schema["required"] as? JsonArray)?.toList() ?: emptyList()) + JsonPrimitive("project")
            obj("name" to t.name, "description" to t.description,
                "inputSchema" to obj("type" to "object", "properties" to JsonObject(props), "required" to JsonArray(req)))
        }))
        "tools/call" -> {
            val name = params.str("name") ?: error("missing name")
            val args = params["arguments"] as? JsonObject ?: obj()
            val projectName = args.str("project") ?: error("missing project")
            val project = Project(File(Graph.paths.projects, projectName)).also { require(it.metaFile.isFile) { "no project $projectName" } }
            val tool = tools().firstOrNull { it.name == name } ?: error("unknown tool $name")
            val r = runBlocking { tool.run(ctx(project), JsonObject(args - "project")) }
            obj("content" to JsonArray(listOf(obj("type" to "text", "text" to r.text)) + r.images.map {
                obj("type" to "image", "mimeType" to "image/png", "data" to Base64.getEncoder().encodeToString(it)) }),
                "isError" to r.isError)
        }
        else -> error("unsupported method $method")
    }

    /** No ask_user / subagent / approvals over MCP: the remote client is the agent and the user. */
    private fun tools(): List<Tool> = runBlocking {
        val any = Graph.paths.projects.listFiles()?.firstOrNull { File(it, "kiln.json").isFile }
        if (any == null) emptyList() else Graph.kiln.tools(Project(any)).second
            .filter { it.name !in setOf("ask_user", "subagent", "shell") }
    }

    private fun ctx(project: Project) = object : ToolContext {
        override val project = project
        override val sessionId = "mcp"
        override val state = synchronized(states) { states.getOrPut(project.name) { SessionState() } }
        override val spillDir = File(Graph.paths.spill, "mcp").apply { mkdirs() }
        override fun progress(line: String) {}
        override suspend fun ask(question: String, options: List<String>) = "no user available over MCP; decide yourself"
        override fun spill(text: String, maxChars: Int): String = if (text.length <= maxChars) text else
            text.take(maxChars) + "\n… [${text.length - maxChars} chars truncated]"
    }
}
