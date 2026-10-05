package ai.respondo.sdk.internal

import android.content.Context
import android.os.Build
import java.util.TimeZone

/**
 * Технический контекст устройства — мобильный аналог `page_url` веба. Домешивается в `identity.metadata`
 * перед каждой отправкой; host-значения с тем же ключом имеют приоритет (см. api-surface §8).
 */
internal class DeviceContext(
    private val appVersion: String,
    private val osVersion: String,
    private val deviceModel: String,
    private val timezone: String,
) {
    /** Локаль (BCP-47) — из RespondoConfig.locale или системная; обновляется контроллером. */
    @Volatile
    var locale: String = "en"

    /** Имя текущего экрана host-приложения (опционально). */
    @Volatile
    var screen: String? = null

    /** Собирает авто-метаданные. Значения не перетирают host-ключи — слияние делает вызывающий. */
    fun autoMetadata(): Map<String, String> {
        val map = linkedMapOf(
            "app_version" to appVersion,
            "sdk_version" to SdkInfo.VERSION,
            "platform" to SdkInfo.PLATFORM,
            "os_version" to osVersion,
            "device_model" to deviceModel,
            "locale" to locale,
            "timezone" to timezone,
        )
        screen?.takeIf { it.isNotEmpty() }?.let { map["screen"] = it }
        return map
    }

    companion object {
        fun from(context: Context): DeviceContext {
            val appContext = context.applicationContext
            val appVersion = runCatching {
                val pkg = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                pkg.versionName ?: "unknown"
            }.getOrDefault("unknown")

            val timezone = runCatching { TimeZone.getDefault().id }.getOrDefault("UTC")

            return DeviceContext(
                appVersion = appVersion,
                osVersion = "Android ${Build.VERSION.RELEASE}",
                deviceModel = Build.MODEL ?: "Android",
                timezone = timezone,
            )
        }
    }
}
