package ai.respondo.sdk

/**
 * Неизменяемая конфигурация SDK. Передаётся один раз в [Respondo.init].
 *
 * [agentId] обязателен всегда (непустой): SDK не поддерживает humans-only сценарий без агента.
 *
 * @property agentId id AI-агента; обязателен и непуст.
 * @property channelId id widget-канала (интеграции). Передаётся в запросы как `channel_id`;
 *   заменяет legacy-алиас `integrationId` из веба.
 * @property baseUrl базовый URL API; при `null` → `https://api.respondo.ai`. WS-хост выводится из него.
 * @property locale BCP-47 (напр. `"ru"` / `"en"`); при `null` берётся системная локаль устройства.
 *   Управляет и языком строк UI, и параметром `lang` запроса конфигурации.
 * @property themeOverride перекрывает поля темы, пришедшие с бэкенда (поле за полем).
 */
data class RespondoConfig(
    val agentId: String,
    val channelId: String? = null,
    val baseUrl: String? = null,
    val locale: String? = null,
    val themeOverride: RespondoTheme? = null,
)
