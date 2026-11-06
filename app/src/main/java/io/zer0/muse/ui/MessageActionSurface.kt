package io.zer0.muse.ui

/**
 * Estado mutuamente excluyente del panel de acción del mensaje.
 *
 * Utilice un solo estado en su lugar showActionMenu/showExtendedMenu/showLanguageSubmenu tres booleanos,
 * Esto garantiza que el menú condensado, el menú completo y el menú de idiomas no aparecerán al mismo tiempo.
 */
enum class MessageActionSurface {
    Hidden,
    Compact,
    Extended,
    TranslationLanguages,
}
