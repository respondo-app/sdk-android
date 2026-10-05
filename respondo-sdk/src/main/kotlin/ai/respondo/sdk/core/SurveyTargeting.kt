package ai.respondo.sdk.core

/**
 * «Когда и где» оверлей-опроса в приложении — чистая часть (без UI и сети).
 *
 * Зеркало веб-модуля `widget/src/survey-targeting.ts`, с экраном вместо адреса страницы
 * (контракт: `sdks/spec/api-surface.md`, backend `outbound/survey_targeting.go`):
 *  - правила экрана сравниваются с именем из `setCurrentScreen`: exact / contains / starts_with /
 *    ends_with по ИЛИ, not_contains обязательны всегда; нет правил — любой экран. Правила адреса
 *    страницы (`survey_url_rules`) в приложении не действуют никогда;
 *  - платформа (`survey_platform`): опрос «только сайт» в приложении не показывается никогда
 *    (каталог его и не отдаёт — SDK шлёт platform=app);
 *  - задержка — секунды на подходящем экране (после события, если оно задано);
 *  - событие — `Respondo.track(<event>)` в этой сессии приложения: живёт [EVENT_TTL_MS] (смена
 *    экрана его не забывает) и расходуется опросом, который открыло.
 * Опрос, уже показанный на экране, остаётся при смене экрана, а опрос, у которого есть доставка
 * (его уже открывали посетителю, в т.ч. до перезапуска приложения), открывается на любом экране
 * без повторного ожидания: правила решают, где посетителя ПРИГЛАШАЮТ, а не где начатый опрос
 * может продолжиться (`sdks/spec/api-surface.md` §3.3, как и в веб-виджете).
 */
internal object SurveyTargeting {
    /** Возможность, которую SDK объявляет каталогу (`features`) и WS-кадру identify. */
    const val FEATURE = "survey_targeting"
    /** Платформа, которой SDK называет себя каталогу и кадру identify. */
    const val CLIENT_PLATFORM = "app"
    const val DELAY_MAX_SECONDS = 600
    /** Сколько событие-триггер может открыть опрос: полчаса, длина сессии. */
    const val EVENT_TTL_MS = 30 * 60 * 1000L

    /** Убирает из [events] события, которые больше не могут открыть опрос (старше TTL). */
    fun dropStaleEvents(events: MutableMap<String, Long>, nowMs: Long) {
        events.entries.removeAll { (_, firedAt) -> firedAt > nowMs || nowMs - firedAt > EVENT_TTL_MS }
    }

    /**
     * «Show on» (`survey_platform`) пускает опрос в приложение — всё, кроме `web`. Без учёта
     * регистра и пробелов по краям, как `outbound.surveyPlatform` на сервере.
     */
    fun showsInApps(platform: String?): Boolean = platform?.trim()?.lowercase() != "web"

    /** Канон имени события — зеркало `outbound.NormalizeEventName`. */
    fun normalizeEventName(name: String): String =
        name.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("_")

    fun clampDelay(seconds: Int?): Int =
        if (seconds == null || seconds <= 0) 0 else minOf(DELAY_MAX_SECONDS, seconds)

    /** Подходит ли экран под правила (пусто — любой экран; null-экран — только без правил). */
    fun screenMatches(rules: List<RespondoScreenRule>, screen: String?): Boolean {
        if (rules.isEmpty()) return true
        val name = screen.orEmpty().trim()
        var positives = 0
        var hits = 0
        for (rule in rules) {
            val value = rule.value.trim()
            if (rule.op == "not_contains") {
                if (value.isNotEmpty() && name.contains(value)) return false
                continue
            }
            positives++
            if (value.isEmpty() || name.isEmpty()) continue
            val hit = when (rule.op) {
                "exact" -> name == value
                "contains" -> name.contains(value)
                "starts_with" -> name.startsWith(value)
                "ends_with" -> name.endsWith(value)
                else -> false
            }
            if (hit) hits++
        }
        return positives == 0 || hits > 0
    }

    sealed interface Readiness {
        /** Показать сейчас. */
        data object Ready : Readiness
        /** На нужном экране, ждём задержку: спросить снова через [inMs]. */
        data class Delay(val inMs: Long) : Readiness
        /** На нужном экране, ждём событие. */
        data object Event : Readiness
        /** Не тот экран. */
        data object Elsewhere : Readiness
    }

    /**
     * Готов ли опрос открыться в момент [nowMs] (все времена — мс одних часов). [resumed] — у
     * опроса есть доставка (его уже открывали посетителю): он открывается на любом экране.
     */
    fun readiness(
        targeting: RespondoSurveyTargeting,
        screen: String?,
        screenSinceMs: Long,
        events: Map<String, Long>,
        nowMs: Long,
        resumed: Boolean = false,
    ): Readiness {
        if (!targeting.showsInApps) return Readiness.Elsewhere
        if (resumed) return Readiness.Ready
        if (!screenMatches(targeting.screenRules, screen)) return Readiness.Elsewhere
        var since = screenSinceMs
        val trigger = targeting.triggerEvent
        if (!trigger.isNullOrEmpty()) {
            val firedAt = events[trigger] ?: return Readiness.Event
            // Задержка — время на подходящем экране ПОСЛЕ события: от события, если оно случилось
            // здесь, от прихода на экран — если раньше.
            since = maxOf(screenSinceMs, firedAt)
        }
        val left = since + targeting.delaySeconds * 1000L - nowMs
        return if (left > 0) Readiness.Delay(left) else Readiness.Ready
    }
}
