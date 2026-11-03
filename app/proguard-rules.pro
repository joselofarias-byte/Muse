# muse ProGuard reglas
# release Habilitado isMinifyEnabled=true + isShrinkResources=true(R8 Ofuscación de compresión total).
# Phase 11.4: Mejorar el conjunto de reglas,Cubre todas las dependencias. suplemento:Compose / io.zer0.ai / KSP Generar clase.

# ============================================================================
# Universales:Mantenga anotaciones, clases internas y firmas genéricas.(Dependencias de reflexión/serialización)
# ============================================================================
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, RuntimeInvisibleTypeAnnotations

# ============================================================================
# Kotlin:Preservar metadatos(para reflexionar)+ @Metadata anotación
# ============================================================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata *;
}
-dontnote kotlinx.serialization.AnnotationsKt

# ============================================================================
# kotlinx.serialization:Objeto complementario serializador + clase @Serializable
# ============================================================================
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Mantenga todas las clases anotadas @Serializable(Entidad de proyecto + biblioteca dependiente DTO)
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class *
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# ============================================================================
# Clase de entidad del proyecto:io.zer0.* @Serializable/las entidades de sala bajo paquetes deben conservar los nombres de los campos
# (Room Nombres de columnas reflejadas/nombres de campos serializados JSON/compatibilidad de importación y exportación de copias de seguridad)
# ============================================================================
-keep class io.zer0.muse.data.** { *; }       # SessionEntity/MessageEntity/AssistantEntity espera
-keep class io.zer0.memory.** { *; }          # FactEntity/SessionSummaryEntity espera
-keep class io.zer0.ai.core.** { *; }         # UIMessage/ChatRequest/ChatCompletion espera
-keep class io.zer0.muse.backup.** { *; }     # Backup clase de datos
-keep class io.zer0.muse.mcp.** { *; }        # MCP clase de datos(McpConfig/McpOAuthConfig espera)
-keep class io.zer0.muse.asr.** { *; }        # AsrConfig
-keep class io.zer0.muse.balance.** { *; }    # BalanceConfig

# ============================================================================
# Room:clase de entidad + DAO(Anulado a través de io.zer0.muse.data arriba,Agregue información relacionada con la base de datos aquí)
# ============================================================================
-dontwarn androidx.room.paging.**
-keep class androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# ============================================================================
# Koin:Clase de módulo + inyección reflectante.
# ============================================================================
-keep class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}

# ============================================================================
# OkHttp + OkHttp SSE:Clases relacionadas con la plataforma + ConnectionSpec
# ============================================================================
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# M-2: Anotado — OkHttp Trae el tuyo consumer-rules.pro,No se requiere manual -keep,La retención excesiva evita que R8 elimine el código inactivo
# -keep class okhttp3.** { *; }
# -keep interface okhttp3.** { *; }

# ============================================================================
# Coil:ImageLoader decodificador(SvgDecoder / GifDecoder Registro de reflexión)
# ============================================================================
# M-2: Anotado — Coil Trae el tuyo consumer-rules.pro,No se requiere manual -keep,La retención excesiva evita que R8 elimine el código inactivo
# -keep class coil.** { *; }
# -keep class coil.decode.** { *; }
# v1.112 (F3): Preservar el decodificador de GIF animado(ImageDecoderDecoder / GifDecoder)y
# AnimatedImage/AnimatedDrawable Clases relacionadas.R8 La ofuscación puede interrumpir el registro de reflexión y el ciclo de vida de Animatable,
# Hace que el GIF muestre solo el primer fotograma y se convierta en una imagen estática.
-keep class coil.decode.GifDecoder* { *; }
-keep class coil.decode.ImageDecoderDecoder* { *; }
-keep class coil.decode.AnimationDecoder { *; }
-keep class coil.decode.FrameDecoder { *; }
-keep class coil.image.AnimatedImage { *; }
-keep class coil.drawable.AnimatedDrawable* { *; }
-keep class android.graphics.Movie { *; }
-keep class android.graphics.ImageDecoder { *; }
-keep class android.graphics.ImageDecoder$* { *; }

# ============================================================================
# PDFBox-Android:Recursos de fuentes + carga reflectante
# ============================================================================
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**

