# sharing — share text, open a link, email, dial, or open a map from the app

- `KIntents.share(ctx, text)` and `KIntents.openUrl(ctx, url)` cover most cases.
- Other actions are plain intents; guard with `resolveActivity`-free `runCatching` (no app may handle it).

```kotlin
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.kiln.kit.KIntents

fun skillShareResult(context: Context, total: Int) = KIntents.share(context, "I drank $total ml today")

fun skillEmail(context: Context, to: String, subject: String) = runCatching {
    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun skillDial(context: Context, number: String) = runCatching {
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun skillMap(context: Context, query: String) = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
```
