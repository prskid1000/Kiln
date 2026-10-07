package app.kiln.publish

import app.kiln.build.Project
import app.kiln.core.KJ
import app.kiln.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Pushes a project's source to a GitHub repository with the REST API (no git binary): one
 * commit per sync holding the project exactly as it is now. Build output and Kiln's own state
 * (.kiln — signing key, chats' memory) never leave the phone.
 */
class GitHubSync(private val token: String, private val http: OkHttpClient = DEFAULT_HTTP) {
    class GitHubException(message: String) : Exception(message)

    fun login(): String = get("/user").str("login") ?: throw GitHubException("GitHub didn't say who this token belongs to")

    /** A new private repository under the token's account, with a first commit so it has a branch. */
    fun createRepo(name: String, description: String): String {
        val r = send("POST", "/user/repos", buildJsonObject {
            put("name", name); put("description", description.take(300)); put("private", true); put("auto_init", true)
        })
        return r.str("full_name") ?: throw GitHubException("GitHub didn't create the repository")
    }

    /** Commit the project to [repo] ("owner/name"); returns the commit URL, or null when nothing changed. */
    fun push(project: Project, repo: String, message: String, onStep: (String) -> Unit = {}): String? {
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) { "Repository must look like owner/name" }
        onStep("Reading $repo")
        val info = get("/repos/$repo")
        val branch = info.str("default_branch") ?: "main"
        var ref = runCatching { get("/repos/$repo/git/ref/heads/$branch") }.getOrNull()
        if (ref == null) {
            // An empty repository has no branch yet, and the Git Data API refuses to work on it.
            send("PUT", "/repos/$repo/contents/.gitkeep", buildJsonObject { put("message", "Start"); put("content", "") ; put("branch", branch) })
            ref = get("/repos/$repo/git/ref/heads/$branch")
        }
        val parent = (ref["object"] as JsonObject).str("sha")!!
        val baseTree = (get("/repos/$repo/git/commits/$parent")["tree"] as JsonObject).str("sha")!!
        val remote = (get("/repos/$repo/git/trees/$baseTree?recursive=1")["tree"] as? JsonArray).orEmpty()
            .map { it.jsonObject }.filter { it.str("type") == "blob" }.associate { it.str("path")!! to it.str("sha")!! }

        val files = project.files().filter { !project.rel(it).startsWith(".kiln/") }
        val entries = buildJsonArray {
            for ((i, f) in files.withIndex()) {
                val path = project.rel(f)
                val bytes = f.readBytes()
                val sha = gitBlobSha(bytes)
                if (remote[path] != sha) {
                    onStep("Uploading ${i + 1}/${files.size}: $path")
                    send("POST", "/repos/$repo/git/blobs", buildJsonObject {
                        put("content", Base64.getEncoder().encodeToString(bytes)); put("encoding", "base64")
                    })
                }
                add(buildJsonObject { put("path", path); put("mode", "100644"); put("type", "blob"); put("sha", sha) })
            }
        }
        // Same paths, same contents: nothing to commit.
        val local = entries.associate { (it as JsonObject).str("path")!! to it.str("sha")!! }
        if (local == remote) return null
        onStep("Committing")
        val tree = send("POST", "/repos/$repo/git/trees", buildJsonObject { put("tree", entries) }).str("sha")!!
        val commit = send("POST", "/repos/$repo/git/commits", buildJsonObject {
            put("message", message); put("tree", tree); put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        })
        send("PATCH", "/repos/$repo/git/refs/heads/$branch", buildJsonObject { put("sha", commit.str("sha")!!) })
        return commit.str("html_url")
    }

    private fun get(path: String): JsonObject = exec(Request.Builder().url(API + path).get())

    private fun send(method: String, path: String, body: JsonObject): JsonObject =
        exec(Request.Builder().url(API + path).method(method, body.toString().toRequestBody(JSON)))

    private fun exec(b: Request.Builder): JsonObject {
        val r = http.newCall(b.header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build()).execute()
        val body = r.use { it.body.string() }
        if (!r.isSuccessful) throw GitHubException("GitHub (${r.code}): " +
            (runCatching { KJ.parseToJsonElement(body).jsonObject.str("message") }.getOrNull() ?: body.take(200)))
        return runCatching { KJ.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    companion object {
        private const val API = "https://api.github.com"
        private val JSON = "application/json".toMediaType()
        private val DEFAULT_HTTP = OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).build()

        /** The id git gives a file's contents: SHA-1 of "blob <size>\u0000" + bytes. */
        fun gitBlobSha(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${bytes.size}".toByteArray()); md.update(0); md.update(bytes)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
