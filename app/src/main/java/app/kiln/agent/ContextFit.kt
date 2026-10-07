package app.kiln.agent

import app.kiln.core.str
import app.kiln.llm.Msg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps a request inside the model's context window without touching the stored transcript.
 *
 * Old tool outputs (file dumps, build logs, UI trees, screenshots) are what fills a session; once
 * they've been acted on, the first line is enough. When the estimate passes [threshold] of the
 * window, tool results older than the last [keepRecent] messages are replaced by that line,
 * oldest first, until it fits. Server-side context editing (Anthropic) does the same remotely;
 * this is what keeps local and OpenAI-compatible models (32–64k windows) alive.
 */
object ContextFit {
    private const val CHARS_PER_TOKEN = 3.5
    private const val IMAGE_TOKENS = 1_600

    fun estimateTokens(messages: List<Msg>, system: String): Int {
        var chars = system.length.toLong(); var images = 0
        fun walk(e: Any?) {
            when (e) {
                is JsonObject -> { if (e.str("type") == "image") images++ else e.values.forEach { walk(it) } }
                is JsonArray -> e.forEach { walk(it) }
                is JsonPrimitive -> if (e.isString) chars += e.content.length
            }
        }
        messages.forEach { walk(it.content) }
        return (chars / CHARS_PER_TOKEN).toInt() + images * IMAGE_TOKENS
    }

    fun fit(messages: List<Msg>, system: String, window: Int, threshold: Double = 0.7, keepRecent: Int = 8): List<Msg> {
        val budget = (window * threshold).toInt()
        if (estimateTokens(messages, system) <= budget) return messages
        val out = messages.toMutableList()
        // Old outputs first; if recent ones alone are too big (small local windows), those too —
        // but always keep the newest result, which the model is about to act on.
        for (keep in listOf(keepRecent, 2)) if (clear(out, system, budget, keep)) break
        return out
    }

    /** Clears tool results older than the last [keep] messages until under [budget]; true when it fits. */
    private fun clear(out: MutableList<Msg>, system: String, budget: Int, keep: Int): Boolean {
        val cutoff = (out.size - keep).coerceAtLeast(0)
        for (i in 0 until cutoff) {
            val m = out[i]
            if (m.role != "user" || m.content.none { (it as? JsonObject)?.str("type") == "tool_result" }) continue
            out[i] = m.copy(content = JsonArray(m.content.map { b ->
                val o = b as? JsonObject
                if (o?.str("type") != "tool_result" || firstLine(o).startsWith(CLEARED)) b
                else JsonObject(o + ("content" to JsonPrimitive("$CLEARED " + firstLine(o))))
            }))
            if (estimateTokens(out, system) <= budget) return true
        }
        return estimateTokens(out, system) <= budget
    }

    private const val CLEARED = "[cleared to fit the context window]"

    private fun firstLine(result: JsonObject): String {
        val c = result["content"]
        val text = (c as? JsonPrimitive)?.content
            ?: (c as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.str("text") } ?: ""
        return text.lineSequence().firstOrNull()?.take(160) ?: ""
    }

}
