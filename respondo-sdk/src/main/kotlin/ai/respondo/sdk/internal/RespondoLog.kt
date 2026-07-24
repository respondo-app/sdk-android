package ai.respondo.sdk.internal

import android.util.Log

/**
 * Логгер SDK. Все сообщения идут под общим тегом с префиксом `Respondo:` — как `console.error`/`console.warn` веба.
 * Уровень по умолчанию — warn+error; debug включается флагом [debugEnabled] (без изменения публичной поверхности).
 */
internal object RespondoLog {
    private const val TAG = "Respondo"

    @Volatile
    var debugEnabled: Boolean = false

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
    }
}
