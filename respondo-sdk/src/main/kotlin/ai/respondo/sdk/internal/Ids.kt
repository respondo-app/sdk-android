package ai.respondo.sdk.internal

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Генераторы идентификаторов SDK. */
internal object Ids {
    private val localCounter = AtomicLong(0)

    /**
     * Анонимный visitor_id формата `"v_" + base36 + base36` — совпадает с вебом
     * (`"v_" + Math.random().toString(36).slice(2) + …`). Персистится, живёт до `reset()`.
     */
    fun newVisitorId(): String = "v_" + base36chunk() + base36chunk()

    /**
     * Локальный id оптимистичного сообщения. Только цифры (без дефиса) — это признак «локального» id;
     * серверные id — UUID с дефисами. Дедуп в треде опирается на это различие.
     */
    fun newLocalMessageId(): String = System.currentTimeMillis().toString() + localCounter.incrementAndGet().toString()

    private fun base36chunk(): String {
        // 11 символов base36 из случайного положительного long.
        val v = Random.nextLong(0, 0x7FFFFFFFFFFFFFFFL)
        return v.toString(36).padStart(11, '0').takeLast(11)
    }
}

/** Серверный id — тот, что содержит дефис (UUID). Локальные оптимистичные id дефиса не имеют. */
internal fun String.isBackendId(): Boolean = contains('-')
