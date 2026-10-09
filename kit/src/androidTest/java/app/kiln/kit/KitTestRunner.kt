package app.kiln.kit

import android.app.ActivityManager
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * The usual runner, but it leaves nothing behind: each test's screen is finished by its compose rule, yet the
 * test app's task stayed in Recents after the run. Every task is removed when the run ends.
 */
class KitTestRunner : AndroidJUnitRunner() {
    override fun finish(resultCode: Int, results: Bundle?) {
        runCatching {
            context.getSystemService(ActivityManager::class.java).appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        super.finish(resultCode, results)
    }
}
