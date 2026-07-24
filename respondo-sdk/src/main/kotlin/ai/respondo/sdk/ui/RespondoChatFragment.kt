package ai.respondo.sdk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

/**
 * Fragment-обёртка чата Respondo для XML-хостов (не-Compose приложений). Добавьте фрагмент в контейнер
 * (напр. в корневой layout Activity), а чат открывайте/закрывайте фасадом [ai.respondo.sdk.Respondo] —
 * фрагмент лишь монтирует [RespondoChatHost], реагирующий на состояние.
 *
 * Пример:
 * ```
 * supportFragmentManager.beginTransaction()
 *     .add(android.R.id.content, RespondoChatFragment())
 *     .commit()
 * ```
 */
class RespondoChatFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RespondoChatHost() }
        }
    }
}
