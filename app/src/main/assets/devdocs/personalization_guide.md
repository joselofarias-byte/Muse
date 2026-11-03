<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Guía completa de personalización.

> escena desencadenante: Usuario preguntó"Cómo cambiar el tema""Cómo cambiar la fuente""¿Para qué sirven los retratos de usuarios?""Cómo hacer que la IA me entienda mejor"tiempo,Consulte este documento y responda con sinceridad.
> Este documento se basa en la implementación real del código fuente AppearanceSettingsStore.kt/SettingsScreen.kt.

## 1. Descripción general

La personalización se divide en dos partes.:
1. **Apariencia**: Tema(Selección de color dinámica/Personalizar/Predeterminado), fuente,Markdown dejar en blanco
2. **Retrato de usuario**: Información básica del usuario,Inyecte el mensaje del sistema para permitir que la IA comprenda mejor al usuario

## 2. Sistema temático(Respaldo de la capa 3)

Prioridad de mayor a menor:
1. **Material You Selección de color dinámica**(dynamicColor): Sigue el fondo de pantalla del sistema para elegir colores.(Android 12+)
2. **Tema personalizado**(customTheme): Generar según el color de la semilla. ColorScheme,Personalizable primary/secondary/tertiary
3. **Tema predeterminado**(presetTheme): Múltiples conjuntos de colores incorporados

cadena de reserva: La selección dinámica de colores está disponible → úsalo;De lo contrario, personaliza el tema.;De lo contrario, se utiliza el tema predeterminado.

### Configuración
- ajustes → Apariencia → Tema
- Tema personalizado: Elige el color de la semilla → Genera automáticamente un conjunto completo de colores.;Se pueden ajustar tres colores principales
- Tema predeterminado: Múltiples conjuntos integrados(oscuro/color claro/Color etc)

## 3. Fuentes y composición tipográfica

- ajustes → Apariencia → fuente
- Opciones: Predeterminado del sistema/múltiples fuentes integradas
- Markdown El espacio en blanco es ajustable.(Comodidad de lectura)

## 4. Retrato de usuario

### 4.1 campo
| campo | Descripción |
|---|---|
| título | AI Nombre del usuario(Como por ejemplo"Ziqi") |
| edad | - |
| ciudad | - |
| MBTI | tipo de personalidad |
| carrera | - |
| Interés | Lista de pasatiempos separados por comas |

### 4.2 mecanismo de inyección
- Inyección de contenido de imagen system prompt,Deje que la IA se manifieste naturalmente en las conversaciones(título/charla de la ciudad/Recomendación de interés)
- Después de la modificación**El nuevo mensaje entra en vigor**(La información histórica se mantiene sin cambios.)

## 5. Opciones de apariencia del chat

| Opciones | Descripción |
|---|---|
| Expandir por defecto | AI Los mensajes largos se expanden de forma predeterminada./doblar |
| visualización de mensajes | Burbujas/Marca de tiempo/Opciones de visualización como avatares. |
| temperatura | Temperatura de muestreo predeterminada global(0-2) |
| Avanzado | longitud del contexto, etc. |

## 6. Preguntas frecuentes Q&A

1. **"No hay respuesta después de cambiar el tema."**: Confirma que la aplicación ha desbloqueado el tema.;La selección dinámica de colores requiere Android 12+ y Material You está activado en el sistema.
2. **"Quiero que la IA me llame con un apodo"**: ajustes → Apariencia → Retrato de usuario → Completa el título,Los nuevos mensajes entran en vigor.
3. **"Cómo restaurar la fuente a los valores predeterminados"**: Apariencia → fuente → Seleccione el valor predeterminado del sistema.
4. **"¿Para qué utilizará la IA los retratos de los usuarios?"**: inyectar system prompt,Deja que AI use tu título y charle sobre tu ciudad/Temas relacionados con intereses;No se subirá a la nube.
5. **"¿Son iguales los retratos de todos los asistentes?"**: Los retratos de usuarios son globales;Los asistentes difieren según la configuración de avisos de su sistema.

## 7.LLM Puntos de llamada

- Título en retrato de usuario/ciudad/Intereses, etc.,usado naturalmente en la conversación(llamar/Mencionar el clima de la ciudad./Temas de interés recomendados)
- No leas el contenido del retrato con rigidez.(Como por ejemplo"Según tu MBTI eres INTJ"),integración natural
- Después de que el usuario modifica el retrato.,Chat por mensaje nuevo
- No preguntes al usuario"cuales son tus intereses"Cuando ya hay en el retrato.
