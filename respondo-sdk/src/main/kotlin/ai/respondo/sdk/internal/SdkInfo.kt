package ai.respondo.sdk.internal

/** Константы сборки SDK. Держатся отдельно от AGP-`BuildConfig`, чтобы чистая логика и JVM-тесты не зависели от Android. */
internal object SdkInfo {
    /** Версия SDK (совпадает с суффиксом User-Agent, полем metadata.sdk_version и respondo.sdk.version в gradle.properties). */
    const val VERSION: String = "0.1.0"

    /** Значение поля `source` в запросах — отличает мобильный трафик Android от веба. */
    const val SOURCE: String = "sdk-android"

    /** Значение metadata.platform. */
    const val PLATFORM: String = "android"

    /** HTTP-заголовок User-Agent. */
    const val USER_AGENT: String = "RespondoSDK/$VERSION"

    /** Дефолтный базовый URL API. */
    const val DEFAULT_BASE_URL: String = "https://api.respondo.ai"
}
