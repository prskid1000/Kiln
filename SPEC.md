# Kiln — SPEC

**Build Android apps on the phone, with an AI agent, and install them directly.**

> **Working name.** `Kiln` is a placeholder (same convention as Vessel).

Kiln is an agent harness that runs on Android. You describe an app; a Claude
agent writes it, compiles it **on the phone**, installs it through
[Warden](../Warden) without a prompt, opens it, reads the screen and the logs,
and fixes what it sees — in a loop, until the app works.

---

## 1. Principles

1. **The harness is the product.** The model is fixed; what we control is the
   tools, their outputs, the loop, and the context. Published harness work shows
   the same model gaining ~7 points on a coding benchmark from harness changes
   alone ([Weng, 2026](https://lilianweng.github.io/posts/2026-07-04-harness/)).
2. **Tools are data, not code** (same rule as On Device AI §1.5). Most new tools
   are a JSON entry; Kotlin is only needed for tools with real logic.
3. **Verify, don't trust.** "It compiled" is not done. Done = installed, launched,
   no crash in logcat, and the screen matches the request.
4. **Enforce in code, not in the prompt.** Which packages may be touched, what
   needs approval, output size limits — all enforced by the tool implementation.
5. **Append-only conversation.** Never edit or delete earlier turns (it breaks
   prompt caching and, on current models, invalidates thinking blocks). Trimming
   happens through the API's context editing and compaction, not client-side.

## 2. Architecture

```
┌──────────────────────────── Kiln app ────────────────────────────┐
│ UI (Compose, Nocturne): chat · activity feed · files · device    │
├──────────────────────────────────────────────────────────────────┤
│ Agent loop ── Hooks ── Approval gate ── Budget/cost meter        │
│     │                                                            │
│ Providers: anthropic-messages · openai-chat · openai-responses   │
│     │                                                            │
│ Tool registry ── Kotlin tools · JSON command tools · MCP tools   │
├───────────────┬───────────────────────────┬──────────────────────┤
│ Workspace     │ Build engine              │ Device bridge        │
│ files·git·mem │ aapt2·kotlinc/ecj·d8·apksig│ Warden binder        │
└───────────────┴───────────────────────────┴──────────────────────┘
```

## 3. Agent loop

- **Model:** any profile from §8; the default is Claude Opus 5.5
  (`claude-opus-5-5`) through the official Anthropic Java SDK (works from Kotlin). Adaptive thinking; **effort set per phase**: `high`
  for planning/fixing, `medium` for routine edits. Server-side `fallbacks`
  enabled for refusals.
- **Streaming always**, with `eager_input_streaming` on client tools so large
  file writes stream in; each parsed tool input is validated against its schema
  before running.
- **Parallel tool calls:** read-only tools marked `parallelSafe` run concurrently;
  all results go back in **one** user message. Failed tools return
  `is_error: true`, never dropped.
- **Stop reasons handled explicitly:** `tool_use`, `end_turn`, `max_tokens`
  (continue), `pause_turn`, `refusal` (show category, offer fallback).
- **Limits:** max steps per run (default 80), retries per tool (2), wall-clock
  timeout per tool, and an advisory **task budget** so the model paces itself.
- **Cancel** at any point; the in-flight tool gets a cancellation signal and
  returns a result so history stays valid.
- **Hooks** (configurable): `preTool` (block/modify), `postTool` (e.g. after
  `edit_file` → run `check`), `onTurnEnd`, `onBuildFail`. Hooks are the main
  place to encode "always do X after Y" without prompt text.

### Context management
| Layer | What | Why |
|---|---|---|
| Prompt cache | Frozen system prompt + **deterministically sorted** tool list first; automatic caching on the tail | Prefix must be byte-identical turn to turn |
| State injection | Mid-conversation `system` messages (build status, current file tree) | Updates without invalidating the cache |
| Context editing | Server-side clearing of old tool results (`clear_tool_uses`) | Logcat/build output goes stale fast |
| Compaction | Server-side compaction near the limit | Long sessions |
| Memory | `PROJECT.md` + memory tool dir per project | Survives sessions: decisions, conventions, known issues |
| Output spill | Tool outputs over a cap (default 8k tokens) are cut to head+tail and the full text saved to `.kiln/out/<id>.txt` | The agent can `grep`/`read` the rest on demand |

Changing the tool set mid-session breaks the cache, so config changes apply to
the **next** session; with many tools, use **tool search** (deferred loading).

## 4. Tool system (pluggable)

### 4.1 Tool contract
```kotlin
interface Tool {
    val name: String
    val description: String          // written for the model: when to use, what it returns
    val inputSchema: JsonObject      // strict: true, additionalProperties: false
    val traits: Set<Trait>           // ReadOnly, ParallelSafe, Destructive, NeedsApproval,
                                     // NeedsBroker, ReturnsImage, LongRunning
    val timeout: Duration
    suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult
}
// ToolResult = text | image(s) | file refs, plus isError and structured `data` for the UI
```
`ToolContext` gives workspace, build engine, device bridge, cancellation,
progress reporting (shown live in the activity feed), and the output spiller.

### 4.2 Three ways to add a tool
1. **Kotlin tool** — implement `Tool`, add to the registry (Hilt multibinding).
2. **Command tool (no code)** — a JSON entry in `tools.d/*.json`:
   ```json
   { "name": "battery_stats", "description": "Battery usage of the app under test.",
     "command": "dumpsys batterystats {{package}}", "runAs": "broker",
     "params": { "package": { "type": "string" } },
     "traits": ["ReadOnly"], "maxOutputTokens": 4000 }
   ```
   Parameters are substituted with shell quoting; `runAs` is `app` or `broker`.
3. **MCP tools** — connect any MCP server (stdio or HTTP); its tools are added
   with a server prefix and the same traits/approval rules.

Each tool can be enabled/disabled per project; the registry rejects duplicate
names and validates schemas at startup.

### 4.3 Approval policy
Per tool: `allow` · `ask` · `deny`, defaults by trait (`Destructive` → `ask`).
"Allow for this session" is available. Approval renders as a modal with the
typed arguments (why dedicated tools beat a single `shell` tool).

## 5. Tool catalog (v1)

**Files & workspace**
`list_dir` · `glob` · `grep` (regex, context lines) · `read_file` (line ranges,
line numbers) · `write_file` · `edit_file` (exact replace; **rejects if the file
changed since last read**) · `multi_edit` · `move` · `delete`

**Project**
`create_project` (from templates: Views/Java, Views/Kotlin, Compose) ·
`project_info` (package, SDKs, libraries, entry points) · `add_library` (from the
bundled set only) · `set_app_meta` (name, icon, permissions)

**Build**
`check` (fast: compile only, no packaging) · `build` (incremental APK) · `clean`.
Errors come back **structured**: `[{file, line, col, severity, message, code}]`
plus the source line — not raw compiler output.

**Device (via Warden)**
`install` (silent `pm install`) · `uninstall` · `launch` · `stop` · `clear_data` ·
`grant_permission` · `logcat` (filter by app/pid/level/tag, **since a marker** so
only new lines return) · `last_crash` (newest FATAL stack trace, mapped to
project files) · `screenshot` (downscaled image, optional region) · `ui_tree`
(compact uiautomator dump: id, text, bounds, clickable) · `tap` / `type` /
`swipe` / `key` (**target by id/text**, coordinates only as fallback) ·
`wait_for` (element/text with timeout) · `dumpsys` (allow-listed services) ·
`shell` (gated, `ask` by default)

**Verify**
`run_tests` (JUnit compiled to dex, run on the device) · `ui_check` (scripted
steps + assertions in one call) · `screen_diff` (compare with the last screenshot)

**Knowledge**
`sdk_lookup` (class/method signatures from the bundled `android.jar` and
libraries — stops invented APIs) · `docs_search` (offline Android docs index) ·
`web_search` / `web_fetch` (Anthropic server tools)

**Agent**
`plan` / `todo` (visible checklist) · `ask_user` (multiple choice, blocks the
loop) · `checkpoint` / `restore` (git snapshot of the project) · `memory` ·
`subagent` (cheaper model for log reading, review, or research; returns a summary)

## 6. Build engine

Pipeline per project, no Gradle:
`aapt2 compile/link` → R.java → **kotlinc** (or **ECJ** for Java) → **d8** →
package → zipalign → **apksig** (per-project key in Android Keystore).

- **Warm compiler daemon:** kotlinc runs in a long-lived process so the JIT warm-up
  is paid once — the single biggest speed win.
- **Incremental:** per-file hashes; recompile changed sources and their
  dependents, re-dex only changed classes, reuse the resource table when
  resources didn't change.
- **Bundled libraries:** a pinned set (AndroidX core/appcompat/lifecycle,
  Material, Room, Coroutines; Compose in phase 2) pre-dexed and shipped **inside
  the APK** as toolchain components (jdk, native, tools, kotlinc, sdk, kit,
  templates; one APK flavour per ABI). On launch each component whose content
  hash isn't installed is unpacked, verified file by file and moved into place;
  the active toolchain is a set of links switched atomically, and old versions
  are pruned. No import step, no network. No arbitrary Maven resolution in v1.
- **16 KB-aligned** native libraries in output APKs; generated apps target the
  current SDK.
- **Generated apps live in their own namespace** (`kiln.app.<name>`) so device
  tools can be restricted to Kiln-built packages.

Prior art proving each step runs on a phone: AndroidIDE / Android Code Studio
(full Gradle on device), Sketchware Pro (aapt2 + ECJ/kotlinc + d8 + apksigner),
CodeAssist (on-device Kotlin analysis and Compose preview).

## 7. Device bridge

Warden's binder (`Warden.bind()`, `newProcess`) runs device tools as shell:
silent install, `input`, `screencap`, `uiautomator`, `logcat` for other apps.

**Without Warden** the harness still works, degraded: install through
`PackageInstaller` (user confirms each time), no other-app logcat, no input
injection. Tools declare `NeedsBroker`; when Warden isn't running they're hidden
from the model and the UI says why. Kiln checks Warden's state at session start.

## 8. Model connection (providers)

Kiln speaks **three wire protocols** and connects to any endpoint that serves one
of them. Which endpoints exist is configuration, not code.

### 8.1 Wire protocols (adapters)
| Adapter | Path | Typical endpoints |
|---|---|---|
| `anthropic-messages` | `POST /v1/messages` (+ `count_tokens`) | Claude API, On Device AI proxy, Telecode proxy |
| `openai-chat` | `POST /v1/chat/completions` | OpenRouter, On Device AI proxy, Telecode proxy, llama.cpp, Ollama, LM Studio, vLLM, Groq… |
| `openai-responses` | `POST /v1/responses` | OpenAI (recommended there for new work, and the only route to its reasoning items and built-in tools), Telecode passthrough |

All three share `GET /v1/models` for discovery. Gemini (`/v1beta/models/*`) can
be a fourth adapter later; Telecode proves the shape.

### 8.2 Canonical transcript
The internal format is **Anthropic-shaped** (content blocks: text, image,
thinking, tool_use with ids, tool_result with images, cache markers) because it is
the richest and Claude is the primary target. Adapters convert *down*:
- Tool results with images → OpenAI `role:"tool"` text + the image lifted into a
  following user message (OpenAI tool messages are text-only; Telecode does the same).
- Tool schemas → OpenAI `function` format; names checked against
  `^[a-zA-Z0-9_-]{1,64}$` at registration so no adapter has to rename.
- `cache_control` → kept (Anthropic, OpenRouter's Anthropic models) or dropped.
- Effort → `output_config.effort` / `reasoning_effort` / OpenRouter `reasoning.effort`.

**Opaque provider state is kept verbatim per turn** — Claude thinking blocks,
compaction blocks, OpenAI reasoning items — and replayed only to the provider
that produced it. A history is append-only *per provider segment*: switching
provider/model mid-session starts a new segment (provider state dropped, visible
messages kept), and the UI says so.

### 8.3 Provider profiles (data)
```json
{ "id": "openrouter", "label": "OpenRouter", "protocol": "openai-chat",
  "baseUrl": "https://openrouter.ai/api/v1",
  "auth": { "style": "bearer", "keyRef": "keystore:openrouter" },
  "headers": { "HTTP-Referer": "https://kiln.local", "X-Title": "Kiln" },
  "models": "discover", "caps": { "override": {} } }
```
- **Base URL normalisation:** accept with or without `/v1` and a trailing slash
  (Telecode's lesson: Anthropic SDKs append `/v1`, OpenAI ones expect it in the
  base — a profile must work either way).
- **Auth styles:** `x-api-key`, `bearer`, `none`. Keys live in the Android Keystore,
  never in config files or logs.
- **Built-in presets:** Anthropic · OpenAI · OpenRouter · On Device AI (tailnet) ·
  Telecode proxy (PC over tailnet, port 1235) · generic OpenAI-compatible ·
  generic Anthropic-compatible.
- **Self-signed TLS** (On Device AI serves its own certificate): fetch
  `GET /certificate`, show the SHA-256, the user confirms, Kiln **pins** it.
  Cleartext HTTP is allowed only to loopback and the tailnet range `100.64.0.0/10`
  (network security config), never to the open internet.

### 8.4 Capabilities: probed, not assumed
On "Test connection" Kiln calls `/v1/models`, then sends tiny **functional
probes** (Telecode's `llama_caps` idea): a tool call, two parallel tool calls, an
image input, a cached prefix sent twice, a mid-conversation system message. The
results fill the profile's capability flags; the user can override any of them.

| Capability | Used for | If missing, the harness… |
|---|---|---|
| tools | everything | refuses the profile for the agent role |
| parallel tool calls | speed | runs tools one at a time |
| strict schemas | valid tool input | validates client-side, returns `is_error` to the model |
| vision | `screenshot` | `screenshot` returns `ui_tree` text instead |
| prompt caching | cost | sends no cache markers |
| mid-conversation system | state injection | sends a `<system-reminder>` text block in the user turn |
| server compaction / context editing | long runs | client-side: masks old tool results, then summarises into a new segment (allowed only where no provider state would be invalidated) |
| reasoning/effort | quality/cost | omits the field |
| server tools (web search) | `web_search` | uses Kiln's own fetch tool, or hides it |
| context window, max output | budgeting | read from `/models` metadata or the probe |

### 8.5 Roles and routing
Each **role** picks its own profile + model: `agent` (main loop), `subagent`
(log reading, review), `summarizer` (compaction), `vision` (screenshot judging).
Example: Claude Opus 5.5 as `agent`, a local model on On Device AI as
`subagent`. One model per role keeps each role's prompt cache intact.
**Fallback chain** per role (e.g. Claude API → OpenRouter's Claude → local);
a fallback is a new segment (8.2).

### 8.6 Streaming, errors, usage
- One normalised event stream for every adapter: text delta, thinking delta,
  tool-call start / argument delta / end, usage, stop.
- Stop reasons mapped: `tool_use`/`tool_calls`, `end_turn`/`stop`,
  `max_tokens`/`length`, `refusal`/`content_filter`.
- Retries on 429 (honouring `retry-after`), 5xx/overloaded and dropped
  connections — common on a phone moving between Wi-Fi and mobile data. A turn is
  appended to history only when complete, so a retry re-sends the same request.
- Usage normalised to input / output / cache read / cache write; prices from
  OpenRouter's `/models` pricing, a built-in table for Anthropic/OpenAI, or set by
  hand for local endpoints (cost 0).
- Long runs keep going with the screen off: the loop runs in a foreground
  service, using On Device AI's §18.7 lessons on restarts.

### 8.7 The other direction: Kiln as a server
Kiln can expose its **tool registry as an MCP server** over the tailnet (off by
default, bearer token, same rules as On Device AI §18.3). Then Claude Code or
Codex on the PC — or Telecode's agents — can drive builds on the phone: write
files, build, install, screenshot, read logcat. Same tools, different brain.

## 9. Safety

- Device tools refuse packages outside `kiln.app.*` (enforced in code).
- `shell`, `uninstall`, `clear_data`, web access: `ask` by default.
- Content from logs, web pages and app screens is **data, not instructions** —
  wrapped as tool results; the system prompt says so.
- Spend caps per session and per day; the run stops when reached.
- Sideload distribution (an app that builds and installs apps won't fit Play
  policy).

## 10. Observability

Full transcript per session (JSONL), per-tool timings, token and cost meter
(cache read vs write vs output), build durations, replay of a session, export as
a zip. Same append-only, hash-chained idea as Warden's audit log.

## 11. UI

Single page like Warden/Sundown: tabs **Chat · Files · Device**.
- **Chat:** messages + activity feed (each tool call is a card: args, result,
  duration, image thumbnails), stop button, budget meter, approval modals.
- **Files:** tree + editor (read-only while the agent runs).
- **Device:** latest screenshot, logcat tail, installed Kiln apps.

## 12. Configuration (`.kiln/config.json` per project, global defaults)
```json
{ "roles": { "agent": { "profile": "anthropic", "model": "claude-opus-5-5",
                          "fallback": ["openrouter:anthropic/claude-opus-5-5"] },
             "subagent": { "profile": "ondevice", "model": "auto" } },
  "effort": { "plan": "high", "edit": "medium" },
  "limits": { "maxSteps": 80, "taskBudgetTokens": 200000, "dailyUsd": 5 },
  "tools": { "disabled": ["web_fetch"], "approval": { "shell": "ask" } },
  "hooks": { "postTool:edit_file": ["check"] },
  "mcpServers": [], "commandToolsDir": "tools.d" }
```

## 13. Evals

A fixed set of app prompts (counter, todo list with Room, timer with
notification, camera preview, settings screen, …). Per harness change, record:
build success, launches without crash, task judged complete (screenshot +
rubric), turns, tokens, cost, wall time. Harness changes ship only if the
numbers improve.

## 14. Milestones (by risk)

| # | Goal | Proves |
|---|---|---|
| M0 | Hand-run aapt2 + ECJ + d8 + apksig on the phone; install via Warden | The toolchain runs on this device |
| M1 | Agent loop + `anthropic-messages` adapter + file tools + Java/Views build + structured errors | Write → build → fix loop |
| M2 | Device tools (install, launch, logcat, screenshot, ui_tree, tap) | Verify loop on a real screen |
| M3 | kotlinc daemon + incremental builds + bundled libraries | Kotlin at usable speed |
| M4 | Command tools, MCP, hooks, approvals, config; `openai-chat` + `openai-responses` adapters, capability probes, roles/fallbacks | Extensibility, any provider |
| M5 | Compose (compiler plugin + runtime) | Modern UI apps |
| M6 | Evals, cost tuning, Kiln as an MCP server | Quality, cost, drive from the PC |

## 15. Risks

| Risk | Mitigation |
|---|---|
| kotlinc too slow/heavy on device | Daemon, incremental; Java/ECJ path stays available |
| Compose compiler plugin on ART | M5 is late on purpose; Views first |
| Toolchain size (~260 MB) | Bundled per ABI; an update unpacks only changed components |
| Model invents APIs | `sdk_lookup` + compile errors fed back structured |
| Runaway cost | Task budget, caps, caching, context editing, effort per phase |
| Warden not running | Degraded mode with clear UI; tools hidden, not failing |

---

### Sources
- [AndroidIDE / Android Code Studio — building on device](https://www.mintlify.com/AndroidCSOfficial/android-code-studio/guides/building-apps)
- [APK Builder — full toolchain on a phone](https://timeout.userpage.fu-berlin.de/apk-builder)
- [d8](https://developer.android.com/tools/d8) · [aapt2](https://developer.android.com/tools/aapt2?authuser=1)
- [Sketchware Pro build process](https://gsdms.csir.co.za/drop/sketchware-pro-fixing-r8-bug)
- [CodeAssist 3.0.1 — on-device Kotlin + Compose preview](https://www.apkmirror.com/?p=14393656)
- [Compose compiler plugin](https://mvnrepository.com/artifact/androidx.compose.compiler/compiler-hosted)
- [adb-mcp — device tools for agents](https://mcpservers.org/servers/iksnerd/adb-mcp) · [mobile-device-mcp](https://github.com/srmorete/mobile-device-mcp)
- [OpenRouter API](https://www.codewords.ai/blog/https-openrouter-ai-api-v1-chat-completions) · [OpenRouter schema extensions](https://skills.sh/thatjuan/agent-skills/openrouter-api) · [Migrate to the Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses.md)
- On Device AI `SPEC.md` §18 (proxy surface, TLS, access) · Telecode `CLAUDE.md` (proxy pipeline, translate.py, capability probes)
- [Harness Engineering for Self-Improvement](https://lilianweng.github.io/posts/2026-07-04-harness/) · [Building AI Coding Agents for the Terminal](https://arxiv.org/html/2603.05344v1) · [The Devil Is in the Interface](https://arxiv.org/pdf/2608.11386) · [awesome-harness-engineering](https://github.com/ai-boost/awesome-harness-engineering)
