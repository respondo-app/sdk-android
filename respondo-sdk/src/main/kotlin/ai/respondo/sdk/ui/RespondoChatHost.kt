package ai.respondo.sdk.ui

import ai.respondo.sdk.Respondo
import ai.respondo.sdk.RespondoChatState
import ai.respondo.sdk.core.ChatSurface
import ai.respondo.sdk.core.OverlayDecision
import ai.respondo.sdk.core.RespondoBannerPosition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Точка монтирования чата Respondo. Host-приложение размещает [RespondoChatHost] один раз в корне своего
 * Compose-дерева; SDK сам показывает bottom-sheet, когда [Respondo.open] переводит [Respondo.chatState] в OPEN.
 * Точку входа (кнопку/иконку/бейдж) рисует сам host — SDK лончер не рисует (см. `theming.md` §4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RespondoChatHost() {
    val chatState by Respondo.chatState.collectAsState()
    val controller = Respondo.activeController ?: return
    if (chatState != RespondoChatState.OPEN && chatState != RespondoChatState.OPENING) return

    val theme by controller.theme.collectAsState()
    val surface by controller.surface.collectAsState()
    val overlay by controller.engagement.activeOverlay.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = { Respondo.close() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = theme.cornerRadiusDp.dp, topEnd = theme.cornerRadiusDp.dp),
    ) {
        RespondoComposeTheme(theme) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(theme.maxDetent)) {
                val banner = (overlay as? OverlayDecision.Banner)?.banner
                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    if (banner != null && banner.position == RespondoBannerPosition.TOP) {
                        BannerBar(controller, banner)
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (surface) {
                            ChatSurface.CHAT -> ChatScreen(controller)
                            ChatSurface.NEWS -> NewsScreen(controller)
                            ChatSurface.CHECKLISTS -> ChecklistsScreen(controller)
                        }
                    }
                    if (banner != null && banner.position == RespondoBannerPosition.BOTTOM) {
                        BannerBar(controller, banner)
                    }
                }
                // Опрос-оверлей поверх всей поверхности (survey > banner в арбитре).
                (overlay as? OverlayDecision.Survey)?.let { SurveyOverlay(controller, it.survey) }
            }
        }
    }
}
