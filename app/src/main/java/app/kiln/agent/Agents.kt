package app.kiln.agent

import app.kiln.build.Project
import java.io.File

/**
 * A kind of helper agent Kiln can spawn: its instructions, which tools it may use, and the tag its
 * steps carry in the chat. Every helper runs the same way (a headless AgentLoop whose steps stream
 * into the chat under [tag]); adding a type is adding a spec, here or as a project file.
 */
data class AgentSpec(
    /** What `subagent(type = …)` takes: "qa", "explore", "reviewer", or a project agent's file name. */
    val name: String,
    /** Its label on every step in the chat ("QA"). */
    val tag: String,
    /** When to use it — listed in the subagent tool's description. */
    val description: String,
    /** Its instructions. */
    val prompt: String,
    /** The tools it may use; null = every read-only tool. Helpers never get ask_user or other helpers. */
    val tools: Set<String>? = null,
    /** Append the kit reference and index to [prompt] (agents that read or judge app code). */
    val kitDocs: Boolean = false,
    /** Its answer must contain this; if it stops without it, it is told [unfinished] (twice at most). */
    val finished: Regex? = null,
    val unfinished: String = "",
)

object Agents {
    /** What the QA agent may use: look at and operate the app, read the code, never change it. */
    private val QA_TOOLS = setOf("launch", "screenshot", "ui_tree", "tap", "type_text", "swipe", "press_key", "wait_for",
        "test_flow", "logcat", "last_crash", "ui_check", "read_file", "list_dir", "grep")

    val QA = AgentSpec(
        name = "qa", tag = "QA",
        description = "tests the running app on the device against done criteria and reports PASS/FAIL per criterion (qa_check uses it)",
        prompt = "You are a strict QA tester for an Android app on this phone. You did not build it. " +
            "Start with launch, then exercise the app to check each done criterion: tap by visible label, type, swipe, wait_for, " +
            "test_flow for whole journeys, and read the screen (ui_tree / screenshot). A criterion passes only if you observed it. " +
            "Also note crashes (last_crash) and anything broken you see on the way. " +
            // A run's edit screen passed QA with its text drawn over the list beneath it, a near-black title, "750.0" for
            // 750 and "₹1000750.00" for every amount: QA checked the text was there, not that the screen looked right.
            "Look at every screen you reach, not just the text in it: take a screenshot and fail the criterion if layers " +
            "overlap or show through (text over other text, a form drawn over a list), if any text is unreadable, or if a value " +
            "shown isn't exactly what was entered (typed 750 must show as 750 or ₹750.00 — not 750.0, not ₹1000750.00). " +
            "Run ui_check on each screen you reach. Reply with one line per criterion: " +
            "PASS or FAIL — criterion — what you saw. End with exactly one line: VERDICT: PASS (every criterion passed) or VERDICT: FAIL.",
        tools = QA_TOOLS,
        finished = Regex("VERDICT:\\s*(PASS|FAIL)"),
        unfinished = "You stopped before finishing. Use the tools now to test every criterion on the device, then end " +
            "with one line per criterion and the final line VERDICT: PASS or VERDICT: FAIL.",
    )

    val EXPLORE = AgentSpec(
        name = "explore", tag = "Explore",
        description = "a read-only investigation of the project (\"find every screen that reads the todos store and summarise how\"); keeps long reading out of your context",
        prompt = "You are a focused investigator helping an Android engineer. Use the read-only tools to answer the task " +
            "precisely and briefly; report facts with file:line references. Do not speculate.",
        kitDocs = true,
    )

    val REVIEWER = AgentSpec(
        name = "reviewer", tag = "Review",
        description = "reviews the app's code for bugs, crashes and broken flows before you call it done, and lists concrete problems with file:line",
        prompt = "You are a careful code reviewer for an Android app written with Jetpack Compose and the Kiln kit. Read the " +
            "project's source and find real problems: crashes, data that isn't saved or loaded, state that doesn't update the UI, " +
            "flows the user asked for that are missing or broken, wrong kit usage. Don't report style. Reply with a numbered list: " +
            "file:line — the problem — the fix. If you find nothing, say so.",
        kitDocs = true,
    )

    val builtIn = listOf(QA, EXPLORE, REVIEWER)

    /** Built-ins plus the project's own agents (.kiln/agents/<name>.md); a project agent can't replace a built-in. */
    fun all(project: Project): List<AgentSpec> =
        builtIn + File(project.kilnDir, "agents").listFiles { f -> f.extension == "md" }.orEmpty().sortedBy { it.name }
            .mapNotNull { f -> runCatching { parse(f.nameWithoutExtension, f.readText()) }.getOrNull() }
            .filter { p -> builtIn.none { it.name == p.name } }

    /**
     * A project agent file:
     * ```
     * ---
     * tag: Copy
     * description: rewrites the app's text to be clear and friendly
     * tools: read_file, list_dir, grep
     * ---
     * You are a UX writer. …
     * ```
     * `tools` is optional (default: every read-only tool); the body is its instructions.
     */
    fun parse(name: String, text: String): AgentSpec? {
        val m = Regex("""(?s)^\s*---\s*\n(.*?)\n---\s*\n(.*)$""").find(text) ?: return null
        val head = m.groupValues[1].lines().mapNotNull { l -> l.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val prompt = m.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: return null
        val id = name.lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        return AgentSpec(
            name = id, tag = head["tag"]?.takeIf { it.isNotBlank() } ?: name.replaceFirstChar { it.uppercase() },
            description = head["description"].orEmpty().ifBlank { "project agent $id" }, prompt = prompt,
            tools = head["tools"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() },
            kitDocs = head["kit_docs"]?.toBooleanStrictOrNull() ?: false,
        )
    }
}