# ============================================================================
# ML Kit OCR / ML Kit Text Recognition
# ============================================================================
# M-2: Anotado — ML Kit Trae el tuyo consumer-rules.pro,No se requiere manual -keep,La retención excesiva evita que R8 elimine el código inactivo
# -keep class com.google.mlkit.** { *; }
# -keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**

# These entry points are instantiated by class name at runtime. Dependency consumer rules keep
# the class declarations but not necessarily their public no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

# ============================================================================
# v1.55: jtokkit(BPE tokenizer)— Preservar los recursos de la tabla de codificación y las clases cargadas reflexivamente
# ============================================================================
-keep class com.knuddels.jtokkit.** { *; }
-dontwarn com.knuddels.jtokkit.**

# ============================================================================
# AndroidX Compose:Composable Función reservada(El compilador ha procesado,Aquí está el resultado final)
# ============================================================================
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ============================================================================
# llamada reflejada Java API(JJWT / S3 Firma/WebDAV, etc.)
# ============================================================================

# ============================================================================
# Retención de pila de fallos:Throwable Subclase(Conveniente para que CrashHandler informe)
# ============================================================================
-keep class java.lang.Throwable { *; }
-keep class * extends java.lang.Throwable { *; }

# Shizuku UserService is instantiated by reflection in a non-Android process.
# Keep the class, AIDL Stub methods and constructors across release shrinking.
#
# v2.6.6 Reparación: Resulta que solo se conservó el ShellService y se omitieron la interfaz AIDL y el Stub.R8 poner
# IShellService / IShellService$Stub / $Stub$Proxy Como“Sólo se utiliza dentro de este proceso.”despegar,
# Como resultado, bindUserService en el paquete de lanzamiento nunca puede esperar a onServiceConnected:
#   Bit de autorización concedido (el asistente muestra que ha sido autorizado)→ ensureServiceBound() tiempo de espera →
#   shellEnabled=false → El perfil de capacidad le dice al modelo“Shell No disponible” → El asistente dijo que no había permiso.
# Evidencia de prueba:debug El paquete clases18.dex tiene IShellService/$Stub/$Stub$Proxy,
# release No puedo encontrar ninguno de los tres dex en el paquete (dexdump -f No hay resultados en la búsqueda completa).
# UserService Creado por Shizuku en una reflexión de proceso independiente y llamado entre procesos, el nombre de la interfaz es el mismo que
# TRANSACTION Los métodos deben conservarse tal como están y no se les puede cambiar el nombre ni eliminarlos.
-keep class io.zer0.muse.tools.system.IShellService { *; }
-keep class io.zer0.muse.tools.system.IShellService$Stub { *; }
-keep class io.zer0.muse.tools.system.IShellService$Stub$Proxy { *; }
-keep class io.zer0.muse.tools.system.ShellService { *; }

# ============================================================================
# enumeración:values() / valueOf reflexión
# ============================================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# R8 compatibles:Evite la optimización excesiva que resulte en ClassCastException
# ============================================================================
-allowaccessmodification
# M-12: Anotado — -overloadaggressively arriesgado,Puede provocar un conflicto en el nombre del método, lo que provoca un error en la llamada de reflexión.
# -overloadaggressively
-repackageclasses ''

# ============================================================================
# v1.49: Ktor Referencias a clases propietarias de JVM(java.lang.management.*),Android No disponible,
# R8 Reportar falta de clase. Solo se hace referencia a estas clases en la ruta de instrumentación de depuración de IntelliJ.,No se alcanzará durante el tiempo de ejecución,
# Simplemente suprímalo con -dontwarn.
# ============================================================================
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ============================================================================
# suplemento(Phase: release habilitar R8):Compose Reservado en su totalidad
# Originales androidx.compose.runtime.** No hay suficientes bolsillos,Se expande a todo el paquete androidx.compose,
# Evite que R8 elimine accidentalmente funciones componibles/clases de temas/implementaciones de modificadores.
# ============================================================================
# M-1: Anotado — Compose Trae el tuyo consumer-rules.pro,La conservación completa evitará que R8 elimine el código inactivo,
# Aumenta significativamente el tamaño del paquete. Las reglas ascendentes han conservado las implementaciones necesarias de función componible/modificador.
# -keep class androidx.compose.** { *; }
# -keep interface androidx.compose.** { *; }

