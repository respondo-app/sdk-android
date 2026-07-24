package ai.respondo.sdk.transport

/** HTTP-метод, поддерживаемый транспортом SDK. */
enum class HttpMethod { GET, POST, DELETE }

/** Тело HTTP-запроса. */
sealed interface HttpBody {
    /** JSON-тело (`application/json`). */
    data class Json(val text: String) : HttpBody

    /** Multipart-тело с единственным файловым полем (для `/chat/upload`). */
    data class Multipart(
        val fieldName: String,
        val filename: String,
        val contentType: String,
        val bytes: ByteArray,
    ) : HttpBody {
        override fun equals(other: Any?): Boolean =
            other is Multipart && fieldName == other.fieldName && filename == other.filename &&
                contentType == other.contentType && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int {
            var result = fieldName.hashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + contentType.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }
}

/**
 * HTTP-запрос в терминах, независимых от сетевой библиотеки.
 *
 * @property timeoutMs переопределение таймаута вызова (напр. 20000 для `POST /chat`); `null` — дефолт движка.
 */
data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: HttpBody? = null,
    val timeoutMs: Long? = null,
)

/** HTTP-ответ. Тело всегда прочитано в строку (виджет-эндпоинты возвращают JSON или пустое тело). */
data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Абстракция HTTP-транспорта. Боевая реализация — [OkHttpEngine]; в unit-тестах подставляется фейковая,
 * что позволяет прогонять транспортные сценарии без сетевых библиотек и без эмулятора.
 */
interface HttpEngine {
    /** Выполняет запрос. Бросает [java.io.IOException] при сетевой ошибке/таймауте (не при не-2xx статусе). */
    suspend fun execute(request: HttpRequest): HttpResponse

    /** Освобождает ресурсы движка (пулы соединений и т. п.). */
    fun close() {}
}
