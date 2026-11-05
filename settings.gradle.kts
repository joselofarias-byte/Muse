pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "muse"

// Lista de módulos(Implementado desde cero módulo por módulo)
// Phase 1: Habilite solo los módulos necesarios para el cierre del diálogo.,El resto se activará según sea necesario en la Fase 5.
include(":app")          // Módulo de aplicación principal(UI/ViewModel/lógica central)
include(":ai")           // AI SDK capa de abstracción(Provider/Model/Tool/UIMessage)
include(":memory")       // sistema de memoria
include(":common")       // Herramientas y extensiones comunes
// v1.97 gap7: Habilitar módulo:material3 — Montar el código fuente de material-color-utilities,
// proporcionar DynamicScheme.toColorScheme() Expandir,Para que CustomTheme se genere en función del color de la semilla ColorScheme
include(":material3")    // Material extensión de color(color de semilla → ColorScheme generador)
// Pequeño módulo independiente para teléfono celular.:Viene con biblioteca de sala e interfaz de host,no depende de :app
include(":miniphone")
// P3-3: Módulo independiente del servicio de accesibilidad — MuseAccessibilityService + AIDL interfaz,
// Como una biblioteca dependía de: aplicación,Los servicios se compilan directamente en el archivo principal. APK(Misma aplicación bindService,No es necesario instalar entre APK)
include(":accessibility")  // Servicios de accesibilidad(UI Base de capacidad de automatización)
// v2.2.1: Accesible e independiente Provider APK —— Actualización de la aplicación principal/La reinstalación no interrumpe los servicios de accesibilidad.
include(":accessibility-provider")
// v2.2.1: pantalla virtual(shell uid proceso independiente) —— Módulo de contrato + módulo de servidor
include(":virtual-display-protocol")
include(":virtual-display-server")
// include(":search")       // Función de búsqueda SDK(Exa/Tavily/Zhipu/Bing espera)        — Phase 5
// include(":speech")       // Voz TTS/ASR                                   — Phase 5
// include(":document")     // Análisis de documentos(PDF/DOCX/PPTX/EPUB)                  — Phase 5
// include(":highlight")    // Resaltado de sintaxis de código                                   — Phase 5
// include(":workspace")    // proot Linux entorno de caja de arena                           — Phase 5
// include(":web")          // Servidor web integrado                              — Phase 5
