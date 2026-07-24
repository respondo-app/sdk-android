package ai.respondo.sdk.core

/**
 * Счётчик непрочитанных сообщений визитёра. Источники бейджа (behavior.md §4): непрочитанные сообщения
 * оператора при закрытом чате, проактив, припаркованная кампания, непрочитанная in-thread кампания.
 * Нативно показываем ЧИСЛО (веб — только точку); это расхождение в сторону улучшения.
 *
 * Инкремент считает только «свежие» сообщения (по id, не виденные ранее) при закрытом чате; открытие/read обнуляет.
 */
class UnreadTracker {
    private val countedIds = HashSet<String>()
    private var count: Int = 0

    /** Учитывает новое сообщение оператора при закрытом чате. Возвращает актуальный счётчик. */
    @Synchronized
    fun onIncomingWhileClosed(messageId: String): Int {
        if (countedIds.add(messageId)) count += 1
        return count
    }

    /** Поднимает флаг непрочитанного без привязки к id (проактив/кампания). */
    @Synchronized
    fun bumpBadge(): Int {
        count += 1
        return count
    }

    @Synchronized
    fun reset(): Int {
        countedIds.clear()
        count = 0
        return count
    }

    @get:Synchronized
    val value: Int get() = count
}
