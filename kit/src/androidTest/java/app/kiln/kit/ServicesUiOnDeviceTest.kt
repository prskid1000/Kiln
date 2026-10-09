package app.kiln.kit

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** The services that need an Activity, Compose, other apps or a permission — exercised on the device. */
@RunWith(AndroidJUnit4::class)
class ServicesUiOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Serializable data class Draft(val text: String, val n: Int)

    @Test fun storedStateAndPrefsSurviveRecomposition() {
        val name = "t_stored_" + System.nanoTime()
        lateinit var stored: MutableState<Draft>
        lateinit var pref: MutableState<Int>
        lateinit var flag: MutableState<Boolean>
        lateinit var text: MutableState<String>
        var gen by mutableIntStateOf(0)
        rule.setContent {
            if (gen >= 0) {                                       // re-created when gen changes
                androidx.compose.runtime.key(gen) {
                    stored = rememberStored(name, Draft("", 0)); pref = rememberPref("$name.count", 0)
                    flag = rememberPref("$name.flag", false); text = rememberPref("$name.text", "")
                    Text("draft=${stored.value.text} n=${pref.value}")
                }
            }
        }
        rule.runOnIdle { stored.value = Draft("hello", 2); pref.value = 5; flag.value = true; text.value = "hi" }
        rule.onNodeWithText("draft=hello n=5").assertExists()
        Thread.sleep(400)
        rule.runOnIdle { gen++ }                                    // brand-new state objects read from disk
        rule.onNodeWithText("draft=hello n=5").assertExists()
        rule.runOnIdle { assertEquals(true, flag.value); assertEquals("hi", text.value) }
        assertEquals(Draft("hello", 2), KStore(ctx, name, Draft("", 0)).value)
    }

    @Test fun clipboardRoundTripInForeground() {
        rule.setContent { Text("clip") }
        rule.runOnIdle { KClipboard.copy(rule.activity, "copied text") }
        rule.runOnIdle { assertEquals("copied text", KClipboard.paste(rule.activity)) }
    }

    @Test fun onlineNowAndLifecycleHooks() {
        var resumes = 0; var pauses = 0; var online: Boolean? = null; var ticks = mutableListOf<Long>()
        rule.setContent {
            KOnResume { resumes++ }; KOnPause { pauses++ }
            online = rememberOnline().value
            ticks += rememberNow(everyMillis = 200)
            Text("x")
        }
        rule.waitForIdle()
        assertEquals(KNetwork.isOnline(ctx), online)
        val r0 = resumes
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)    // pause
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)    // resume
        rule.waitForIdle()
        assertTrue("pauses=$pauses", pauses >= 1); assertTrue("resumes=$resumes r0=$r0", resumes > r0)
        rule.mainClock.advanceTimeBy(1000)                                  // compose tests run on a virtual clock
        assertTrue("clock ticked ${ticks.distinct().size} times", ticks.distinct().size >= 2)
    }

    /** Catches the next startActivity (blocking it, so no real app opens) and returns its intent. */
    private class Catch : Instrumentation.ActivityMonitor() {
        @Volatile var caught: Intent? = null
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            if (caught == null) caught = intent
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    private fun interceptAny(launch: () -> Unit): Intent {
        val m = Catch()
        inst.addMonitor(m)
        try {
            rule.runOnIdle(launch)
            val deadline = System.currentTimeMillis() + 5000
            while (m.caught == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            return m.caught ?: throw AssertionError("nothing was launched")
        } finally { inst.removeMonitor(m) }
    }

    @Test fun handOffsToOtherApps() {
        rule.setContent { Text("open") }
        val a = rule.activity
        interceptAny { KOpen.dial(a, "+15551234") }.let { assertEquals(Intent.ACTION_DIAL, it.action); assertEquals("tel:+15551234", it.dataString) }
        interceptAny { KOpen.sms(a, "555", "hi") }.let { assertEquals("smsto:555", it.dataString); assertEquals("hi", it.getStringExtra("sms_body")) }
        interceptAny { KOpen.email(a, "a@b.c", "Subj", "Body") }.let {
            assertEquals("mailto:", it.dataString); assertEquals("a@b.c", it.getStringArrayExtra(Intent.EXTRA_EMAIL)?.single()); assertEquals("Subj", it.getStringExtra(Intent.EXTRA_SUBJECT)) }
        interceptAny { KOpen.map(a, "Brandenburg Gate") }.let { assertEquals("geo:0,0?q=Brandenburg%20Gate", it.dataString) }
        interceptAny { KOpen.url(a, "https://example.com") }.let { assertEquals("https://example.com", it.dataString) }
        interceptAny { KOpen.calendarEvent(a, "Dentist", 1000L, 2000L, "Clinic") }.let {
            assertEquals(Intent.ACTION_INSERT, it.action); assertEquals("Dentist", it.getStringExtra(android.provider.CalendarContract.Events.TITLE)) }
        interceptAny { KOpen.appSettings(a) }.let { assertEquals("package:" + a.packageName, it.dataString) }
        interceptAny { KOpen.notificationSettings(a) }.let { assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, it.action) }
        interceptAny { KOpen.playStore(a, "com.example") }.let { assertEquals("market://details?id=com.example", it.dataString) }
        interceptAny { KOpen.shareText(a, "hello", "subject") }.let {
            assertEquals(Intent.ACTION_CHOOSER, it.action)
            val inner = it.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals("hello", inner.getStringExtra(Intent.EXTRA_TEXT)) }
        KFiles.writeText(a, "share/report.csv", "a,b\n1,2")
        interceptAny { KFiles.share(a, KFiles.file(a, "share/report.csv"), "text/csv") }.let {
            val inner = it.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            val uri = inner.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
            assertEquals("text/csv", inner.type); assertEquals("a,b\n1,2", KFiles.readUriText(a, uri)) }
    }

    @Test fun pickersOpenTheRightSystemScreens() {
        lateinit var pickDoc: () -> Unit; lateinit var save: (String) -> Unit; lateinit var pickImg: () -> Unit
        lateinit var pickImgs: () -> Unit; lateinit var shoot: () -> Boolean; lateinit var dictate: () -> Unit
        rule.setContent {
            pickDoc = rememberFilePicker(arrayOf("text/csv")) {}; save = rememberFileSaver("text/csv") {}
            pickImg = rememberImagePicker {}; pickImgs = rememberImagePicker(max = 3) {}
            shoot = rememberCameraShot {}; dictate = rememberDictation {}
            Column { Text("pickers") }
        }
        interceptAny { pickDoc() }.let { assertEquals(Intent.ACTION_OPEN_DOCUMENT, it.action); assertEquals("text/csv", it.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.single()) }
        interceptAny { save("export.csv") }.let { assertEquals(Intent.ACTION_CREATE_DOCUMENT, it.action); assertEquals("export.csv", it.getStringExtra(Intent.EXTRA_TITLE)) }
        interceptAny { pickImg() }.let { assertTrue(it.action, it.action == MediaStore.ACTION_PICK_IMAGES || it.action == Intent.ACTION_OPEN_DOCUMENT || it.action?.contains("PICK") == true) }
        interceptAny { pickImgs() }.let { assertTrue(it.action, it.action == MediaStore.ACTION_PICK_IMAGES || it.action == Intent.ACTION_OPEN_DOCUMENT || it.action?.contains("PICK") == true) }
        interceptAny { shoot() }.let { assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, it.action) }
        interceptAny { dictate() }.let { assertEquals(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH, it.action) }
    }

    @Test fun textToSpeechInitialisesAndSpeaks() {
        lateinit var speak: (String) -> Unit
        rule.setContent { speak = rememberSpeaker(); Text("tts") }
        // The engine binds asynchronously; once ready, speak() queues the utterance without error.
        Thread.sleep(2500)
        rule.runOnIdle { speak("Kiln test") }
        val tts = android.speech.tts.TextToSpeech(ctx) {}
        try { assertTrue("no TTS engine installed", tts.engines.isNotEmpty()) } finally { tts.shutdown() }
    }

    @Test fun notificationsAndRemindersReallyPost() {
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        KNotify.post(ctx, "Kit notify test", "Body text", id = 4242)
        Thread.sleep(500)
        assertTrue(nm.activeNotifications.any { it.id == 4242 && it.notification.extras.getString("android.title") == "Kit notify test" })
        KReminder.schedule(ctx, "t_rem_post", Duration.ofSeconds(1), "Reminder title", "Reminder body")
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && nm.activeNotifications.none { it.notification.extras.getString("android.title") == "Reminder title" }) Thread.sleep(500)
        assertTrue("reminder notification never appeared", nm.activeNotifications.any { it.notification.extras.getString("android.title") == "Reminder title" })
        assertEquals(WorkInfo.State.SUCCEEDED, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("kreminder-t_rem_post").get().first().state)
        nm.cancelAll()
    }

    @Test fun locationWithPermissionGivesAFix() = runBlocking {
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        if (!lm.isLocationEnabled) { println("KITTEST location is off on this phone — skipped"); return@runBlocking }
        val t0 = System.currentTimeMillis()
        val loc = withTimeoutOrNull(60_000) { KLocation.current(ctx) }
        assertNotNull("no location at all within 60 s", loc)
        assertTrue(loc!!.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0)
        val age = (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 60_000_000_000
        println("KITTEST location ok: ${loc.provider}, accuracy ${loc.accuracy} m, ${age} min old, took ${System.currentTimeMillis() - t0} ms")
    }
}
