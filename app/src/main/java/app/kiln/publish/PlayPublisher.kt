package app.kiln.publish

import app.kiln.core.KJ
import app.kiln.core.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Uploads a bundle to a Play track (internal testing by default) with the Play Developer API,
 * authenticated as a Google Cloud service account that has been invited in Play Console.
 *
 * Play only accepts uploads for an app that already exists in Play Console with this package
 * name — the very first bundle has to be uploaded there by hand.
 */
class PlayPublisher(private val serviceAccountJson: String, private val http: OkHttpClient = DEFAULT_HTTP) {
    class PlayException(message: String) : Exception(message)

    private val account: JsonObject = runCatching { KJ.parseToJsonElement(serviceAccountJson).jsonObject }
        .getOrElse { throw PlayException("That isn't a service-account JSON key file") }
    val email: String = account.str("client_email") ?: throw PlayException("The key file has no client_email")

    /** Upload [bundle] and release it to [track]; returns the version code Play recorded. */
    fun upload(pkg: String, bundle: File, track: String = "internal", notes: String? = null, onStep: (String) -> Unit = {}): Long {
        onStep("Signing in to Google Play")
        val token = accessToken()
        val base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$pkg"
        onStep("Starting an edit")
        val edit = call(token, Request.Builder().url("$base/edits").post("{}".toRequestBody(JSON))).str("id")
            ?: throw PlayException("Play didn't start an edit")
        onStep("Uploading ${bundle.name}")
        val uploaded = call(token, Request.Builder()
            .url("https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$pkg/edits/$edit/bundles?uploadType=media")
            .post(bundle.asRequestBody("application/octet-stream".toMediaType())))
        val code = uploaded["versionCode"]?.toString()?.trim('"')?.toLongOrNull() ?: throw PlayException("Play didn't accept the bundle")
        onStep("Releasing to $track")
        fun release(status: String) = buildJsonObject {
            put("track", track)
            put("releases", buildJsonArray {
                add(buildJsonObject {
                    put("versionCodes", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(code.toString())) })
                    put("status", status)
                    if (!notes.isNullOrBlank()) put("releaseNotes", buildJsonArray {
                        add(buildJsonObject { put("language", "en-US"); put("text", notes.take(500)) })
                    })
                })
            })
        }.toString()
        try {
            call(token, Request.Builder().url("$base/edits/$edit/tracks/$track").put(release("completed").toRequestBody(JSON)))
        } catch (e: PlayException) {
            // An app that has never been published can only hold draft releases.
            if ("draft" !in (e.message ?: "")) throw e
            call(token, Request.Builder().url("$base/edits/$edit/tracks/$track").put(release("draft").toRequestBody(JSON)))
        }
        onStep("Committing")
        call(token, Request.Builder().url("$base/edits/$edit:commit").post("".toRequestBody(null)))
        return code
    }

    /** OAuth access token for the service account (JWT bearer grant). */
    private fun accessToken(): String {
        val now = System.currentTimeMillis() / 1000
        val header = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64(buildJsonObject {
            put("iss", email); put("scope", "https://www.googleapis.com/auth/androidpublisher")
            put("aud", TOKEN_URL); put("iat", now); put("exp", now + 3600)
        }.toString().toByteArray())
        val pem = account.str("private_key") ?: throw PlayException("The key file has no private_key")
        val der = Base64.getMimeDecoder().decode(pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----"))
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$header.$claims".toByteArray()) }.sign()
        val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$header.$claims.${b64(sig)}"
        val r = http.newCall(Request.Builder().url(TOKEN_URL).post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()).execute()
        val body = r.use { it.body.string() }
        if (!r.isSuccessful) throw PlayException("Google sign-in failed: " + errorText(body))
        return KJ.parseToJsonElement(body).jsonObject.str("access_token") ?: throw PlayException("Google sign-in returned no token")
    }

    private fun call(token: String, b: Request.Builder): JsonObject {
        val r = http.newCall(b.header("Authorization", "Bearer $token").build()).execute()
        val body = r.use { it.body.string() }
        if (!r.isSuccessful) throw PlayException("Play (${r.code}): " + errorText(body))
        return runCatching { KJ.parseToJsonElement(body.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    companion object {
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private val JSON = "application/json".toMediaType()
        private val DEFAULT_HTTP = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES).build()
        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        /** Google's error JSON → its message; anything else → the first bit of the body. */
        fun errorText(body: String): String = runCatching {
            val o = KJ.parseToJsonElement(body).jsonObject
            (o["error"] as? JsonObject)?.str("message") ?: o.str("error_description") ?: o.str("error")
        }.getOrNull() ?: body.take(300)
    }
}
