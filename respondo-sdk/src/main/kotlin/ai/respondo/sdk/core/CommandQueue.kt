package ai.respondo.sdk.core

import ai.respondo.sdk.RespondoIdentity
import ai.respondo.sdk.RespondoPushPayload
import ai.respondo.sdk.internal.RespondoLog

/** Отложенный вызов фасада, сделанный до завершения `init` (см. api-surface §7). */
sealed interface QueuedCommand {
    data class Identify(val identity: RespondoIdentity) : QueuedCommand
    data class Track(val name: String, val properties: Map<String, Any?>) : QueuedCommand
    data object Open : QueuedCommand
    data object Close : QueuedCommand
    data object OpenNews : QueuedCommand
    data object OpenChecklists : QueuedCommand
    data class SetPushToken(val token: String) : QueuedCommand
    data object ClearPushToken : QueuedCommand
    data class HandlePush(val payload: RespondoPushPayload) : QueuedCommand
}

/**
 * FIFO-очередь команд до `init`. Правила (api-surface §7):
 * - несколько `identify` схлопываются — применяется последний (личность это состояние, не событие);
 * - `track` не схлопывается (каждое событие — отдельный факт);
 * - размер ограничен [MAX_SIZE]; при переполнении отбрасываются самые старые не-`handlePush` вызовы.
 */
class CommandQueue {
    private val items = ArrayDeque<QueuedCommand>()

    @Synchronized
    fun enqueue(command: QueuedCommand) {
        if (command is QueuedCommand.Identify) {
            // Схлопывание: снять предыдущий identify, оставить последний.
            items.removeAll { it is QueuedCommand.Identify }
        }
        if (items.size >= MAX_SIZE) {
            val oldestReplaceable = items.indexOfFirst { it !is QueuedCommand.HandlePush }
            if (oldestReplaceable >= 0) {
                items.removeAt(oldestReplaceable)
            } else {
                items.removeFirst()
            }
            RespondoLog.w("command-queue переполнена — отброшен самый старый вызов")
        }
        items.addLast(command)
    }

    /** Извлекает все команды в порядке поступления и очищает очередь. */
    @Synchronized
    fun drain(): List<QueuedCommand> {
        val snapshot = items.toList()
        items.clear()
        return snapshot
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    @get:Synchronized
    val size: Int get() = items.size

    companion object {
        const val MAX_SIZE = 256
    }
}
