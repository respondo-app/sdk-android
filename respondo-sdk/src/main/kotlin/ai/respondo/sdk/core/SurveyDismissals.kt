package ai.respondo.sdk.core

import ai.respondo.sdk.identity.StorageKeys
import ai.respondo.sdk.internal.KeyValueStore
import ai.respondo.sdk.internal.respondoJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Доставки опросов, которые посетитель закрыл, — в хранилище SDK, как `respondo_survey_seen_*`
 * веб-виджета. Без этого опрос с доставкой (он продолжается на любом экране) после перезапуска
 * возвращался на первом же экране, хотя его закрыли (sdks/spec/api-surface.md §3.3).
 * Хранятся последние [LIMIT]; `reset()` стирает их вместе с контактом ([forget]). Не потокобезопасен —
 * вызывается под `stateLock` [EngagementController].
 */
internal class SurveyDismissals(private val store: KeyValueStore) {
    private val ids: LinkedHashSet<String> = load()

    fun contains(deliveryId: String): Boolean = deliveryId.isNotEmpty() && deliveryId in ids

    fun add(deliveryId: String) {
        if (deliveryId.isEmpty()) return
        ids.remove(deliveryId)
        ids.add(deliveryId)
        while (ids.size > LIMIT) ids.remove(ids.first())
        save()
    }

    fun remove(deliveryId: String) {
        if (ids.remove(deliveryId)) save()
    }

    /** Контакт сменился (`reset()`): его закрытые опросы забыты. */
    fun forget() {
        ids.clear()
        store.remove(StorageKeys.SURVEY_DISMISSED)
    }

    private fun load(): LinkedHashSet<String> {
        val raw = store.getString(StorageKeys.SURVEY_DISMISSED) ?: return LinkedHashSet()
        return runCatching { LinkedHashSet(respondoJson.decodeFromString(serializer, raw)) }
            .getOrElse { LinkedHashSet() }
    }

    private fun save() {
        if (ids.isEmpty()) store.remove(StorageKeys.SURVEY_DISMISSED)
        else store.putString(StorageKeys.SURVEY_DISMISSED, respondoJson.encodeToString(serializer, ids.toList()))
    }

    private companion object {
        const val LIMIT = 100
        val serializer = ListSerializer(String.serializer())
    }
}
