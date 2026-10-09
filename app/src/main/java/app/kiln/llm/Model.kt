package app.kiln.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Wire protocol a profile speaks (SPEC §8.1). */
@Serializable
enum class Protocol { ANTHROPIC, OPENAI_CHAT, OPENAI_RESPONSES }

@Serializable
enum class AuthStyle { X_API_KEY, BEARER, NONE }

/**
 * What an endpoint can do — probed on "Test connection", overridable by hand.
 * The harness degrades per flag instead of failing (SPEC §8.4).
 */
@Serializable
data class Caps(
    val tools: Boolean = true,
    val parallelTools: Boolean = true,
    val strictTools: Boolean = false,
    val vision: Boolean = true,
    val caching: Boolean = false,
    val midSystem: Boolean = false,
    val thinking: Boolean = false,
    val effort: Boolean = false,
    val compaction: Boolean = false,
    val contextEditing: Boolean = false,
    val serverWebSearch: Boolean = false,
    val fallbacks: Boolean = false,
    val taskBudget: Boolean = false,
    val contextWindow: Int = 200_000,
    val maxOutput: Int = 32_000,
)

/** A configured endpoint (SPEC §8.3). The API key lives in [app.kiln.llm.Secrets], never here. */
@Serializable
data class Profile(
    val id: String,
    val label: String,
    val protocol: Protocol,
    val baseUrl: String,
    val auth: AuthStyle = AuthStyle.BEARER,
    val headers: Map<String, String> = emptyMap(),
    val models: List<String> = emptyList(),
    val caps: Caps = Caps(),
    /** SHA-256 (hex) of a pinned self-signed certificate (On Device AI), if any. */
    val pinnedCertSha256: String? = null,
    /** $ per million tokens: input, output, cache read, cache write. 0 = free/local. */
    val price: Price? = null,
    /** Per-model prices (e.g. from OpenRouter's /models); beats [price] for that model. */
    val modelPrices: Map<String, Price> = emptyMap(),
)

@Serializable
data class Price(val input: Double, val output: Double, val cacheRead: Double = input * 0.1, val cacheWrite: Double = input * 1.25)

/** USD per million tokens assumed for a model with no known price (on the high side, for the caps). */
val ESTIMATE_PRICE = Price(5.0, 25.0)

/** A tool as the model sees it. */
@Serializable
data class ToolSpec(val name: String, val description: String, val schema: JsonObject, val strict: Boolean = false)

/**
 * One transcript entry, Anthropic-shaped content blocks (SPEC §8.2).
 * [origin] is "profileId|model" for assistant turns; [providerState] holds
 * opaque provider data (OpenAI reasoning items) replayed only to that origin.
 * role is user | assistant | system (mid-conversation operator message).
 */
@Serializable
data class Msg(
    val role: String,
    val content: JsonArray,
    val origin: String? = null,
    val providerState: JsonObject? = null,
    val ts: Long = System.currentTimeMillis(),
)

data class ModelRequest(
    val model: String,
    val system: String,
    val messages: List<Msg>,
    val tools: List<ToolSpec>,
    val maxTokens: Int = 32_000,
    val effort: String? = null,
    /** Advisory total for the whole run (task budget), when supported. */
    val taskBudgetTokens: Int? = null,
    /** Anthropic server tools to declare (e.g. web search) when caps allow. */
    val serverTools: List<JsonObject> = emptyList(),
)

sealed interface ModelEvent {
    data class Text(val delta: String) : ModelEvent
    data class Thinking(val delta: String) : ModelEvent
    data class ToolStart(val id: String, val name: String) : ModelEvent
    data class ToolArgs(val id: String, val partial: String) : ModelEvent
    data class Status(val message: String) : ModelEvent
}

enum class Stop { TOOL_USE, END_TURN, MAX_TOKENS, REFUSAL, PAUSE_TURN, OTHER }

@Serializable
data class Usage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite)
    // No known price (a provider without one): counted at a conservative estimate, so the spending caps still stop a
    // run — at $0 they never could. Local providers set a price of 0.
    fun cost(p: Price?): Double = (p ?: ESTIMATE_PRICE).let { p ->
        (input * p.input + output * p.output + cacheRead * p.cacheRead + cacheWrite * p.cacheWrite) / 1_000_000.0 }
}

data class ModelTurn(
    val content: JsonArray,
    val stop: Stop,
    val usage: Usage,
    val providerState: JsonObject? = null,
    val refusal: String? = null,
)

class ProviderException(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause)

/**
 * An error a provider sent inside the stream (OpenRouter relays upstream failures this way, after
 * a 200). Overloaded, rate-limited and server-side failures are worth retrying after a pause.
 */
fun streamError(e: kotlinx.serialization.json.JsonObject, raw: String): ProviderException {
    val msg = (e["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: raw
    val codeText = (e["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val code = codeText.toIntOrNull()
    // Codes may be numbers (429, 503) or names (OpenAI's "server_error", "rate_limit_exceeded").
    val transient = code == 429 || (code != null && code >= 500) ||
        Regex("server|rate.?limit|overload", RegexOption.IGNORE_CASE).containsMatchIn(codeText) ||
        Regex("overload|temporar|rate.?limit|capacity|try again|retry|timeout|unavailable", RegexOption.IGNORE_CASE).containsMatchIn(msg)
    return ProviderException(msg, retryable = transient)
}

/** A wire protocol implementation. */
interface Adapter {
    suspend fun stream(req: ModelRequest, onEvent: (ModelEvent) -> Unit): ModelTurn
    suspend fun listModels(): List<String> = emptyList()
}

/** The price for [model] on this profile: its own entry, else the profile price, else unknown (null). */
fun Profile.priceFor(model: String): Price? = modelPrices[model] ?: price ?: if (isLocal()) Price(0.0, 0.0, 0.0, 0.0) else null

/** A model on this phone, the PC over adb/loopback, or the tailnet costs nothing per token. */
fun Profile.isLocal(): Boolean {
    val host = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: return false
    return host == "localhost" || host.startsWith("127.") || host.startsWith("100.") || host.endsWith(".ts.net")
}
