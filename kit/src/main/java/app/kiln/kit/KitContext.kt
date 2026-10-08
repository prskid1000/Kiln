package app.kiln.kit

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * The app's Context, ready from process start — before any Activity, in workers and receivers too.
 * It lets stores be plain top-level values with no lateinit and no setup call:
 * ```
 * object Repo {
 *     val expenses = KCollection<Expense>("expenses")
 *     val settings = KStore("settings", Settings())
 * }
 * ```
 */
object KApp {
    lateinit var context: Context
        internal set
}

/** Sets [KApp.context] when the process starts (declared in the kit's manifest; apps do nothing). */
class KitInitProvider : ContentProvider() {
    override fun onCreate(): Boolean { context?.applicationContext?.let { KApp.context = it }; return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
