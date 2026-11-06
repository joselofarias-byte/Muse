package io.zer0.muse.data.plugin.market

/**
 * El directorio oficial de complementos integrado en el paquete de instalación.
 *
 * El mercado de complementos está listo para usar desde el primer momento: la dirección del directorio y la raíz de confianza del directorio se publican junto con la aplicación y los usuarios no necesitan ninguna configuración.
 * (La página de configuración aún conserva la entrada de cobertura para directorios de prueba o cambio de emergencia, consulte [PluginMarketSettings]).
 *
 * Acuerdo de seguridad:
 *  - La clave pública aquí es**Directorio firmando raíz de confianza**, independientemente de la raíz de confianza del emisor confirmada por el usuario en el dispositivo;
 *  - La raíz de confianza incorporada siempre participa en la verificación de la firma y la configuración del usuario no puede eliminarla ni sobrescribirla para evitar un mal funcionamiento e invalidar el directorio oficial;
 *  - El contenido del directorio en sí aún debe pasar la verificación de firma. Lo que está integrado es "en qué clave confiar", no "confiar en el contenido".
 */
object PluginMarketDefaults {

    /** Dirección del directorio oficial; debe ser https y resolverse en una dirección de red pública (la corrección de DNS y la verificación SSRF se realizarán en la salida). */
    const val CATALOG_URL = "https://museai.ltd/muse-market/signed-catalog.json"

    /** Identificador de directorio; la secuencia aceptada se registra por separado y no se sobrescribirá entre sí al cambiar de directorio. */
    const val CATALOG_ID = "museai-official"

    /** El keyId raíz de confianza del directorio debe ser el mismo que el keyId en el directorio firmado JSON. `keyId` Acuerdo literal. */
    const val CATALOG_ROOT_KEY_ID = "museai-catalog-1"

    /** Clave pública raíz de confianza del directorio:P-256 El X.509 SubjectPublicKeyInfo, línea única estándar Base64. */
    const val CATALOG_ROOT_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7R5oDYokrWIXNgHa7ZUrREnSv2enqzd4aJ+O8MMrDMWHBom78kw1wfJX/hsZ1v34W9tlftp26fRq/26UOJ7alQ=="

    /** Clave pública Huella digital SHA-256; solo se usa para autoprueba y visualización para facilitar la verificación manual de que el valor incorporado es consistente con el lado de publicación. */
    const val CATALOG_ROOT_FINGERPRINT = "33b85c984cb4cb695ff93042523de7ecaabf2a4d937bcab7b9b2d33feb210d8d"

    /** Conjunto integrado de raíces de confianza; con [CATALOG_ROOT_FINGERPRINT] Correspondencia uno a uno. */
    val catalogRootKeys: Map<String, String> = mapOf(CATALOG_ROOT_KEY_ID to CATALOG_ROOT_PUBLIC_KEY)
}
