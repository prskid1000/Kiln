package app.kiln.kit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * A Supabase backend: email sign-in and table rows over Supabase's REST API.
 *
 * ```
 * val db = KSupabase(context, url = "https://xyz.supabase.co", anonKey = AppSecrets.SUPABASE_ANON_KEY)
 * db.signIn(email, password)
 * val notes: List<Note> = db.table("notes").select<Note>("select=*&order=created_at.desc")
 * db.table("notes").insert(Note(text = "hi"))
 * db.table("notes").update("id=eq.$id", buildJsonObject { put("text", "edited") })
 * db.table("notes").delete("id=eq.$id")
 * ```
 * Only the anon (public) key belongs in an app; protect rows with Row Level Security in Supabase.
 * The signed-in session is kept on the device and refreshed when it expires.
 */
/** Inserts leave out nulls, so the server fills its own columns (`val id: Long? = null`, created_at) — real values, defaults included, are sent. */
private val insertJson = kotlinx.serialization.json.Json(KJson) { explicitNulls = false }

class KSupabase(context: Context, private val url: String, private val anonKey: String) {
    private val prefs = context.getSharedPreferences("kiln_supabase", Context.MODE_PRIVATE)
    private val _user = MutableStateFlow(prefs.getString("user_id", null))
    /** The signed-in user's id, or null. */
    val userId: StateFlow<String?> = _user

    private val base = url.trimEnd('/')
    private val json = "application/json".toMediaType()

    suspend fun signUp(email: String, password: String) = auth("$base/auth/v1/signup", email, password)
    suspend fun signIn(email: String, password: String) = auth("$base/auth/v1/token?grant_type=password", email, password)

    fun signOut() { prefs.edit().clear().apply(); _user.value = null }

    private suspend fun auth(endpoint: String, email: String, password: String) {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString()
        save(KJson.parseToJsonElement(send(Request.Builder().url(endpoint).post(body.toRequestBody(json)), authed = false)).jsonObject)
    }

    private fun save(o: JsonObject) {
        val access = o["access_token"]?.jsonPrimitive?.content ?: return   // sign-up awaiting email confirmation
        prefs.edit().putString("access", access).putString("refresh", o["refresh_token"]?.jsonPrimitive?.content)
            .putString("user_id", (o["user"] as? JsonObject)?.get("id")?.jsonPrimitive?.content).apply()
        _user.value = prefs.getString("user_id", null)
    }

    private suspend fun refresh(): Boolean {
        val token = prefs.getString("refresh", null) ?: return false
        val body = buildJsonObject { put("refresh_token", token) }.toString()
        return runCatching {
            save(KJson.parseToJsonElement(send(Request.Builder().url("$base/auth/v1/token?grant_type=refresh_token")
                .post(body.toRequestBody(json)), authed = false, retry = false)).jsonObject)
        }.isSuccess
    }

    internal suspend fun send(b: Request.Builder, authed: Boolean = true, retry: Boolean = true): String = withContext(Dispatchers.IO) {
        val token = if (authed) prefs.getString("access", null) ?: anonKey else anonKey
        val r = KNet.client.newCall(b.header("apikey", anonKey).header("Authorization", "Bearer $token").build()).execute()
        val text = r.use { it.body.string() }
        if (r.code == 401 && authed && retry && refresh()) return@withContext send(b, authed, retry = false)
        if (!r.isSuccessful) throw IOException("Supabase ${r.code}: " +
            (runCatching { KJson.parseToJsonElement(text).jsonObject.let { o -> (o["message"] ?: o["msg"] ?: o["error_description"])?.jsonPrimitive?.content } }.getOrNull() ?: text.take(200)))
        text
    }

    fun table(name: String) = Table(name)

    inner class Table(private val name: String) {
        private fun at(query: String) = "$base/rest/v1/$name" + if (query.isBlank()) "" else "?$query"

        /** Rows matching a PostgREST query, e.g. "select=*&done=eq.false&order=created_at.desc". */
        suspend fun <T> select(serializer: KSerializer<T>, query: String = "select=*"): List<T> =
            KJson.decodeFromString(ListSerializer(serializer), send(Request.Builder().url(at(query)).get()))
        suspend inline fun <reified T> select(query: String = "select=*"): List<T> = select(KJson.serializersModule.serializer<T>(), query)

        /** Insert a row; returns the stored row (with server defaults such as id). */
        suspend fun <T> insert(serializer: KSerializer<T>, row: T): T =
            KJson.decodeFromString(ListSerializer(serializer), send(Request.Builder().url(at(""))
                .header("Prefer", "return=representation")
                .post(insertJson.encodeToString(serializer, row).toRequestBody(json)))).first()
        suspend inline fun <reified T> insert(row: T): T = insert(KJson.serializersModule.serializer<T>(), row)

        /** Change the rows matching [filter] (e.g. "id=eq.42") to have these fields. */
        suspend fun update(filter: String, fields: JsonElement) {
            send(Request.Builder().url(at(filter)).patch(fields.toString().toRequestBody(json)))
        }

        suspend fun delete(filter: String) { send(Request.Builder().url(at(filter)).delete()) }
    }
}
