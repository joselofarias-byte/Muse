# Muse v2.5.2

Esta versión ha rehecho el efecto de vidrio líquido (v2.5.1 Este efecto está desactivado de forma predeterminada y el elemento de configuración está oculto). Si está interesado en la apariencia del vidrio, puede configurarlo en Configuración → Apariencia → El efecto de vidrio líquido se puede activar para experimentar el efecto; si no está encendido, no afectará en absoluto el uso diario.

## Rediseño de vidrio líquido

### Problemas con la edición anterior.

Sólo hice "coloración plana borrosa + translúcida", que es vidrio esmerilado; y el color es pesado (0.4+), cubriendo el propio desenfoque——El control deslizante de resistencia a la tracción apenas cambia de forma perceptible. La verdadera sensación del vidrio proviene de tres capas, dos de las cuales antes faltaban.

### Completado en esta versión.

**1. Capa de borde (lo que falta más importante)**

Se agregó una superposición de vidrio de tres elementos:

- Borde resaltado blanco superior de 1,5 dp —— vidrio"Espesor"fuente principal de
- Borde oscuro inferior 1dp —— sensación de volumen
- diagonalmente 35° escaneo de luz —— Simular el reflejo de la luz ambiental

**2. permeabilidad de la capa corporal**

La transparencia del sombreado se redujo significativamente para que el desenfoque fuera realmente visible y los cambios del control deslizante de intensidad fueran perceptibles.

**3. Estilo dual (realmente diferenciado visualmente)**

- **vaso de agua**: Desenfoque débil + resaltado intenso + sombreado fino (similar a la apariencia de iOS)
- **vidrio esmerilado**: Desenfoque fuerte + resaltado suave + sombreado grueso (similar a la apariencia nativa de Android)

### Capas de alcance (rendimiento crítico)

- **vidrio real**(Fondo borroso): Tecla de retorno de la barra superior / Tecla Más / Isla de entrada / Menú Más —— Pequeña cantidad, ubicación fija
- **vidrio falso**(Degradado + borde resaltado, sin desenfoque): burbuja de mensaje —— Gran número, desplazamiento, evite que docenas de capas borrosas en una pantalla arrastren hacia abajo el desplazamiento

### Otros

- Barra superior de chat desde"Toda la franja transparente horizontal."Cambiar a**tres islas independientes**(Atrás izquierda / Título central / Más derecha)
- Más menús (tres puntos en la esquina superior derecha) también aplican textura de vidrio

## Información de construcción

- Versión:2.5.2 (252)
- Versión de la base de datos:109(Igual que 2.5.0/2.5.1, sobrescribe directamente la instalación)
- Distribución:arm64-v8a / armeabi-v7a / universal

---
# Muse v2.5.2 (English)

This release redesigns the liquid glass effect (which has been off by default with its setting hidden since v2.5.1). If you're curious about the look, enable it under Settings → Appearance → Liquid glass effect; leaving it off changes nothing in daily use.

## Liquid glass redesign

### What was wrong before

It only did "blur + flat translucent tint" — that is frosted glass, not liquid glass. The tint was also too heavy (0.4+), covering the blur itself, so dragging the intensity slider barely changed anything. Real glass comes from three layers; two were missing.

### What's added

**1. Edge layer (the key missing piece)**

A new three-element glass overlay:

- A 1.5dp top white highlight edge — the main source of glass "thickness"
- A 1dp bottom shadow edge — volume
- A diagonal 35° light sweep — simulated ambient reflection

**2. Body transparency**

Tint alpha is lowered substantially so the blur is genuinely visible and the intensity slider has a perceptible effect.

**3. Two styles (visually distinct)**

- **Water glass**: light blur + strong highlight + thin tint (close to the iOS look)
- **Frosted glass**: heavy blur + soft highlight + thick tint (close to the Android native look)

### Layered scope (performance-critical)

- **Real glass** (backdrop blur): top-bar back/more buttons, input bar, more menu — few and fixed
- **Fake glass** (gradient + highlight edge, no blur): message bubbles — many and scrolling, avoiding dozens of blur layers per screen

### Other

- The chat top bar changes from a full-width translucent strip to **three separate islands** (back / title / more)
- The top-right "more" menu also gets the glass treatment

## Build

- Version: 2.5.2 (252)
- Database version: 109 (same as 2.5.0/2.5.1; install over the existing app)
- Distributions: arm64-v8a / armeabi-v7a / universal
