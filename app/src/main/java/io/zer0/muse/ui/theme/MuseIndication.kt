package io.zer0.muse.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * Indicador de prensa global sin ondas — No hay superposición visual cuando se presiona.
 *
 * fondo:Anteriormente, Material3 se usaba globalmente de forma predeterminada. ripple(máscara de difusión circular). Su radio es"Punto de contacto con el componente
 * esquina más alejada"calcular,Tarjetas en ancho de línea completo/Al hacer clic en la fila de la lista, se desplegará un gran círculo gris que cubrirá la mayor parte de la tarjeta.,
 * Ruido visual excesivo. decisiones de producto:Quitar todas las máscaras de prensa circular.,La respuesta de la prensa se unifica y se entrega al componente para que lo dibuje por sí mismo.
 * (Por ejemplo, la animación de compresión del botón emergente.).
 *
 * Por MuseTheme `LocalIndication provides MuseNoRippleIndication` inyección global;
 * M3 La ondulación dentro del componente se compone de la misma capa. `LocalRippleConfiguration provides null` Cerca.
 */
object MuseNoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoOpIndicationNode()

    // IndicationNodeFactory Declarar iguales/hashCode como miembros abstractos(La igualdad participa en la optimización de Compose):
    // Esta fábrica es un singleton.,Comparación de identidad;hashCode Utilice constantes fijas para satisfacer"Misma constante de fábrica"contrato.
    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0x4D757365 // "Muse"
}

/**
 * implementación vacía — no dibujes nada,Sólo los marcadores de posición cumplen el protocolo de indicación.
 *
 * debe ser cada vez [MuseNoRippleIndication.create] Nueva instancia:el mismo Modifier.Node
 * No se puede utilizar en varios lugares. attach(Violará el contrato del ciclo de vida del nodo).
 */
private class NoOpIndicationNode : Modifier.Node()
