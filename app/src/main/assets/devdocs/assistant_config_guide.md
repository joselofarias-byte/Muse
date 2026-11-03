<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Guía completa para la configuración del asistente

> escena desencadenante: Usuario preguntó"Cómo crear un asistente""¿Qué configuraciones tiene el asistente?""Cómo combinar modelos exclusivos""Cómo configurar la lista blanca de herramientas""Cómo compartir asistente"tiempo,Consulte este documento y responda con sinceridad.
> Este documento está basado en el código fuente. AssistantEntity.kt / AssistantRepository.kt / AssistantDetailPages.kt verdadera realización.

## 1. Descripción general

Muse cada asistente(Agent)es un**personalidad independiente**:Tiene sistema independiente, modelo, lista blanca de herramientas, lista blanca de habilidades, configuración de memoria y extensiones.(Lorebook/Inyección rápida de palabras/mensaje rapido). Múltiples asistentes pueden colaborar(delegate_agent/chat grupal).

- entrada: ajustes → asistente
- El asistente principal existe por defecto.(id="default",nombre"Asistente predeterminado")
- Número ilimitado de asistentes,Cada asistente se configura de forma independiente

## 2. Crear/Editar/Eliminar

1. ajustes → asistente → Crear nuevo asistente
2. Completa el nombre(Requerido),Seleccione avatar, modelo
3. Editar la configuración de cada subpágina(Ver abajo)
4. Eliminar: Lista de asistentes pulsación larga → Eliminar(No recuperable,Presta atención para confirmar.)

## 3. Explicación detallada de 5 subpáginas de detalles del asistente

### 3.1 Basic(Conceptos básicos)
| campo | Descripción | Predeterminado |
|---|---|---|
| avatar | avatar personalizado | Icono predeterminado |
| Nombre | nombre para mostrar | - |
| modelId / providerId | **modelo exclusivo**: Después de especificarlo, el asistente siempre utilizará este modelo.,No afectado por el cambio global | vacío=Usar modelo global |
| Exportación de tarjeta asistente | Exportar a archivo/código qr | - |

### 3.2 Prompt(palabra rápida)
| campo | Descripción |
|---|---|
| systemPrompt | Configuración de personalidad/reglas de conducta(apoyo {{var}} Variables de plantilla) |
| messageTemplate | Plantilla de mensaje(Pebble subconjunto compatible) |
| presetMessagesJson | Mensaje predeterminado(Palabras de apertura, etc.) |

### 3.3 Extensions(Expandir)
| campo | Descripción |
|---|---|
| toolIdsJson | Lista blanca de herramientas(`[]`=Todos;matriz=Habilitar solo los listados) |
| skillIdsJson | Lista blanca de habilidades(Todo integrado de forma predeterminada) |
| mcpServerIdsJson | vinculante MCP server(La conexión se realiza correctamente y el asistente principal se vincula automáticamente.) |
| Lorebook | Configuración de la visión mundial,Activar inyección por palabra clave |
| PromptInjection | Inyecte palabras adicionales según las condiciones. |
| QuickMessage | mensaje rapido chips |

### 3.4 Memory(memoria)
| campo | Descripción | Predeterminado |
|---|---|---|
| memoryEnabled | El asistente usa la memoria | true |
| useGlobalMemory | Usar memoria compartida global(De lo contrario, memoria independiente) | true |
| enableRecentChatsReference | Inyectar referencias de sesiones recientes | false |
| enableTimeReminder | inyección de memoria dependiente del tiempo | true |

### 3.5 Advanced(Avanzado)
| campo | Descripción | Predeterminado |
|---|---|---|
| temperature | Temperatura de muestreo | incumplimiento global |
| reasoningLevel | nivel de pensamiento | AUTO |
| Encabezado de solicitud | Encabezados HTTP personalizados(Algunos servicios modelo requieren) | - |

## 4. La relación entre el modelo excluyente y el modelo global(centrarse)

- El asistente está configurado. modelId(modelo exclusivo): el asistente**Modelo exclusivo para uso fijo.**,Se sobrescribirá el cambio de modelos en una sesión.(Aviso al cambiar"Se han cubierto los modelos específicos de asistente.",Recuperación de sesión de cambio)
- Asistente no configurado: Usar modelo predeterminado global(ajustes→Modelos y Servicios)
- modelo de herramienta(toolModelId): Un modelo liviano para rondas de llamadas de herramientas.,per-assistant Disponible

## 5. Comprobación rápida de la semántica de la lista blanca

| Configuración | vacío/Predeterminado | Especificar |
|---|---|---|
| toolIdsJson | Todas las herramientas | Habilitar solo aquellos en la lista |
| skillIdsJson | todas las habilidades | Habilitar solo aquellos en la lista |
| mcpServerIdsJson | Ninguno MCP | en la lista vinculante server |
| memoryEnabled | true | false=No hay necesidad de recordar |

## 6. Asistente para compartir e importar

- Exportar: Detalles del asistente → Tarjeta de exportación → Generar archivo o código QR
- importar: Escanea el código QR o importa el archivo → Generar nuevo ayudante(Contiene mensaje/lista blanca/configuración de memoria)
- compartir con otros: La otra parte escanea el código para obtener el mismo asistente de configuración.

## 7. Preguntas frecuentes Q&A

1. **"Cómo permitir que cada asistente use diferentes modelos"**: Detalles del asistente → Basic → Elige tu propio modelo.
2. **"¿Por qué cortar el modelo no tiene ningún efecto?"**: El asistente está configurado con un modelo dedicado.;El cambio manual dentro de una sesión se sobrescribirá(Consejos),La recuperación de sesiones cortadas es exclusiva.
3. **"Cómo hacer que el asistente utilice sólo algunas herramientas"**: Extensions → Lista blanca de herramientas,Cancela lo que no necesitas.
4. **"El nuevo asistente MCP no se puede utilizar"**: Asistente de confirmación Extensions → MCP el server(El asistente principal recién conectado se vinculará automáticamente).
5. **"Cómo hacer una copia de seguridad de la configuración del asistente"**: Tarjeta de exportación(Archivo/código qr),Sólo guárdalo.
6. **"systemPrompt ¿Qué variables se pueden utilizar en"**: {{user_name}}/{{char}}/{{date}} espera,Pebble Filtros de soporte de subconjuntos/bucle/condiciones.

## 8.LLM Puntos de llamada

- El asistente es una personalidad independiente.: Estilo de respuesta/El comportamiento se basa en el sistema de ayuda.
- Usuario preguntó"quien eres tu/¿Qué asistente eres?"tiempo,Responder según la configuración actual del asistente
- No mezcle recuerdos de varios asistentes/identidad(scope El aislamiento está garantizado por el sistema.)
- Usuario mencionado"Modificar la configuración del asistente"tiempo,Arrancar en Configuración → asistente(No cambie directamente la configuración a nivel de herramienta,Evite sobrepasar su autoridad)
