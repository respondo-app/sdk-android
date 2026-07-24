package ai.respondo.sdk.i18n

/**
 * Детект локали UI с тем же приоритетом, что в вебе:
 * (1) сохранённая `respondoai_lang`; (2) язык текста первого пользовательского сообщения
 * (кириллица → ru; украинские `іїєІЇЄ` → uk); (3) базовый код системной локали устройства.
 * Результат всегда нормализуется к поддерживаемому коду (иначе → en).
 */
object LocaleDetect {

    fun detect(storedLang: String?, firstUserText: String?, systemLocale: String?): String {
        if (!storedLang.isNullOrEmpty()) return Strings.normalize(storedLang)
        detectFromText(firstUserText)?.let { return it }
        return Strings.normalize(systemLocale)
    }

    /** Язык по тексту: украинские спец-буквы → uk; прочая кириллица → ru; иначе null. */
    fun detectFromText(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        if (text.any { it in UKRAINIAN_MARKERS }) return "uk"
        if (text.any { it in 'Ѐ'..'ӿ' }) return "ru"
        return null
    }

    private val UKRAINIAN_MARKERS = setOf('і', 'ї', 'є', 'І', 'Ї', 'Є')
}
