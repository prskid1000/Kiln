package app.kiln.kit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/** Every kit service called for real on the device (no fakes). */
@RunWith(AndroidJUnit4::class)
class ServicesOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Serializable data class Note(val title: String, val body: String = "")
    @Serializable data class Post(val id: Int, val title: String)
    @Serializable data class NewPost(val title: String)

    @Test fun kstorePersistsAcrossInstances() {
        val name = "t_store_" + System.nanoTime()
        val a = KStore(ctx, name, listOf<Note>())
        a.update { it + Note("one") }
        a.set(a.value + Note("two"))
        Thread.sleep(400)                                     // writes are async
        val b = KStore(ctx, name, listOf<Note>())
        assertEquals(listOf("one", "two"), b.value.map { it.title })
    }

    @Test fun kcollectionCrudQueryAndReload() {
        val name = "t_coll_" + System.nanoTime()
        val c = KCollection<Note>(ctx, name)
        val id1 = c.add(Note("Milk"))
        val id2 = c.add(Note("Eggs", "a dozen"))
        assertEquals(2, c.size)
        assertEquals("Eggs", c.rows.value.first().value.title)          // newest first
        c.update(id1, Note("Oat milk"))
        c.modify(id2) { it.copy(body = "six") }
        assertEquals("Oat milk", c.get(id1)?.title)
        assertEquals("six", c.get(id2)?.body)
        assertEquals(listOf(id1), c.query { it.title.contains("milk", true) }.map { it.id })
        val reopened = KCollection<Note>(ctx, name)
        assertEquals(2, reopened.size)
        reopened.delete(id1)
        assertEquals(listOf("Eggs"), reopened.items.map { it.title })
        reopened.clear()
        assertEquals(0, reopened.size)
        val oldestFirst = KCollection<Note>(ctx, name + "_asc", newestFirst = false)
        oldestFirst.add(Note("a")); oldestFirst.add(Note("b"))
        assertEquals(listOf("a", "b"), oldestFirst.items.map { it.title })
    }

    object Repo { val items = KCollection<Note>("t_noctx_" + System.nanoTime()); val prefs = KStore("t_noctx_store", Note("x")) }

    @Test fun storesNeedNoContext() {
        val id = Repo.items.add(Note("from a plain object"))
        assertEquals("from a plain object", Repo.items.get(id)?.title)
        assertEquals("x", Repo.prefs.value.title)
    }

    @Test fun prefsReadWrite() {
        KPrefs.of(ctx).edit().putInt("t_runs", 7).commit()
        assertEquals(7, KPrefs.of(ctx).getInt("t_runs", 0))
    }

    @Test fun filesReadWriteListShare() {
        KFiles.writeText(ctx, "t/notes/a.txt", "hello")
        KFiles.writeBytes(ctx, "t/notes/b.bin", byteArrayOf(1, 2, 3))
        assertEquals("hello", KFiles.readText(ctx, "t/notes/a.txt"))
        assertEquals(3, KFiles.readBytes(ctx, "t/notes/b.bin")?.size)
        assertEquals(listOf("a.txt", "b.bin"), KFiles.list(ctx, "t/notes").map { it.name })
        assertTrue(KFiles.exists(ctx, "t/notes/a.txt"))
        assertNull(KFiles.readText(ctx, "t/missing.txt"))
        // The FileProvider KFiles.share uses must be declared, or sharing crashes.
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".kiln.files", KFiles.file(ctx, "t/notes/a.txt"))
        assertEquals("hello", KFiles.readUriText(ctx, uri))
        val cached = KFiles.cacheFile(ctx, "t/c.txt").apply { writeText("x") }
        assertNotNull(FileProvider.getUriForFile(ctx, ctx.packageName + ".kiln.files", cached))
        assertTrue(KFiles.delete(ctx, "t"))
        assertTrue(!KFiles.exists(ctx, "t/notes/a.txt"))
    }

    @Test fun httpTypedVerbsErrorsAndRetry() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("""[{"id":1,"title":"Hi","extra":true}]""").build())
        server.enqueue(MockResponse.Builder().code(201).body("""{"id":2,"title":"New"}""").build())
        server.enqueue(MockResponse.Builder().code(404).body("not here").build())
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        server.enqueue(MockResponse.Builder().body("""{"id":3,"title":"After retry"}""").build())
        server.enqueue(MockResponse.Builder().code(204).build())
        server.start()
        try {
            val posts: List<Post> = KHttp.get(server.url("/posts").toString())
            assertEquals("Hi", posts.single().title)
            val created: Post = KHttp.post(server.url("/posts").toString(), NewPost("New"), headers = mapOf("Authorization" to "Bearer t"))
            assertEquals(2, created.id)
            server.takeRequest(); val req = server.takeRequest()
            assertEquals("POST", req.method); assertEquals("Bearer t", req.headers["Authorization"])
            assertEquals("""{"title":"New"}""", req.body?.utf8())
            try { KHttp.get<Post>(server.url("/missing").toString()); fail("404 should throw") }
            catch (e: KHttpException) { assertEquals(404, e.code); assertTrue(e.body.contains("not here")) }
            val retried: Post = KHttp.get(server.url("/flaky").toString())    // 503 then 200
            assertEquals("After retry", retried.title)
            KHttp.delete(server.url("/posts/1").toString())
        } finally { server.close() }
    }

    @Test fun networkStateAnswers() { KNetwork.isOnline(ctx) }

    @Test fun formatting() {
        assertEquals("$1,234.50", KFormat.money(1234.5, "USD", Locale.US))
        assertEquals("1.2K", KFormat.compact(1234)); assertEquals("3.4M", KFormat.compact(3_400_000)); assertEquals("999", KFormat.compact(999))
        assertEquals("05:09", KFormat.duration(309)); assertEquals("1:05:09", KFormat.duration(3909))
        val now = System.currentTimeMillis()
        assertEquals("just now", KFormat.relative(now - 5_000, now))
        assertEquals("5 min ago", KFormat.relative(now - 300_000, now))
        assertEquals("2 h ago", KFormat.relative(now - 7_200_000, now))
        assertEquals("yesterday", KFormat.relative(now - 90_000_000, now))
        assertEquals("3 days ago", KFormat.relative(now - 3 * 86_400_000L, now))
        assertEquals("8 Oct 2026", KFormat.date(LocalDate.of(2026, 10, 8)))
        assertTrue(KFormat.percent(0.25).contains("25"))
        assertTrue(KFormat.number(1234.567, 2).contains("234"))
    }

    @Test fun geo() {
        val berlin = KLatLng(52.52, 13.405); val paris = KLatLng(48.8566, 2.3522)
        val km = KGeo.distanceMeters(berlin, paris) / 1000
        assertTrue("Berlin–Paris was $km km", km in 870.0..885.0)
        assertEquals("850 m", KGeo.formatDistance(850.0)); assertEquals("12.4 km", KGeo.formatDistance(12_400.0))
        runBlocking { KGeo.find(ctx, "Brandenburg Gate, Berlin") }   // may be null without a geocoder; must not throw
    }

    @Test fun locationWithoutPermissionIsNull() = runBlocking {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            assertNull(KLocation.current(ctx))
    }

    @Test fun reminderRunsThroughWorkManager() {
        KReminder.schedule(ctx, "t_rem", Duration.ofSeconds(1), "Test", "Reminder fired")
        val wm = WorkManager.getInstance(ctx)
        val deadline = System.currentTimeMillis() + 60_000
        var state: WorkInfo.State? = null
        while (System.currentTimeMillis() < deadline) {
            state = wm.getWorkInfosForUniqueWork("kreminder-t_rem").get().firstOrNull()?.state
            if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED) break
            Thread.sleep(500)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, state)
        KReminder.scheduleAt(ctx, "t_rem2", 3, 0, "Later", "x")
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork("kreminder-t_rem2").get().first().state)
        KReminder.cancel(ctx, "t_rem2")
        Thread.sleep(300)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork("kreminder-t_rem2").get().first().state)
    }

    @Test fun mediaSaveAndLoad() {
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val uri = KMedia.saveToGallery(ctx, bmp, "kiln_test_${System.nanoTime()}")
        assertNotNull(uri)
        val back = KMedia.loadBitmap(ctx, uri!!)
        assertEquals(64, back?.width); assertEquals(Color.MAGENTA, back?.getPixel(10, 10))
        ctx.contentResolver.delete(uri, null, null)
    }

    @Test fun deviceHapticsNotifyAndClipboardDontThrow() {
        assertTrue(KDevice.batteryPercent(ctx) in 0..100)
        KDevice.isCharging(ctx); assertTrue(KDevice.model.isNotBlank())
        KHaptics.tick(ctx); KHaptics.success(ctx); KHaptics.error(ctx)
        KNotify.post(ctx, "Kit test", "Notification from the kit test")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { KClipboard.copy(ctx, "copied!") }
    }

    @Test fun speechRecognizerReportsAvailability() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val stt = KSpeechToText(ctx, Locale.US)
            stt.available                                   // true on phones with a recogniser; must not throw
            assertTrue(!stt.listening)
        }
    }
}
