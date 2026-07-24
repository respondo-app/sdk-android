package ai.respondo.sdk

/**
 * Размер листа чата. Управляет высотой bottom-sheet (детентом), см. `theming.md` §6.
 */
enum class RespondoChatSize { COMPACT, DEFAULT, LARGE }

/**
 * Нативное представление внешнего вида. Перекрывает поля темы, пришедшие с бэкенда (поле за полем:
 * непустое значение оверрайда выигрывает у серверного). Полная семантика и дефолты — в `theming.md`.
 *
 * @property primaryColor основной цвет в формате ARGB `0xFFRRGGBB`.
 * @property title заголовок шапки чата.
 * @property greeting приветственный пузырь ассистента.
 * @property logoUrl лого в шапке чата.
 * @property avatarUrl аватар ассистента.
 * @property chatSize размер листа (детент).
 * @property cornerRadiusDp радиус верхних углов листа, dp (клампится в диапазон 0…28).
 */
data class RespondoTheme(
    val primaryColor: Long? = null,
    val title: String? = null,
    val greeting: String? = null,
    val logoUrl: String? = null,
    val avatarUrl: String? = null,
    val chatSize: RespondoChatSize? = null,
    val cornerRadiusDp: Int? = null,
)
