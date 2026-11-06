# Enlace 5:UI / Sistemas de Diseño/Accesibilidad — Línea base de deduplicación

## Solución confirmada/Decisión de diseño (no más presentación)
- LAYOUT-01 Ancho del texto en línea+Restricción de fila: guardarrail check_row_text_width.py línea base de tolerancia cero (línea base vacía);MarkdownText La exención es la intención del diseño; Los componentes de fila compartida vienen en dos juegos.
- CMP-11 La barandilla de convergencia de componentes entra en vigor;M3 La cotización directa sólo baja pero no aumenta.
- visualización del razonamiento del mensaje de la herramienta,ToolRunCard No se incluye la copia completa del pensamiento dentro del grupo, el razonamiento puro."N operaciones realizadas", Se ha solucionado la supresión de la tarjeta dual PlanCard del estado de recuperación.
- TaskCard Pausa de un solo paso/restaurar (toolCallId vinculante,PENDING hacer una pausa,RUNNING No obligado a matar).
- La interfaz de usuario de tabla/diferencia de resultados de la herramienta ya no descarta silenciosamente las colas.
- MessageBubble Se agregó LongParameterList de showToolCallDetails por @Suppress (calibre de punto de ensamblaje a nivel de pantalla).
- AssistantAvatar Decodificar por tamaño de visualización (OOM reparación,AssistantAvatarMemoryWiringTest).
- ChatListScreen Retirar vista previa MuseIcons.chat + MuseIconSizes.iconTiny.
- TerminalScreen 5 en M3 IconButton → MuseTopBarIconButton;OnboardingScreen M3 Switch → MuseSwitch.
- El banner de actualización es texto sin formato, como está diseñado para ser Surface (no una regresión).
- MuseDialog El área de contenido se ha desplazado (no más contenedores de desplazamiento anidados)——Trampas conocidas.
- MuMu emulador insets≈0 Utilice museSafeTopInsetPadding para descubrirlo——conocido.
- Rediseño de la barra de entrada + textura de vidrio + barra de salto de MessageMapBar + reparación de salto de arrastre de la barra de navegación (v2.5.x lote) se ha implementado.
- TalkBack/El contraste requiere verificación del dispositivo (conocido); A cada uno de los cinco paquetes de idiomas le faltan 101 traducciones (conocidas).

## La dirección de exploración clave de este enlace.
1. **2026-10 Coherencia del estado de la interfaz de usuario después de dividir y refactorizar**:ChatViewModel Después de retirar el coordinador/transformador,UI flujo de estado (StateFlow/LiveData) ¿Hay inicios repetidos, actualizaciones perdidas o estados de cadena entre sesiones (anteriormente, el error de sesión de cadena se ha reparado durante muchas rondas?——Compruebe si el código recién extraído reproduce patrones similares).
2. v2.5.x Nueva interfaz (rediseño del campo de entrada,MessageMapBar, panel de textura de vidrio, entrada de exportación de diagnóstico,A2A página de configuración?): Si se debe cumplir con LAYOUT-01 (ancho+restringido),7 cadena de idioma,MuseIcons,Muse Componente, alcance el objetivo 48 dp.
3. Compose Rendimiento: Nueva interfaz en la larga lista/Puntos calientes de reorganización bajo el texto grande;stable/immutable Recombinación excesiva debido a anotaciones faltantes.
4. tema oscuro/El token de color de los nuevos componentes bajo la selección dinámica de colores cumple (check_design_tokens Solo busca colores nude——Buscar archivos nuevos).
5. teclado/Interacción del método de entrada: recuadro de IME y panel de emoticones después del rediseño de la barra de entrada/La lista de chat salta cuando se cambia el panel de herramientas.
6. Semántica de accesibilidad: descripción del contenido/descripción del estado de nuevos componentes interactivos (solo se verifican las barreras de seguridad)"desaparecido", comprobar"error semántico"requiere trabajo manual).
