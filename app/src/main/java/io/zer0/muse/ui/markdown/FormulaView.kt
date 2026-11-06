package io.zer0.muse.ui.markdown

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer

/** M8 Reparación: Longitud máxima del código fuente de la fórmula,Si se excede, se reducirá a texto sin formato.,Evite la presión de la memoria de WebView. */
private const val MAX_LATEX_LENGTH = 5000

/**
 * Phase 8.6: LaTeX representación de fórmula(WebView + KaTeX).
 *
 * darse cuenta:
 *  - Insertar con AndroidView WebView
 *  - Cargar KaTeX CSS+JS(Carga sin conexión desde activos/proveedores locales/,v1.97 Transformación)
 *  - llamar katex.render(latex, element, {displayMode: true}) renderizado
 *  - fondo transparente,color del texto MaterialTheme.onBackground
 *
 * Comparación de volumen:
 *  - JLatexMath: ~2MB(Pero requiere la versión de implementación de Android.,La versión oficial usa java.awt y no está disponible.)
 *  - KaTeX via WebView: ~305KB APK Volumen(katex.min.js ~280KB + katex.min.css ~25KB,empacado en assets/vendor/)
 *
 * limitaciones:
 *  - WebView Empezar con ~50ms gastos generales
 *  - No todos los entornos AMS son compatibles(Pero KaTeX ya cubre el 95% de la sintaxis LaTeX más utilizada.)
 *
 * Instrucciones de seguridad: v1.97 Dado que los recursos de KaTeX se han cargado desde activos locales(base URL para file:///android_asset/),
 * Ya no depende de ningún control remoto CDN,Sin riesgo de secuestro o SRI.RichContentWebViewClient Intercepta toda la navegación de nivel superior.
 *
 * M8 Instrucciones de optimización de memoria: Cada bloque de fórmula se crea de forma independiente. WebView,Hay mucha presión en la memoria cuando hay mensajes largos y múltiples fórmulas.;
 * Cuando la longitud del látex excede [MAX_LATEX_LENGTH] bajar a renderizado de solo texto,WebView ya no se crea.
 *
 * @param latex LaTeX Código fuente de la fórmula(no contiene $$ delimitador)
 * @param modifier Modificaciones de diseño externo(M-MD7 Nuevo)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FormulaView(latex: String, modifier: Modifier = Modifier) {
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val textColorHex = String.format("#%06X", 0xFFFFFF and onBackgroundColor.toArgb())
    // L-MD14 Reparación: Utilice MaterialTheme.error para los colores de error en lugar de codificar #B00020
    val errorColorHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.error.toArgb())

    // M8 Reparación: Las fórmulas demasiado largas se degradan al texto original,Evite la presión de la memoria causada por la creación de WebView para cada bloque de fórmulas
    // Limitaciones conocidas: Los gráficos de fórmula no se representan después de degradar,Mostrar solo el código fuente original de LaTeX;Los escenarios con mensajes largos y múltiples fórmulas se guardan primero en la memoria.
    if (latex.length > MAX_LATEX_LENGTH) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = latex,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        Spacer(Modifier.height(6.dp))
        return
    }

    // Almacenamiento en caché de plantillas HTML:latex O se regenerará cuando cambie el color.
    val html = remember(latex, textColorHex, errorColorHex) {
        buildHtml(latex, textColorHex, errorColorHex)
    }

    Spacer(Modifier.height(6.dp))
    // stringResource Debe extraerse en la ubicación de llamada directa de @Composable,no puedo estar ahí semantics{} Para uso interno.
    val formulaCd = stringResource(R.string.markdown_formula_cd, latex)
    // v1.88 Reparación: Usar en su lugar LifecycleAwareWebViewContainer,Procesamiento automático ON_PAUSE/ON_RESUME/ON_DESTROY,
    // Resuelva el temporizador JS relacionado con el renderizado KaTeX cuando la actividad está en segundo plano/La animación continúa funcionando y consume energía.
    // Se han eliminado las limitaciones conocidas del L9 original.;La lógica de lanzamiento original v0.53 onRelease está unificada por el contenedor.
    // v1.97: base URL para file:///android_asset/ para cargar local KaTeX
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = "file:///android_asset/",
        javaScriptEnabled = true,
        // L11 Reparación: KaTeX No se requiere almacenamiento DOM,mantener predeterminado false
        domStorageEnabled = false,
        // Intercepta toda la navegación de nivel superior,Los recursos utilizan activos locales y no requieren listas blancas remotas.
        webViewClient = RichContentWebViewClient(),
        // M-MD7 Reparación: aceptar el exterior modifier;M-MD8 Reparación: height(80.dp) Cambiar a heightIn(min=80.dp)
        // M5 Reparación: Agregue descripción de contenido para una lectura sin barreras
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .semantics { contentDescription = formulaCd },
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * Construya HTML que contenga un script de renderizado KaTeX.
 *
 * - KaTeX CSS+JS Carga desde activos locales/proveedor/(v1.97 Transformación,Sin dependencia de CDN)
 * - Llamado automáticamente después de cargar la página. katex.render()
 * - displayMode: true corresponder $$...$$ fórmula a nivel de bloque
 * - throwOnError: false Deje que se muestre el texto de látex original en lugar de fallar cuando falla el análisis
 *
 * @param latex LaTeX código fuente
 * @param textColorHex Color del texto(ARGB girar #RRGGBB)
 * @param errorColorHex Color del mensaje de error(L-MD14: La persona que llama pasa el color del error del tema)
 */
private fun buildHtml(latex: String, textColorHex: String, errorColorHex: String): String {
    // Escapar de personajes especiales en látex.,Evite romper cadenas JS
    val escaped = latex
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("</", "<\\/") // protegerse contra </script> inyectar
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<!-- M-MD11 Reparación: Quitar user-scalable=no,Permitir a los usuarios acercar y alejar para ver los detalles de la fórmula;maximum-scale=5.0 Limitar el zoom máximo -->
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
<!-- v1.97: KaTeX CSS+JS En su lugar, cargue desde activos locales,Resuelva el problema de que CDN no esté disponible en China. Las fuentes recurren a las fuentes del sistema. -->
<link rel="stylesheet" href="vendor/katex.min.css">
<script defer src="vendor/katex.min.js"></script>
<style>
  html, body {
    margin: 0;
    padding: 8px 4px;
    background: transparent;
    color: $textColorHex;
    font-size: 16px;
    overflow-x: auto;
    overflow-y: hidden;
  }
  #formula {
    display: inline-block;
    width: 100%;
    text-align: center;
  }
  .katex-error {
    font-family: monospace;
    color: $errorColorHex;
    font-size: 13px;
    padding: 4px;
    background: rgba(255,255,255,0.05);
    border-radius: 4px;
    display: inline-block;
  }
</style>
</head>
<body>
<div id="formula"></div>
<script>
  window.onload = function() {
    try {
      katex.render("$escaped", document.getElementById("formula"), {
        displayMode: true,
        throwOnError: false,
        output: "htmlAndMathml"
      });
    } catch (e) {
      // L5 Reparación: Utilice contenido de texto en su lugar innerHTML,Evitar que e.message se analice en HTML y provoque inyección
      document.getElementById("formula").textContent = 'Error de fórmula: ' + e.message;
    }
  };
</script>
</body>
</html>
    """.trimIndent()
}
