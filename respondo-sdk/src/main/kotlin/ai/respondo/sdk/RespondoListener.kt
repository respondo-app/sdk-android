package ai.respondo.sdk

import ai.respondo.sdk.core.RespondoProactiveMessage

/**
 * Императивные колбэки SDK — для host-приложений, которым удобнее делегат/лямбды, чем реактивные потоки.
 * Оба механизма отражают одни и те же события. Все методы вызываются на главном потоке.
 */
interface RespondoListener {
    /** Изменилось число непрочитанных (для бейджа на иконке host-приложения). */
    fun onUnreadChanged(count: Int) {}

    /** Модалка чата открылась. */
    fun onChatOpened() {}

    /** Модалка чата закрылась. */
    fun onChatClosed() {}

    /**
     * Пользователь тапнул ссылку/CTA в чате.
     * @return `true`, если host сам открыл URL (SDK не будет открывать); `false` — SDK откроет во внешнем браузере.
     */
    fun onUrlRequested(url: String): Boolean = false

    /**
     * Тап по пушу/диплинку, который SDK распознал, но не смог отобразить (напр. разговор недоступен) —
     * host решает, что делать.
     */
    fun onUnhandledDeepLink(payload: RespondoPushPayload) {}

    /**
     * Появилось проактивное сообщение под текущий экран (тизер над лончером). Host может показать
     * пузырь-подсказку; тап по нему должен вызвать [Respondo.open] — сообщение попадёт в тред.
     */
    fun onProactiveMessage(message: RespondoProactiveMessage) {}
}
