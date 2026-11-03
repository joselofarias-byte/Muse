package io.zer0.muse.web

import android.content.Context
import io.zer0.common.Logger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v2.x: Herramienta de certificado HTTPS autofirmado para servidores web integrados.
 *
 * diseño:
 *  - Genere clave RSA 2048 + certificado X.509 autofirmado al habilitar HTTPS por primera vez,persistir en filesDir(PKCS12 keystore),
 *    Reutilizar directamente después de cada inicio,No más regeneración;
 *  - El certificado sólo se utiliza para "cifrado LAN punto a punto":Navegador/El cliente avisará al acceder por primera vez."El certificado no es de confianza",
 *    El usuario puede comprobar [TlsMaterial.fingerprint] Confiar manualmente después de tomar las huellas digitales(Certificado autofirmado sin respaldo de CA pública);
 *  - La generación de certificados se construye con BouncyCastle y se firma con X.509 DER.BC(`bcpkix`/`bcprov`)Ya por
 *    `com.tom-roush:pdfbox-android` pasar a classpath,Esta solución no agrega ninguna dependencia nueva.
 *
 * hilo:Todos los métodos implican lectura y escritura de archivos y operaciones clave.,Debe llamarse en el hilo IO.
 */
internal object WebServerTls {

    private const val TAG = "WebServerTls"

    /** PKCS12 keystore nombre de archivo(almacenado en filesDir). */
    private const val KEYSTORE_FILE = "muse-web-tls.p12"

    /** keystore Nombre del archivo de contraseña(almacenado en filesDir,Directorio privado de aplicaciones). */
    private const val PASSWORD_FILE = "muse-web-tls.pwd"

    private const val KEYSTORE_TYPE = "PKCS12"

    /** Alias ​​de certificado(keystore Nombre interno del artículo). */
    const val ALIAS = "muse-web"

    /** Asunto del certificado. Los certificados autofirmados no realizan verificación de nombre de dominio,CN Sólo con fines de visualización. */
    private const val SUBJECT = "CN=muse-web, O=Muse, OU=Embedded Web Server"

    /** Periodo de validez(10 año):Certificado LAN autofirmado,Evite rotaciones frecuentes. */
    private const val VALIDITY_DAYS = 3650L

    /** RSA Longitud de la clave. */
    private const val KEY_SIZE = 2048

    /** Material TLS generado:keystore + Contraseña + huella digital del certificado. */
    data class TlsMaterial(
        val keyStore: KeyStore,
        val alias: String,
        val password: CharArray,
        /** Certificado SHA-256 de huella digital(hexadecimal mayúscula,colon separado),Para que los usuarios consulten la página de advertencia del navegador. */
        val fingerprint: String,
    )

    /**
     * Cargar un certificado autofirmado existente;Regenerar y persistir cuando no exista o esté corrupto.
     * Necesita ser llamado en el hilo IO(Implica lectura y escritura de archivos y generación de claves.).
     */
    fun loadOrCreate(context: Context): TlsMaterial {
        val keyStoreFile = File(context.filesDir, KEYSTORE_FILE)
        val passwordFile = File(context.filesDir, PASSWORD_FILE)

        loadExisting(keyStoreFile, passwordFile)?.let { return it }

        Logger.i(TAG, "Genere un certificado HTTPS autofirmado por primera vez(filesDir/$KEYSTORE_FILE)")
        return generate(keyStoreFile, passwordFile)
    }

    /** Intente cargar un existente keystore;Volver si falla algún enlace null(Retorno a la regeneración por parte de la persona que llama). */
    private fun loadExisting(keyStoreFile: File, passwordFile: File): TlsMaterial? {
        if (!keyStoreFile.exists() || !passwordFile.exists()) return null
        return try {
            val password = passwordFile.readText().trim().toCharArray()
            if (password.isEmpty()) return null
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            FileInputStream(keyStoreFile).use { keyStore.load(it, password) }
            if (!keyStore.containsAlias(ALIAS) || !keyStore.isKeyEntry(ALIAS)) return null
            val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return null
            TlsMaterial(keyStore, ALIAS, password, fingerprintOf(certificate))
        } catch (t: Throwable) {
            // No se trague CancellationException: respaldo solo en caso de falla real.
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w(TAG, "No se pudo cargar el certificado HTTPS existente,se regenerará: ${t.message}")
            null
        }
    }

    /** Generar par de claves y certificado autofirmado,Escriba archivos de almacén de claves y contraseñas. */
    private fun generate(keyStoreFile: File, passwordFile: File): TlsMaterial {
        val password = randomPassword()
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(KEY_SIZE) }
            .generateKeyPair()
        val certificate = selfSign(keyPair)

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))

        passwordFile.writeText(String(password))
        FileOutputStream(keyStoreFile).use { keyStore.store(it, password) }

        return TlsMaterial(keyStore, ALIAS, password, fingerprintOf(certificate))
    }

    /**
     * Construya y firme un certificado X.509 autofirmado.
     * El tema contiene localhost/127.0.0.1 SAN,Facilita la comparación entre clientes en el mismo computadora(El acceso IP LAN seguirá provocando una alarma debido a la autofirma).
     */
    private fun selfSign(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS))
        val serial = BigInteger(64, SecureRandom())
        val name = X500Name(SUBJECT)

        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            notBefore,
            notAfter,
            name,
            keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.dNSName, "localhost"),
                        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    ),
                ),
            )
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        // Autocomprobación:Expuesto inmediatamente cuando el certificado y la clave privada no coinciden,En lugar de esperar a que falle el protocolo de enlace TLS.
        certificate.verify(keyPair.public)
        return certificate
    }

    /** Certificado SHA-256 de huella digital(hexadecimal mayúscula,colon separado). */
    private fun fingerprintOf(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    /** Generar URL de contraseña aleatoria segura(keystore Contraseña,Colocado con el directorio privado de la aplicación.). */
    private fun randomPassword(): CharArray {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
    }
}
