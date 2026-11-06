package io.zer0.muse.ui.chat

/**
 * La forma de interfaz de la página de chat.
 *
 * ¿Por qué utilizar la enumeración en lugar del booleano continuo del montón?
 * `ChatScreen` Ya `isAgentMode` parámetros, agregue uno más `isMiniPhoneMode` aparecerá
 * Dos cambios mutuamente excluyentes que pueden ser verdaderos al mismo tiempo duplican el número de combinaciones de ramas y no pueden restringirse a nivel de tipo.
 * poner aquí"forma"Para converger en una única enumeración, solo es necesario agregar nuevos formularios con un caso y las ramas deben cubrirse explícitamente.
 *
 * - [FULL]: Página completa. La página de detalles de la conversación común incluida en la lista de tareas viene con una isla de retorno y una barra superior.
 * - [AGENT]:Agent Tab Modo integrado. por `HomeScreen` Proporcionar barra superior y menú de tres puntos.
 *   ID de sesión ir `agentSessionId` Sucursales, completamente aisladas de las sesiones de tareas.
 * - [MINI_PHONE]: Modo integrado de teléfono celular pequeño. Barra superior estilo WeChat (avatar + nombre + retorno),
 *   la identificación de la sesión todavía aparece `currentSessionId`(La pequeña sesión de teléfono celular es una sesión normal, no una sesión de agente),
 *   Regrese al teléfono pequeño en lugar de a la lista principal de sesiones de la aplicación.
 *
 * Nota:[MINI_PHONE] Solo se cambian el shell y la navegación, y los enlaces generados, la instalación de herramientas y la determinación de permisos no se modifican.——
 * Están determinadas por las propiedades de la sesión misma, y ​​cambiar la forma no debería producir diferencias de comportamiento.
 */
enum class ChatSurface {
    FULL,
    AGENT,
    MINI_PHONE,
    ;

    /** Si la barra superior es proporcionada por un contenedor externo (en este caso `ChatScreen` no dibuja su propia barra superior). */
    val hidesOwnTopBar: Boolean get() = this == AGENT

    /** Si pertenece a la forma integrada de pequeños teléfonos celulares. */
    val isMiniPhone: Boolean get() = this == MINI_PHONE
}
