package io.zer0.muse.tools

/**
 * P2-23: Registrador de herramientas de generación de medios(Imágenes / Vídeos / Códigos QR).
 *
 * de ChatViewModel.registerMediaTools() Múdate como está —— ToolDef(name / description /
 * parameters / required / riskLevel / parameterTypes)Ni una palabra cambió,Nivel de riesgo mantenido
 * generate_image / generate_video = HIGH,generate_qr_code = SAFE.
 *
 * En cambio, cuando se inicia la aplicación, [ToolRegistrarBootstrapper] Forzar registro de instanciación,Por lo tanto
 * Las tareas programadas/chats grupales/subagentes, etc. también se pueden usar sin enlaces de UI. [ToolRegistry] Encuentre y ejecute estas herramientas en,
 * Ya no depende de"El usuario ha abierto la página de chat.". Ver la implementación [MediaGenToolsImpl].
 *
 * [impl] Inyectado por Koin(Es la misma instancia donde ChatViewModel instala el host de entrega.).
 */
class MediaGenToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val impl: MediaGenToolsImpl,
) {

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_image",
                description = "Genera imágenes basadas en descripciones de usuarios. Solo se llama cuando el usuario solicita explícitamente dibujos, diseños, avatares, carteles, etc. Se consumirán créditos de API de dibujo.",
                parameters = mapOf(
                    "prompt" to "Requerido. Escribe el tema claramente/estilo/composición,el ingles es mejor,Como por ejemplo 'a cute cat on a sofa, watercolor style'",
                    "model" to "Opcional,modelo de dibujo ID(Como por ejemplo dall-e-3 / gpt-image-1);Utilice el modelo predeterminado del proveedor de forma predeterminada",
                    "size" to "Opcional,Tamaño,Como por ejemplo 1024x1024 / 1792x1024 / 1024x1792;Agnes Relación de soporte 1:1 / 16:9 / 3:2",
                    "quality" to "Opcional,Como por ejemplo standard / hd",
                    "style" to "Opcional,Como por ejemplo vivid / natural",
                    "n" to "Opcional,Generar cantidad,Predeterminado 1",
                    "reference_image" to "Opcional,URL de la imagen de referencia o base64(Tu Sheng Tu/Editar). El mapa de referencia local es inyectado por el usuario después de seleccionarlo en la tarjeta de aprobación.,LLM No es necesario completar",
                ),
                required = setOf("prompt"),
                riskLevel = ToolRiskLevel.HIGH,
                parameterTypes = mapOf("n" to "integer"),
            ),
        ) { args -> impl.execGenerateImage(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_video",
                // v1.0.75 fix (Revisión de herramientas 02): Ejemplos de indicaciones complementarias e instrucciones de devolución
                description = "Genere videos cortos basados ​​en descripciones de usuarios. Sólo si el usuario solicita explícitamente el vídeo./Llamado durante la animación. Selección automática de proveedores que admiten salida de vídeo./modelo,Devuelve la URL del vídeo.",
                parameters = mapOf(
                    "prompt" to "Requerido,Descripción del contenido del vídeo,Tanto el inglés como el chino están disponibles.",
                    "model" to "Opcional,modelo de vídeo ID;Selecciona automáticamente el primer modelo que admite salida de vídeo cuando no se especifica",
                    "provider_id" to "Opcional,proveedor ID;Selecciona automáticamente el primer proveedor que admite salida de video cuando no se especifica",
                    "duration" to "Opcional,Duración del vídeo(segundos),Sólo admite 5 o 10,Predeterminado 5",
                    "resolution" to "Opcional,resolución,Como por ejemplo 720p / 1080p,Predeterminado 720p",
                ),
                required = setOf("prompt"),
                riskLevel = ToolRiskLevel.HIGH,
                parameterTypes = mapOf("duration" to "integer"),
            ),
        ) { args -> impl.execGenerateVideo(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_qr_code",
                description = "poner cualquier texto(Como enlaces,WiFi Contraseña, información de contacto)Convertir a imagen de código QR,Y muéstralo en la conversación.",
                parameters = mapOf(
                    "content" to "Requerido,El texto que se codificará en un código QR.",
                    "size" to "Opcional,Longitud del lado del código QR en píxeles,Predeterminado 400,alcance 128-1024",
                ),
                required = setOf("content"),
                riskLevel = ToolRiskLevel.SAFE,
                parameterTypes = mapOf("size" to "integer"),
            ),
        ) { args -> impl.execGenerateQrCode(args) }

        // Mensajes de voz: síntesis local → Guardar audio local → Espera los propios mensajes del asistente.
        // Riesgo: NORMAL en lugar de ALTO: No hay transmisión externa, no se escribe ningún directorio de usuario. Si configura ALTO, la tarjeta de aprobación aparecerá cada vez que envíe un mensaje de voz.
        // Utilice el método de registro con contexto: el límite superior de la cantidad de mensajes de voz en esta ronda debe calcularse por "ronda" y la identificación de la ronda (identificación del mensaje del asistente)
        // Solo se puede obtener del ToolExecutionContext inyectado por el host y el modelo no se puede falsificar.
        toolRegistry.registerWithContext(
            ToolRegistry.ToolDef(
                name = "send_voice",
                // "Habla/voz/lectura/voz" en la descripción son todos términos de búsqueda de find_tools, no los elimines casualmente
                description =
                "Enviar voz: sintetice un fragmento de texto en un mensaje de voz (envíe voz/léalo en voz alta al usuario) y envíelo directamente en la conversación." +
                    "Sólo se debe poner un párrafo en cada mensaje de voz; una respuesta se puede llamar varias veces y debe enviarse párrafo por párrafo en orden. No abarrotes todo el artículo de una vez." +
                    "Incluya únicamente líneas que deban leerse en voz alta. No incluya narración, descripción de la acción, texto original en inglés ni Markdown." +
                    "Adecuado para tono, emoción y contenido que lo acompaña; Las descripciones, códigos y textos largos todavía se responden en texto." +
                    "Se consumirá el crédito TTS; Los párrafos sin caracteres chinos no generarán voz y se pueden realizar un máximo de 5 respuestas a la vez.",
                parameters = mapOf(
                    "text" to "Requerido,texto plano para ser leído(un párrafo,coloquial,Sin Markdown / bloques de código / enlaces)",
                    "emotion" to "Opcional,tono emocional,Como por ejemplo happy / sad / calm / excited;Déjelo en blanco para usar el estado de ánimo predeterminado en la configuración",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args, executionContext ->
            impl.execSendVoice(args, executionContext.messageId)
        }
    }
}
