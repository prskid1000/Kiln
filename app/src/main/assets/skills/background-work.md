# background-work — run work later or periodically (reminders, syncs, daily resets) with WorkManager

- A `CoroutineWorker` does the work; enqueue it with `WorkManager.getInstance(context)`.
- Periodic work runs at most every 15 minutes; use unique names so re-enqueueing doesn't duplicate.
- Work survives app restarts and reboots. Keep it short; post a notification for the user to see.

```kotlin
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kiln.kit.KNotify
import java.util.concurrent.TimeUnit

class SkillReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        KNotify.post(applicationContext, "Reminder", inputData.getString("text") ?: "Time for a break")
        return Result.success()
    }
}

fun skillRemindIn(context: Context, minutes: Long, text: String) {
    val req = OneTimeWorkRequestBuilder<SkillReminderWorker>()
        .setInitialDelay(minutes, TimeUnit.MINUTES)
        .setInputData(androidx.work.workDataOf("text" to text))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("reminder", ExistingWorkPolicy.REPLACE, req)
}

fun skillRemindHourly(context: Context) {
    val req = PeriodicWorkRequestBuilder<SkillReminderWorker>(1, TimeUnit.HOURS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("hourly", ExistingPeriodicWorkPolicy.KEEP, req)
}
```
