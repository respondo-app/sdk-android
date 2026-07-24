package ai.respondo.sdk.support

import ai.respondo.sdk.transport.HttpEngine
import ai.respondo.sdk.transport.HttpRequest
import ai.respondo.sdk.transport.HttpResponse

/** Загрузчик фикстур из тестовых ресурсов `/fixtures`. */
object Fixtures {
    fun read(name: String): String {
        val stream = requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) {
            "фикстура не найдена: $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }
}

/** Фейковая реализация [HttpEngine] для транспортных тестов — без сетевых библиотек и эмулятора. */
class FakeHttpEngine(
    var handler: (HttpRequest) -> HttpResponse = { HttpResponse(200, "{}") },
) : HttpEngine {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }

    /** Последний запрос по подстроке в URL. */
    fun lastRequestFor(urlPart: String): HttpRequest? = requests.lastOrNull { it.url.contains(urlPart) }
}