# ============================================================================
# suplemento:io.zer0.ai Módulo completo DTO reservado
# Sólo se conserva el original io.zer0.ai.core.**,Esto se extiende a todo el módulo ai.,
# Cubre todas las clases de datos @Serializable en el subpaquete ai(ChatRequest / ChatCompletion espera).
# ============================================================================
-keep @kotlinx.serialization.Serializable class *

# ============================================================================
# suplemento:KSP clase generada(Room DAO darse cuenta / kotlinx.serialization $serializer)
# KSP La clase generada no lleva la anotación @Keep,R8 Puede eliminarse por error,Reservado explícitamente.
#   - **_Impl:Room DAO Clase de implementación(Como por ejemplo SessionDao_Impl)
#   - **_$serializer:kotlinx.serialization Serializador generado por el compilador
#   - Companion / INSTANCE:KSP Objetos complementarios generados y singletons.
# ============================================================================
-keep class **_Impl { *; }
-keep class **_$serializer { *; }
-keepclassmembers class * {
    *** Companion;
    *** INSTANCE;
}

# v2.2.0: Netty(ktor-server-netty, HTTPS Planificar A)Se introdujo la supresión de pérdida de dependencia opcional —
# estas clases(tcnative OpenSSL acelerar/log4j/jetty-npn)No se cargará cuando se ejecute en Android(ir JDK SSL+NIO), Alarma de verificación estática R8 únicamente.
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego

# v2.6.3 (issue #4): KTOR-7298 —— Ktor 3.0.x En la versión de lanzamiento ofuscada de R8,
# engine factory La sonda ServiceLoader obtendrá múltiples candidatos,interno single() tirar
# `IllegalArgumentException: Array has more than one element`,WebServer Nunca te levantes.
# Aunque actualizado a Ktor 3.1.3(Solución oficial),Aquí todavía mantenemos la convención ServiceLoader con engine
# Clase de implementación,Evite que futuros cambios de dependencia provoquen problemas similares nuevamente.
-keep class io.ktor.server.engine.ApplicationEngineFactory { *; }
-keep class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }
-keep class io.ktor.server.engine.EngineMain { *; }
-keep class io.ktor.server.netty.NettyApplicationEngine { *; }
-keep class io.ktor.server.netty.NettyApplicationEngine$Configuration { *; }

# v2.6.14: Netty Eliminado de las dependencias (Web servicio utiliza el motor CIO en su lugar),
# Netty de múltiples reflejos (ReflectiveChannelFactory,ResourceLeakDetector inicialización estática)
# El paquete de versión ofuscado de R8 se eliminará uno por uno, lo que provocará que el servidor no pueda iniciarse;CIO Es una implementación pura de Kotlin y no tiene este problema.
# A continuación se incluyen algunas advertencias para dar cabida a referencias opcionales residuales de terceros.
-keepnames class io.ktor.server.engine.ApplicationEngineFactory
-keepnames class * implements io.ktor.server.engine.ApplicationEngineFactory
-keepattributes *Annotation*
-keep class io.ktor.server.config.** { *; }
-keep class io.ktor.util.logging.** { *; }
-keepclassmembers class * extends io.ktor.server.engine.BaseApplicationEngine { *; }


# ============================================================================
# R8 Optimización desactivada
#
# MessageBubble es una sola fila de más de 2000,70+ Parámetro componible, el uso del registro local excede
# 200 ranura.R8 La optimización (integración de métodos/reordenamiento de registros/fusión de clases) producirá
# El verificador ART de Android 14+ rechaza el dex con tipos inconsistentes, que aparece como un clic directo para abrir el diálogo.
# tirar VerifyError: copy-cat1 ... type=Reference: MutableState.
#
# La ofuscación aún se conserva después de desactivar la optimización (nombre de clase/Reescritura de nombres de miembros) y compresión de recursos, la protección del código fuente no se ve afectada.
# El precio es que el cuerpo del paquete aumenta. La solución radical es desmontar el cuerpo del método en trozos más pequeños. Una vez que se completa el desmontaje, se puede quitar el interruptor.
-dontoptimize
