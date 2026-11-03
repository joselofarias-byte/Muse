# Muse Herramienta de firma de mercado complementaria

Convierta el código fuente del complemento en un paquete firmado que la aplicación pueda instalar y genere un directorio en línea que la aplicación pueda verificar.
Todos los productos son aceptados por el propio validador de la aplicación (ver `app/src/test/java/io/zer0/muse/data/plugin/market/MarketSigningFixtureTest.kt`),
Por lo tanto, el algoritmo de esta herramienta y aplicación no variará.

## depender de

- Python 3.8+(solo usando la biblioteca estándar)
- OpenSSL archivo ejecutable (`openssl version` Mientras puedas correr)

## Estructura del directorio

```
market-signer/
├── sign.py                 # Cuerpo de herramienta
└── plugins/                # Código fuente del complemento (un complemento para cada subdirectorio)
    ├── text-toolkit/       # manifest.json + main.js
    └── time-toolkit/
```

## Tres cosas relacionadas con las claves privadas (debe leerse primero)

1. La clave privada sólo existe en esta máquina.**Nunca almacenado, nunca enviado**. Por defecto colocado en `C:\Users\21192\.muse-market\keys\`.
2. **clave privada raíz del directorio**Determina si la aplicación acepta todo el directorio;**Clave privada del emisor**Determina la identidad del editor que los usuarios ven en su dispositivo.
   Se recomienda mantener las dos manijas separadas durante mucho tiempo (actualmente configuradas de esta manera).
3. Clave privada perdida = El directorio publicado ya no se puede actualizar (la clave pública vinculada a la raíz de confianza del cliente no cambiará). Realice una copia de seguridad en un medio sin conexión de inmediato.
   fuga = Se debe invalidar y volver a firmar, y la nueva clave pública se debe enviar a todos los clientes.

## Comandos comunes

```bash
# 1) Primera vez: genere dos claves privadas P-256 (solo una vez)
python sign.py keygen --key "C:/Users/21192/.muse-market/keys/catalog-key.pem"
python sign.py keygen --key "C:/Users/21192/.muse-market/keys/publisher-museai.pem"

# 2) Firme el paquete de complementos (una vez por complemento)
python sign.py pack \
  --src plugins/text-toolkit \
  --key "C:/Users/21192/.muse-market/keys/publisher-museai.pem" \
  --publisher-id museai \
  --out-dir app/build/market-signing/out/packages

# 3) Generar directorio de firmas (escanear todos los paquetes producidos en el paso anterior)
python sign.py catalog \
  --packages-dir app/build/market-signing/out/packages \
  --key "C:/Users/21192/.muse-market/keys/catalog-key.pem" \
  --key-id museai-catalog-1 \
  --base-url "https://museai.ltd/muse-market/packages" \
  --catalog-id museai-official \
  --out app/build/market-signing/out/signed-catalog.json
```

Productos:

- `app/build/market-signing/out/signed-catalog.json` —— archivo de catálogo
- `app/build/market-signing/out/packages/*.muse-plugin` —— Paquete de complemento
- `app/build/market-signing/out/public-keys.json` —— Clave pública y huella digital (no confidencial, para configuración y verificación)

## Publicar en el servidor

Empaquetar y firmar todos los complementos (en `1muse/` ejecución a continuación):

```bash
KEYS="C:/Users/21192/.muse-market/keys"
OUT="app/build/market-signing/out"

for plugin in tools/market-signer/plugins/*/; do
  python tools/market-signer/sign.py pack \
    --src "$plugin" \
    --key "$KEYS/publisher-museai.pem" \
    --publisher-id museai \
    --out-dir "$OUT/packages"
done

python tools/market-signer/sign.py catalog \
  --packages-dir "$OUT/packages" \
  --key "$KEYS/catalog-key.pem" \
  --key-id museai-catalog-1 \
  --base-url "https://museai.ltd/muse-market/packages" \
  --catalog-id museai-official \
  --out "$OUT/signed-catalog.json"
