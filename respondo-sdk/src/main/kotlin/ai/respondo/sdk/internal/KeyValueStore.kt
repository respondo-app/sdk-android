package ai.respondo.sdk.internal

/**
 * Абстракция ключ-значение хранилища. Боевая реализация — зашифрованные SharedPreferences
 * ([ai.respondo.sdk.identity.AndroidKeyValueStore]); в тестах — [InMemoryKeyValueStore].
 * Все ключи SDK несут префикс `respondoai_` (важно для семантики `reset()`, который стирает всё по префиксу).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun contains(key: String): Boolean
    fun keys(): Set<String>

    /** Удаляет все ключи, начинающиеся с [prefix]. */
    fun removeByPrefix(prefix: String) {
        keys().filter { it.startsWith(prefix) }.forEach { remove(it) }
    }
}

/** Потокобезопасная in-memory реализация [KeyValueStore] для unit-тестов. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun keys(): Set<String> = map.keys.toSet()
}
