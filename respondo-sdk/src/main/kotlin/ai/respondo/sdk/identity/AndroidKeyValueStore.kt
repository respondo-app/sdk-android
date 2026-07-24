package ai.respondo.sdk.identity

import ai.respondo.sdk.internal.KeyValueStore
import ai.respondo.sdk.internal.RespondoLog
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Боевое хранилище SDK поверх [EncryptedSharedPreferences]. Если инициализация Keystore недоступна
 * (редкие устройства/битый keystore) — деградирует до обычных приватных SharedPreferences, чтобы
 * visitor_id и сессия оставались стабильными (фрагментация контакта хуже отсутствия шифрования).
 */
internal class AndroidKeyValueStore private constructor(
    private val prefs: SharedPreferences,
) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    override fun removeByPrefix(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private const val FILE = "respondo_sdk_store"

        fun create(context: Context): AndroidKeyValueStore {
            val appContext = context.applicationContext
            val prefs = try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (t: Throwable) {
                RespondoLog.w("EncryptedSharedPreferences недоступны — фолбэк на приватные prefs", t)
                appContext.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE)
            }
            return AndroidKeyValueStore(prefs)
        }
    }
}