```

Cargue y verifique la coherencia de la red pública:

```bash
ssh -i <tuyoSSHclave privada> root@117.72.212.102 'mkdir -p /var/www/muse-market/packages'
scp -i <tuyoSSHclave privada> "$OUT/signed-catalog.json" root@117.72.212.102:/var/www/muse-market/
scp -i <tuyoSSHclave privada> "$OUT/packages"/*.muse-plugin root@117.72.212.102:/var/www/muse-market/packages/

curl -sS --compressed https://museai.ltd/muse-market/signed-catalog.json | sha256sum
sha256sum "$OUT/signed-catalog.json"
```

El servidor nginx ha sido `/muse-market/` Alias ​​de `/var/www/muse-market/`(Ver `museai.ltd` configuración del sitio).
App Lado integrado con la misma dirección y raíz de confianza (`PluginMarketDefaults`), los usuarios pueden usarlo de inmediato y no se requiere configuración.

## Aceptación antes de cada lanzamiento (debe ejecutarse primero)

```bash
# 1) Comportamiento del complemento: use el mismo contrato de llamada del host para ejecutar todas las herramientas
node tools/market-signer/verify-plugins.js

# 2) App Aceptación lateral: utilice el verificador propio de la aplicación para comprobar el producto y confirmar que la clave utilizada para la publicación sea coherente con la raíz de confianza integrada.
./gradlew :app:testDebugUnitTest --tests "io.zer0.muse.data.plugin.market.*"
```

en el artículo 2 `MarketSigningFixtureTest` Afirmará que: la aplicación puede aceptar directorios y paquetes de complementos, y los paquetes manipulados serán rechazados.
**La clave pública en el lado de publicación es igual a la raíz de confianza oficial integrada en el paquete de instalación.**. La emisión con la clave privada incorrecta fallará aquí en lugar de esperar a que el usuario la vea en el dispositivo.
"Clave de firma de directorio desconocida".

## Usar en la aplicación

Mercado de complementos listo para usar: configuración → Gestión de complementos (Plugins)→ Al ingresar al mercado de complementos, el catálogo oficial se cargará automáticamente.
Los elementos de configuración sólo se utilizan para**Cobertura personalizada**(Directorio de prueba o interruptor de emergencia): déjelo en blanco para usar el directorio oficial integrado en el paquete de instalación.
Y la raíz de confianza incorporada no se puede sobrescribir ni eliminar.

## Reglas de funcionamiento (App Se aplicarán restricciones estrictas)

- `sequence` El valor predeterminado es el segundo Unix actual,**debe ser monótono**;La aplicación rechazará permanentemente la reversión (permanecerá en el directorio privado del dispositivo).
- `expiresAtEpochMs` Después de la expiración, todo el directorio dejará de ser válido (incluido el caché local) y deberá volver a emitirse antes de la expiración; el período de validez predeterminado es de 30 días.
- Tanto la URL del directorio como la dirección de descarga del complemento deben ser `https://`y resuelve en la dirección de red pública (App La salida tiene fijación DNS y verificación SSRF,
  Intranet/Las direcciones de loopback siempre se rechazan).
- Tamaño del directorio ≤ 512KB, un único paquete de complemento ≤ 20MB.
- Los directorios sólo pueden ser creados por**clave privada raíz del directorio**Firmado; Los paquetes de complementos solo pueden ser firmados por**Clave privada del emisor**Firmado y una vez que el usuario confía en el ID del editor de cada paquete,
  Ya no puede cambiar la clave pública (la raíz de confianza del dispositivo rechazará el cambio de clave).
- Actualmente, un ID de complemento solo contiene una entrada (aún no se han implementado modelos de coexistencia y reversión de múltiples versiones).

## Agregar un nuevo complemento

1. Copiar `plugins/time-toolkit/` Para un nuevo directorio, cambie `manifest.json`(id/name/tools/entry) y `main.js`.
2. JS Contrato: una función por herramienta, que recibe objetos de parámetros y devuelve objetos ordinarios; regresando por error `{ error: "Razón" }`.
   El anfitrión hará algo con el valor de retorno. `JSON.stringify`, así que no devuelva usted mismo el resultado en cadena.
3. La lista blanca de capacidades solo tiene `resource.read` / `ui` / `ui.mood` / `ui.skin`;El complemento de cálculo puro se puede dejar en blanco.
4. correr `pack`(Hará autoverificación de estructura y firma)→ correr `catalog` → subir → App Haga clic en actualizar.

## Se agregó un nuevo paquete de apariencia de burbujas (kind: ui-skin)

Los paquetes de skins solo distribuyen JSON declarativo,**no contiene ninguna JS**, el anfitrión es responsable de renderizar:

```
plugins/skin-xxx/
├── manifest.json   Requerido:kind="ui-skin",capabilities=["ui.skin"],tools=[]
└── skin.json       Requerido:BubbleSkin JSON(schemaVersion / id / name / light / dark)
```

- `manifest.entry` mantener predeterminado `main.js` y**En realidad, no coloques el archivo main.js.**: El resumen de contenido del anfitrión
  Se tiene en cuenta "Nombre de la entrada + contenido vacío" y la herramienta de firma lo procesa de acuerdo con las mismas reglas.
- `skin.json` de `light` / `dark` Escriba una combinación de colores para cada personaje y el nombre del personaje se fija como
  `USER` / `ASSISTANT` / `GROUP_ASSISTANT` / `SYSTEM` / `TOOL`;El rol predeterminado volverá a ser `ASSISTANT`.
- El color tiene una longitud ARGB (p. ej. `0xFFDCE9FF` escrito en decimales `4292702207`); para conocer los significados de los campos, consulte
  `ui/theme/BubbleSkin.kt` de `BubbleRoleStyle`.
- **El contraste de las WCAG se verificará antes de publicar**: El contraste entre el color del texto y el color de fondo debe ser ≥ 4.5, de lo contrario, el host recurrirá silenciosamente al sistema integrado
  Burbujas (los usuarios pensarán "Es inútil si se instala"), por lo que `pack` Rechace directamente incluir tales máscaras en la lista.
- Implementación de referencia:`plugins/skin-soft-bubble/` con `plugins/skin-contrast-bubble/`.

## Verificación automatizada

```bash
# App Aceptación lateral: utilice el verificador propio de la aplicación para verificar el producto (incluidos los casos de uso negativos de manipulación)
./gradlew :app:testDebugUnitTest --tests "io.zer0.muse.data.plugin.market.MarketSigningFixtureTest"
```

Esta prueba se omite automáticamente si el directorio del producto no existe, por lo que CI no se ve afectado.
