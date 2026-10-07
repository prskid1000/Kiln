package app.kiln.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a blocking model call on its own thread and waits for it in a cancellable way, so Stop
 * returns at once. Coroutine cancellation can't interrupt a socket read: a request still waiting
 * for a slow local model's first byte would otherwise hold Stop for tens of seconds. On cancel,
 * [onAbandon] closes what it can, the thread is interrupted, and anything it produces later is
 * dropped ([body] gets `abandoned()` to check, and events should be guarded with it).
 */
internal suspend fun <T> abandonable(onAbandon: () -> Unit = {}, body: (abandoned: () -> Boolean) -> T): T {
    val abandoned = AtomicBoolean(false)
    val done = CompletableDeferred<T>()
    val worker = Thread({
        try { done.complete(body { abandoned.get() }) } catch (e: Throwable) { done.completeExceptionally(e) }
    }, "kiln-model-call").apply { isDaemon = true; start() }
    try {
        return done.await()
    } catch (e: CancellationException) {
        abandoned.set(true)
        runCatching(onAbandon)
        worker.interrupt()
        throw e
    }
}
