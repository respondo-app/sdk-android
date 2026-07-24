package ai.respondo.sdk.core

import ai.respondo.sdk.internal.RespondoLog
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Единая точка внешнего открытия ссылок (системный браузер/обработчик). Используется и чат-UI, и
 * engagement-слоем, чтобы поведение ссылок было одинаковым. Небезопасные схемы отсекаются [UrlSafety].
 */
internal object ExternalOpener {
    fun open(context: Context, url: String) {
        if (!UrlSafety.isHttp(url)) {
            RespondoLog.w("внешнее открытие отклонено — схема не http/https: $url")
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { RespondoLog.w("не удалось открыть URL внешним приложением: $url", it) }
    }
}
