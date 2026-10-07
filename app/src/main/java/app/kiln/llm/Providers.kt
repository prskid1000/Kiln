package app.kiln.llm

import app.kiln.core.KJ
import app.kiln.core.arrOf
import app.kiln.core.obj
import app.kiln.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Which profile + model a role uses (SPEC §8.5). */
@Serializable
data class RoleBinding(val profile: String, val model: String, val fallback: List<String> = emptyList())

/**
 * Provider profiles (data), their secrets, role routing, and the capability
 * probe. Profiles live in files/providers.json; keys in [Secrets].
 */
class Providers(dir: File, val secrets: Secrets) {
    private val file = File(dir, "providers.json")
    private val rolesFile = File(dir, "roles.json")

    var profiles: List<Profile> = load()
        private set

    var roles: Map<String, RoleBinding> = loadRoles()
        private set

    private fun load(): List<Profile> = runCatching {
        KJ.decodeFromString(ListSerializer(Profile.serializer()), file.readText())
    }.getOrElse { PRESETS }

    private fun loadRoles(): Map<String, RoleBinding> = runCatching {
        KJ.decodeFromString(kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.serializer<String>(), RoleBinding.serializer()), rolesFile.readText())
    }.getOrElse { mapOf("agent" to RoleBinding("anthropic", "claude-opus-5-5"),
                        "subagent" to RoleBinding("anthropic", "claude-sonnet-5-5")) }

    fun save(p: Profile) {
        profiles = profiles.filter { it.id != p.id } + p
        file.writeText(KJ.encodeToString(ListSerializer(Profile.serializer()), profiles))
    }

    fun remove(id: String) {
        profiles = profiles.filter { it.id != id }
        file.writeText(KJ.encodeToString(ListSerializer(Profile.serializer()), profiles))
        secrets.put("key-$id", null)
    }

    fun setRole(role: String, binding: RoleBinding) {
        roles = roles + (role to binding)
        rolesFile.writeText(KJ.encodeToString(kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.serializer<String>(), RoleBinding.serializer()), roles))
    }

    fun profile(id: String) = profiles.firstOrNull { it.id == id }
    fun key(id: String) = secrets.get("key-$id")
    fun setKey(id: String, key: String?) = secrets.put("key-$id", key)

    /** One adapter (HTTP client, connection pool) per profile + key, reused across steps. */
    private val adapters = java.util.concurrent.ConcurrentHashMap<String, Adapter>()
    fun adapter(p: Profile): Adapter = adapters.getOrPut("${p.hashCode()}:${key(p.id).hashCode()}") { newAdapter(p) }

    private fun newAdapter(p: Profile): Adapter = when (p.protocol) {
        Protocol.ANTHROPIC -> AnthropicAdapter(p, key(p.id))
        Protocol.OPENAI_CHAT -> OpenAIChatAdapter(p, key(p.id))
        Protocol.OPENAI_RESPONSES -> OpenAIResponsesAdapter(p, key(p.id))
    }

    /** "profile:model" or a role binding → (profile, model). */
    fun resolve(spec: String): Pair<Profile, String>? {
        val (pid, model) = spec.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        val p = profile(pid) ?: return null
        return p to (model ?: p.models.firstOrNull() ?: return null)
    }

    // ---- capability probe (SPEC §8.4) ----

    data class ProbeReport(val caps: Caps, val models: List<String>, val notes: List<String>, val prices: Map<String, Price> = emptyMap())

    /** model id → $/million tokens, from OpenRouter's public model list (prices there are $/token). */
    private fun openRouterPrices(): Map<String, Price> {
        val body = java.net.URL("https://openrouter.ai/api/v1/models").openStream().use { it.readBytes().decodeToString() }
        val data = (app.kiln.core.parseJson(body) as JsonObject)["data"] as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
        return data.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val pr = o["pricing"] as? JsonObject ?: return@mapNotNull null
            fun d(k: String) = (pr[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.times(1_000_000)
            val inP = d("prompt") ?: return@mapNotNull null
            val outP = d("completion") ?: return@mapNotNull null
            o.str("id")!! to Price(inP, outP, d("input_cache_read") ?: inP * 0.1, d("input_cache_write") ?: inP * 1.25)
        }.toMap()
    }

    suspend fun probe(p: Profile, model: String): ProbeReport = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        val a = adapter(p)
        val models = runCatching { a.listModels() }.getOrDefault(emptyList())
        notes += if (models.isEmpty()) "GET /v1/models: nothing listed" else "GET /v1/models: ${models.size} models"
        val echo = ToolSpec("echo", "Echo a value back. Use it when asked.", obj("type" to "object",
            "properties" to obj("value" to obj("type" to "string")), "required" to arrOf(listOf("value")),
            "additionalProperties" to false))
        fun req(text: String, tools: List<ToolSpec> = listOf(echo), extra: List<Msg> = emptyList()) = ModelRequest(
            model, "You are a connectivity probe. Follow instructions exactly and briefly.",
            extra + Msg("user", arrOf(listOf(obj("type" to "text", "text" to text)))), tools, maxTokens = 2048)

        var caps = p.caps
        val tools = runCatching { a.stream(req("Call the echo tool once with value \"a\".")) {} }
            .onFailure { notes += "tool call failed: ${it.message}" }.getOrNull()
        caps = caps.copy(tools = tools?.content?.any { (it as? JsonObject)?.str("type") == "tool_use" } == true)
        notes += "tools: ${caps.tools}"

        if (caps.tools) {
            val par = runCatching { a.stream(req("Call the echo tool twice in parallel, with \"a\" and \"b\".")) {} }.getOrNull()
            val n = par?.content?.count { (it as? JsonObject)?.str("type") == "tool_use" } ?: 0
            caps = caps.copy(parallelTools = n >= 2); notes += "parallel tool calls: ${n >= 2}"
        }
        // 1×1 PNG: does the endpoint accept images at all?
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val vis = runCatching {
            a.stream(ModelRequest(model, "Reply with one word.", listOf(Msg("user", arrOf(listOf(
                obj("type" to "image", "source" to obj("type" to "base64", "media_type" to "image/png", "data" to png)),
                obj("type" to "text", "text" to "What colour is this pixel?"))))), emptyList(), maxTokens = 256)) {}
        }
        caps = caps.copy(vision = vis.isSuccess); notes += "vision: ${vis.isSuccess}"

        if (p.protocol == Protocol.ANTHROPIC || caps.caching) {
            val big = "Kiln cache probe. " + "lorem ipsum dolor sit amet ".repeat(800)
            val r1 = runCatching { a.stream(ModelRequest(model, big, listOf(Msg("user", arrOf(listOf(obj("type" to "text", "text" to "ok?"))))), emptyList(), maxTokens = 64)) {} }.getOrNull()
            val r2 = runCatching { a.stream(ModelRequest(model, big, listOf(Msg("user", arrOf(listOf(obj("type" to "text", "text" to "ok?"))))), emptyList(), maxTokens = 64)) {} }.getOrNull()
            val cached = (r2?.usage?.cacheRead ?: 0) > 0 || (r1?.usage?.cacheWrite ?: 0) > 0
            caps = caps.copy(caching = cached); notes += "prompt caching: $cached"
        }
        // OpenRouter publishes per-model prices: fill them in so costs (and caps) are real, not $0.
        val prices = if ("openrouter.ai" in p.baseUrl) runCatching { openRouterPrices() }.getOrDefault(emptyMap()) else emptyMap()
        if (prices.isNotEmpty()) notes += "prices: ${prices.size} models priced from OpenRouter"
        ProbeReport(caps, models, notes, prices)
    }

    /** Fetch an On Device AI style `GET /certificate` and return (sha256, pem). */
    suspend fun fetchCertificate(baseUrl: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val root = Http.rootBase(baseUrl)
        // The certificate endpoint is unauthenticated; the tailnet already authenticated the host.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        val client = okhttp3.OkHttpClient.Builder().sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }.build()
        val pem = client.newCall(Request.Builder().url("$root/certificate").build()).execute().use { it.body.string() }
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
        Http.sha256(cert) to pem
    }

    companion object {
        private val anthropicCaps = Caps(tools = true, parallelTools = true, strictTools = true, vision = true,
            caching = true, midSystem = true, thinking = true, effort = true, compaction = true, contextEditing = true,
            serverWebSearch = true, fallbacks = true, taskBudget = true, contextWindow = 1_000_000, maxOutput = 64_000)

        val PRESETS = listOf(
            Profile("anthropic", "Anthropic", Protocol.ANTHROPIC, "https://api.anthropic.com", AuthStyle.X_API_KEY,
                models = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-4-5"),
                caps = anthropicCaps, price = Price(4.0, 20.0, 0.20, 5.0)),
            Profile("openai", "OpenAI", Protocol.OPENAI_RESPONSES, "https://api.openai.com/v1", AuthStyle.BEARER,
                caps = Caps(strictTools = true, caching = true, effort = true, thinking = true, contextWindow = 400_000, maxOutput = 64_000)),
            Profile("openrouter", "OpenRouter", Protocol.OPENAI_CHAT, "https://openrouter.ai/api/v1", AuthStyle.BEARER,
                headers = mapOf("HTTP-Referer" to "https://kiln.local", "X-Title" to "Kiln"),
                models = listOf("anthropic/claude-opus-5-5", "anthropic/claude-sonnet-5-5"),
                caps = Caps(effort = true, thinking = true, contextWindow = 1_000_000, maxOutput = 64_000)),
            Profile("ondevice", "On Device AI (tailnet)", Protocol.ANTHROPIC, "https://100.64.0.1:8443", AuthStyle.BEARER,
                caps = Caps(parallelTools = false, vision = true, contextWindow = 32_000, maxOutput = 8_000), price = Price(0.0, 0.0, 0.0, 0.0)),
            Profile("telecode", "Telecode proxy (PC)", Protocol.ANTHROPIC, "http://100.64.0.2:1235", AuthStyle.NONE,
                caps = Caps(contextWindow = 64_000, maxOutput = 16_000), price = Price(0.0, 0.0, 0.0, 0.0)),
            Profile("openai-compatible", "OpenAI-compatible", Protocol.OPENAI_CHAT, "http://127.0.0.1:8080/v1", AuthStyle.NONE,
                price = Price(0.0, 0.0, 0.0, 0.0)),
            Profile("anthropic-compatible", "Anthropic-compatible", Protocol.ANTHROPIC, "http://127.0.0.1:8080", AuthStyle.BEARER,
                price = Price(0.0, 0.0, 0.0, 0.0)),
        )
    }
}
