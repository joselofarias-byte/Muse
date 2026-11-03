import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
}

// v2.2.1: release Las variantes se comparten con la aplicación principal. keystore.properties —— Permisos a nivel de firma
// io.zer0.muse.permission.A11Y_BRIDGE Requerir que la aplicación principal y el Proveedor tengan la misma firma,Debe tener la misma clave.
// (storeFile Las rutas relativas se resuelven por directorio de módulo.;Este módulo y la aplicación son submódulos directos del directorio raíz.,Los resultados del análisis son consistentes.)
val keystorePropertiesFile = rootProject.file("keystore.properties")

android {
    namespace = "io.zer0.muse.a11y"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.zer0.muse.a11y"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val props = Properties()
                props.load(FileInputStream(keystorePropertiesFile))
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Implementación compartida y contrato AIDL.(io.zer0.muse.accessibility)
    implementation(project(":accessibility"))
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
