plugins {
    alias(libs.plugins.android.library)
}

// v2.2.1 pantalla virtual:módulo de contrato(AIDL + constante).
// Utilice Java deliberadamente por completo: no se puede incluir el dex del módulo del servidor kotlin-stdlib(Límite de clases individuales.dex),
// El código Java del lado del servidor hará referencia directa a la clase de contrato.,Mantenga cero dependencias.
android {
    namespace = "io.zer0.muse.vdproto"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
