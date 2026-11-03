package io.zer0.muse.ui.theme

import kotlinx.coroutines.flow.Flow

/**
 * Máscara de burbuja declarativa proporcionada por el complemento instalado.
 *
 * [skin] debe haber pasado [BubbleSkinValidator.isValid];[enabled] es falso significa que el complemento no está confirmado o deshabilitado,
 * En este momento, la entrada solo puede usarse en la página de configuración para mostrar "no disponible",No se debe ingresar la ruta de renderizado.
 */
data class InstalledSkin(
    val pluginId: String,
    val pluginName: String,
    val version: String,
    val enabled: Boolean,
    val skin: BubbleSkin,
)

/**
 * Fuente de máscara de complemento — La capa de renderizado del host solo se basa en esta interfaz estrecha,No depende directamente de la implementación del administrador de complementos.
 *
 * El implementador debe garantizar:[listInstalledSkins] Todas las máscaras devueltas han pasado por [BubbleSkinValidator] verificar,
 * y solo están disponibles actualmente(Confirmado, habilitado, firmado/confianza/Revisión del resumen aprobada)El complemento es [InstalledSkin.enabled] = true Regresar;
 * Después de deshabilitar, desinstalar o revocar la confianza de un complemento,La siguiente lectura no debe devolver sus máscaras disponibles.
 * Las máscaras son siempre solo datos declarativos.,Ningún código de complemento ni objetos Compose pueden cruzar este límite.
 */
interface PluginSkinSource {

    /**
     * Lea la máscara del complemento instalado.
     *
     * @param includeDisabled true También devuelve máscaras instaladas pero actualmente no disponibles.(Solicitar página de configuración),
     * De lo contrario, solo se devuelven máscaras que pueden ingresar a la ruta de renderizado.
     */
    fun listInstalledSkins(includeDisabled: Boolean = false): List<InstalledSkin>

    /** Señal de cambio de registro de complemento;Los cambios de valor indican que la lista de aspectos puede haber cambiado,El coleccionista debería volver a leer. */
    val revisionFlow: Flow<Int>
}
