# Generación de manifiesto modelo

Muse El mensaje "Qué admite este modelo, cuánto dura el contexto y cuánto se puede generar al máximo" que se muestra está controlado por un JSON.
El tipo de tiempo de ejecución es `io.zer0.ai.core.ModelCatalog`:

```json
{ "schemaVersion": 1, "publishedAt": "...",
  "providers": { "providerId": { "modelId": {
      "name": "...", "context": 1000000, "maxOutput": 128000,
      "image": true, "reasoning": true,
      "toolUse": { "supportsTools": true, "dialect": "openai", "toolResultFormat": "message" },
      "thinkingLevels": ["low","medium","high"]
  } } } }
```

## ¿Por qué deberías guardarlo tú mismo?

Anteriormente, esta lista dependía completamente de fuentes externas: copiar el conjunto completo de una fuente ascendente o esperar a que otros lo actualizaran.
El problema es que los datos de ambos lados sólo cubren la mitad de los hechos:

- **Tabla de capacidad ascendente**¿Sabes "qué dialecto se utiliza para llamar a la herramienta de este canal y cuál es el resultado?"——solo trae `toolUse.dialect`;
- **models.dev** Sepa "cuán largo es el contexto, cuál es el resultado máximo, si se debe comer la imagen y si el soporte respalda el razonamiento".

Hay agujeros si los miras por separado. Está completo una vez montado y puede regenerarse en cualquier momento a su propio ritmo.

## fuente de datos

| papel | Fuente | proporcionar |
|---|---|---|
| esqueleto | aguas arriba `model-catalog.v2.json` | Colección de canales, colección de modelos,`toolUse`,`thinkingLevels`,`compat` |
| suplemento | `https://models.dev/api.json` | `limit.context`,`limit.output`,`modalities`,`tool_call`,`reasoning` |

Prioridad de campo:**entrada aguas arriba > aguas arriba fallbacks > models.dev este canal > models.dev Canal cruzado**.
Cuanto más cerca esté del hecho de que ha sido "verificado en nuestra ruta de llamada".

Es necesario comprobar el resultado final en todos los canales:models.dev La entrada openrouter generalmente no toma `limit.output`,
Pero el mismo modelo está disponible en la entrada del canal oficial. La salida máxima es un atributo del modelo, no un atributo del canal.

## Uso

```bash
python tools/model-catalog/build_catalog.py \
    --hana      path/to/model-catalog.v2.json \
    --modelsdev path/to/models_dev_api.json \
    --out       app/src/main/assets/model-catalog.json
```

`models_dev_api.json` directamente desde `https://models.dev/api.json` Descargar (aprox. 5,6 MB).
No utilice instantáneas simplificadas empaquetadas por otros——Un enfoque común es dejar sólo `limit.context`, poner `limit.output` cortado,
De esta manera nunca se llenará la potencia máxima.

## publicar

La lista sirve a dos lugares al mismo tiempo:

1. **Incorporado**:`app/src/main/assets/model-catalog.json`, lanzado con APK, disponible sin conexión.
2. **extremo lejano**:`https://museai.ltd/api/model-catalog.json`, correspondiente a
   `ModelCatalogRepository.REMOTE_URL`. No es necesario emitir una versión al cambiar de modelo.

Cargar (primero haga una copia de seguridad del anterior y luego sobrescríbalo):

```bash
ssh root@<host> "cp /var/www/museai.ltd-api/model-catalog.json \
    /var/www/museai.ltd-api/model-catalog.json.bak-$(date +%Y%m%d-%H%M)"
scp app/src/main/assets/model-catalog.json \
    root@<host>:/var/www/museai.ltd-api/model-catalog.json
```

Nota `/var/www/museai.ltd-api/` con `/var/www/museai.ltd/assets/` son dos cosas diferentes,
Este último es el directorio de recursos estáticos del sitio web oficial, no la entrada a la lista de modelos.

## verificar

El script de compilación imprime la cobertura (número de canales, número de modelos, con contexto, con rendimiento máximo, con especificaciones de herramienta).
Línea de base actual:35 Inicio / Modelos 1032 / Contexto 98% / Salida máxima 96%.

Las lagunas restantes se concentran juntas,openai,volcengine Hay algunos modelos de borde sin datos de ambas fuentes,
sin inferencia——Los números adivinados son más peligrosos que los valores nulos.