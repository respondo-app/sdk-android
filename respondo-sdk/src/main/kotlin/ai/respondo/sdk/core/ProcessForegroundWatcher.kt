package ai.respondo.sdk.core

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Фон и передний план ПРОЦЕССА для сессий приложения — `ProcessLifecycleOwner`
 * (`androidx.lifecycle:lifecycle-process`). Почему он, а не свой счётчик активити:
 *
 * - события уровня процесса: ON_START — первая активити процесса стала видимой, ON_STOP — последняя
 *   ушла, с задержкой ~700 мс, поэтому поворот экрана и переход активити A → B фоном не считаются;
 * - поздний `Respondo.init` (из `Activity.onCreate`, после первого старта активити) получает догоняющий
 *   ON_START, только если процесс и правда на переднем плане, — счёт активити, начатый с середины, этого
 *   не умел;
 * - процесс, поднятый в фоне (FCM data-пуш, WorkManager, ресивер, сервис), остаётся в CREATED: ON_START
 *   не приходит, и сессия не открывается, пока человек не откроет приложение.
 *
 * Ставится один раз на процесс из `Respondo.init`, наблюдатель добавляется на главном потоке (init можно
 * звать с любого потока). Реалтайм не трогает — WS по-прежнему ведут `Respondo.onBackground/onForeground`.
 * `ProcessLifecycleOwner` инициализирует androidx.startup (`ProcessLifecycleInitializer` в
 * InitializationProvider); приложение, которое этот инициализатор вырезало из манифеста, должно вызвать
 * его само, иначе событий процесса нет и сессии приложения не считаются.
 */
internal object ProcessForegroundWatcher {
    @Volatile
    private var installed = false

    /** Подписывается на жизненный цикл процесса (идемпотентно); колбэки — на главном потоке. */
    @Synchronized
    fun install(onStart: () -> Unit, onStop: () -> Unit) {
        if (installed) return
        installed = true
        val add = Runnable {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    runCatching(onStart)
                }

                override fun onStop(owner: LifecycleOwner) {
                    runCatching(onStop)
                }
            })
        }
        if (Looper.myLooper() == Looper.getMainLooper()) add.run() else Handler(Looper.getMainLooper()).post(add)
    }
}
