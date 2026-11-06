plugins {
    alias(libs.plugins.android.application)
}

// v2.2.1 pantalla virtual:shell uid Servidor de procesos independiente.
//
// El producto es APK,pero nunca se instala —— Después de cambiar el nombre del frasco, la aplicación principal lo escribirá. /data/local/tmp,
// usar `CLASSPATH=<jar> app_process / io.zer0.muse.vdserver.Main <hostPkg>` Levantar.
//
// Todo Java + cero dependencias de terceros:Asegúrese de que el archivo único class.dex(Traer kotlin-stdlib aumentará la cantidad de dex,
// app_process CLASSPATH solo carga de manera confiable la primera sección dex).
android {
    namespace = "io.zer0.muse.vdserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.zer0.muse.vdserver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // app_process CLASSPATH solo puede cargar de manera confiable la sección clases.dex,orden forzada dex
        multiDexEnabled = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // v2.3.2: El propósito de este módulo es"suplemento framework"(Ver comentarios del encabezado del archivo)—— app_process El proceso no ha pasado
        // zygote Inicialización general de,Los estados privados como ActivityThread/DisplayManager solo se pueden completar mediante reflexión.
        // Por tanto, estos dos artículos quedan exentos por módulo."privado API"comprobar;El alcance de la exención se limita a estos dos elementos.,Las reglas de pelusa restantes entran en vigor como de costumbre.,
        // Esto realmente hará un mal uso de la API privada en el futuro.**Nuevo**El código seguirá bloqueado por otras reglas.
        // (El método de escritura de @SuppressLint es como golpear un topo.:Actualmente hay 5 archivos que realizan el mismo tipo de reflexión.)
        disable += setOf("BlockedPrivateApi", "SoonBlockedPrivateApi")
    }
}

dependencies {
    implementation(project(":virtual-display-protocol"))
}
