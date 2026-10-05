package ai.respondo.sdk.core

import ai.respondo.sdk.internal.RespondoLog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Открывает диплинк push-кампании так, как это делает Intercom: ссылка уходит системе через ACTION_VIEW, и её
 * принимает intent-filter самого host-приложения — custom scheme (`yourapp://orders/1`) или App Link (`https://`).
 *
 * Порядок:
 * 1. Сначала intent адресуется собственному пакету host-приложения — диплинк открывает экран внутри приложения,
 *    а не уходит в чужое приложение с той же схемой и не показывает диалог выбора.
 * 2. Если своё приложение ссылку не принимает, а это http(s) — открывается системный браузер (обычная веб-ссылка).
 * 3. Иначе — false: host получает [ai.respondo.sdk.RespondoListener.onUnhandledDeepLink].
 */
internal object DeepLinkOpener {
    /** Схемы, которые SDK не открывает никогда (исполнение кода, чужие файлы, произвольные intent). */
    private val BLOCKED_SCHEMES = setOf("javascript", "data", "file", "content", "intent", "about", "blob")

    fun open(context: Context, link: String): Boolean {
        val uri = runCatching { Uri.parse(link.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || scheme.isNullOrEmpty() || scheme in BLOCKED_SCHEMES) {
            RespondoLog.w("диплинк пуша отклонён: $link")
            return false
        }
        val inApp = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (inApp.resolveActivity(context.packageManager) != null && start(context, inApp)) return true
        if (scheme == "http" || scheme == "https") {
            val browser = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, browser)) return true
        }
        RespondoLog.w("диплинк пуша не принят приложением: $link — объявите intent-filter для этой ссылки")
        return false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
