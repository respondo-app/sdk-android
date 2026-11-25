package ai.respondo.sdk.i18n

/**
 * Стартовая локаль UI SDK (решение 2026-11-02): язык беседы решает сервер —
 * детектор по словам клиента, выбор тиммейта, явная просьба, язык контакта — и присылает его
 * (`visitor_language` конфига, `lang` ответа `/chat`, `conversation_updated{lang}`). SDK язык по
 * тексту посетителя не определяет. До ответа сервера: (1) сохранённая `respondoai_lang` — язык беседы,
 * который сервер дал на прошлом запуске (вызывающий передаёт её только для продолжаемой беседы: новая
 * беседа определяет язык на сервере заново); (2) локаль host-приложения или системы. Результат всегда
 * нормализуется к поддерживаемому коду (иначе → en).
 */
object LocaleDetect {

    fun detect(storedLang: String?, systemLocale: String?): String {
        if (!storedLang.isNullOrEmpty()) return Strings.normalize(storedLang)
        return Strings.normalize(systemLocale)
    }
}
