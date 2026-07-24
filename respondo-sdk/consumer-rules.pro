# Правила ProGuard/R8, применяемые к приложению-потребителю SDK.

# kotlinx.serialization: сохраняем сгенерированные сериализаторы моделей SDK.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ai.respondo.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class ai.respondo.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Публичная поверхность SDK не обфусцируется (host-приложение обращается к ней по именам).
-keep class ai.respondo.sdk.Respondo { *; }
-keep class ai.respondo.sdk.RespondoConfig { *; }
-keep class ai.respondo.sdk.RespondoIdentity { *; }
-keep class ai.respondo.sdk.RespondoTheme { *; }
-keep class ai.respondo.sdk.RespondoPushPayload { *; }
-keep interface ai.respondo.sdk.RespondoListener { *; }
