package io.zer0.muse.ui.markdown

/**
 * v2.6.6: Tokens de tema y estilos de fragmentos de componentes para tarjetas generadas (inyectados en todas las tarjetas WebView).
 *
 * Objetivo: hacer que las tarjetas HTML escritas a mano del modelo "parezcan como si las hubiéramos dibujado de forma nativa"——color unificado/esquinas redondeadas/espaciado/fuente,
 * Y se adapta automáticamente a colores claros y oscuros. El modelo solo necesita usar el siguiente conjunto de variables y clases CSS, y no es necesario que cree estilos usted mismo.
 *
 * Uso (lado del modelo): usado directamente en la tarjeta HTML `var(--muse-fg)` variables o conjuntos iguales `.muse-card`
 * `.muse-metric` `.muse-btn` Espera clase.
 *
 * Lugar de inyección:RichContentCard La plantilla contenedora html/svg dentro del encabezado ([styleBlock]).
 */
internal object MuseCardTheme {

    /**
     * inyectado en cada tarjeta <style>.
     *
     * - La variable sigue el esquema de color preferido para cambiar entre colores oscuros y claros;
     * - Elementos básicos (body/mesa/botón/Entrada) se ha diseñado y el modelo es decente sin escribir CSS;
     * - Clase de componente para la selección del modelo (metric tarjeta indicadora,steps barra de paso,progress bucle de progreso,row Aceptar, etc.).
     */
    val styleBlock: String = """
        :root {
            --muse-bg: transparent;
            --muse-surface: #ffffff;
            --muse-surface-2: #f2f2f4;
            --muse-fg: #1a1a1c;
            --muse-muted: #6b6b70;
            --muse-border: rgba(0,0,0,0.10);
            --muse-accent: #2f7cf6;
            --muse-accent-fg: #ffffff;
            --muse-radius: 14px;
            --muse-radius-sm: 10px;
            --muse-gap: 12px;
        }
        @media (prefers-color-scheme: dark) {
            :root {
                --muse-surface: #1e1e22;
                --muse-surface-2: #2a2a30;
                --muse-fg: #ececef;
                --muse-muted: #9a9aa2;
                --muse-border: rgba(255,255,255,0.14);
                --muse-accent: #5a9bff;
                --muse-accent-fg: #06121f;
            }
        }
        * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        html, body { margin: 0; padding: 0; background: var(--muse-bg); }
        body {
            color: var(--muse-fg);
            font-family: -apple-system, "Segoe UI", Roboto, "Noto Sans SC", sans-serif;
            font-size: 14px; line-height: 1.5;
            padding: 8px;
            word-break: break-word; overflow-wrap: anywhere;
        }
        h1,h2,h3,h4 { margin: 0 0 8px; font-weight: 600; line-height: 1.3; }
        h1 { font-size: 18px; } h2 { font-size: 16px; } h3,h4 { font-size: 14px; }
        p { margin: 0 0 8px; }
        small, .muse-muted { color: var(--muse-muted); font-size: 12px; }
        a { color: var(--muse-accent); text-decoration: none; }
        /* Estuche para tarjetas */
        .muse-card {
            background: var(--muse-surface);
            border: 1px solid var(--muse-border);
            border-radius: var(--muse-radius);
            padding: var(--muse-gap);
            margin: 0 0 var(--muse-gap);
        }
        .muse-card:last-child { margin-bottom: 0; }
        /* tarjeta indicadora */
        .muse-metric { display: inline-flex; flex-direction: column; gap: 2px; padding: 10px 14px;
            background: var(--muse-surface-2); border-radius: var(--muse-radius-sm); min-width: 84px; }
        .muse-metric .v { font-size: 20px; font-weight: 700; }
        .muse-metric .k { color: var(--muse-muted); font-size: 12px; }
        /* fila/lista */
        .muse-row { display: flex; align-items: center; gap: 10px; padding: 8px 0;
            border-bottom: 1px solid var(--muse-border); }
        .muse-row:last-child { border-bottom: none; }
        /* botón */
        .muse-btn {
            display: inline-flex; align-items: center; justify-content: center; gap: 6px;
            min-height: 44px; padding: 0 16px; border: none; cursor: pointer;
            background: var(--muse-accent); color: var(--muse-accent-fg);
            border-radius: var(--muse-radius-sm); font-size: 14px; font-weight: 600;
        }
        .muse-btn.ghost { background: var(--muse-surface-2); color: var(--muse-fg); }
        .muse-btn:active { filter: brightness(0.94); }
        /* Entrada/control deslizante */
        input, select, textarea {
            min-height: 44px; padding: 8px 10px; color: var(--muse-fg);
            background: var(--muse-surface-2); border: 1px solid var(--muse-border);
            border-radius: var(--muse-radius-sm); font-size: 14px; font-family: inherit;
        }
        input[type="range"] { min-height: 0; padding: 0; width: 100%; accent-color: var(--muse-accent); }
        /* mesa */
        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        th, td { padding: 8px 10px; text-align: left; border-bottom: 1px solid var(--muse-border); }
        th { color: var(--muse-muted); font-weight: 600; }
        /* barra de progreso */
        .muse-progress { height: 8px; background: var(--muse-surface-2); border-radius: 999px; overflow: hidden; }
        .muse-progress > i { display: block; height: 100%; background: var(--muse-accent); border-radius: 999px; }
    """.trimIndent()

    /** completo <style> Etiqueta (con la etiqueta, es fácil deletrearla directamente en la cabeza). */
    val styleTag: String = "<style>$styleBlock</style>"

    /** Meta ventana gráfica unificada (requerida para adaptación de pantalla estrecha). */
    const val VIEWPORT_META: String = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"

    /**
     * v2.6.6: Política de seguridad de contenido (CSP)meta.
     *
     * - `default-src 'none'` Bloquear todos los recursos no declarados explícitamente;
     * - estilo/Los scripts sólo permiten inline(Se requiere tarjeta)con data:;
     * - Las imágenes sólo permiten datos: con blob:(Deshabilitar solicitudes de imágenes externas);
     * - prohibido frame/object/media/connect(No permitir ninguna red).
     *
     * Forma una doble capa de protección con el bloqueo shouldInterceptRequest de la capa WebView.
     */
    const val CSP_META: String =
        "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
            "default-src 'none'; " +
            "style-src 'unsafe-inline' file: data:; " +
            "script-src 'unsafe-inline' 'unsafe-eval' file: data:; " +
            "img-src data: blob: file:; " +
            "font-src data: file:; " +
            "connect-src 'none'; " +
            "frame-src 'none'; " +
            "object-src 'none'; " +
            "media-src data: blob: file:; " +
            "base-uri 'none'; " +
            "form-action 'none'" +
            "\">"
}
