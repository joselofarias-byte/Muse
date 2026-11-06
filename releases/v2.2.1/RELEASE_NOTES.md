# Muse 2.2.1 Instrucciones de archivo (compilación local · (Incluida la reparación del sistema de memoria)

- Tiempo de archivo:2026-09-27 Tarde en la noche (GMT+8); parche reaplicado a 22:57
- Fuente de compilación:`E:\1Project\Muse\1muse` @ commit `a944214c`(Local lidera remotamente 48 envíos en total)
- Comando de compilación:`.\gradlew :app:assembleRelease "-PversionName=2.2.1" "-PversionCode=221"`
- Estado:**Archivo local, no enviado/no etiquetado/no publicado**(Esperando pruebas de usuarios reales)

## Correcciones clave incluidas en esta reimpresión (en comparación con la primera versión 2.2.1)

- **Defecto de archivado del sistema de memoria (solución de causa raíz)**: Guardián de la versión escrito hasta la muerte. `<= 13` Y la biblioteca es v14 → Cada vez que se inicia, la versión actual de la biblioteca real se archivará y se reconstruirá en una biblioteca vacía por error (la memoria real se borrará cada vez que se inicie). Ahora la versión guard y la base de datos tienen el mismo origen; comprobar la fidelidad antes de archivar; nueva recuperación de archivo por error único; archivo de cableado de página de memoria/Consejos de recuperación.
- Memoria larga, haga clic para expandir (si supera las 80 palabras o contiene saltos de línea, se puede expandir el texto completo).

## producto con suma de comprobación (SHA-256)

| Archivo | tamaño | SHA-256 |
|---|---|---|
| Muse_v2.2.1_arm64-v8a.apk | 66MB | `c54043ae8a139fb294e258f063fd64855aacf0e6e37646a47bcfb1d782f6a286` |
| Muse_v2.2.1_armeabi-v7a.apk | 55MB | `d15b8fcebcf56c4066edff2988265a6c5d29f2bba3584e02bdce68b1702f23e3` |
| Muse_v2.2.1_universal.apk | 167MB | `2e1cad0012319fa6a66038198e3f1749bde6c6119dfbab6be89600009626e29b` |
| Muse_v2.2.1_accessibility-provider.apk | 1.9MB | `3a61caa038be5ef10d105000da764c18afc98b1796cd31107a6bb0497a13dde8` |

> La aplicación principal y el proveedor utilizan el mismo almacén de claves de versión (certificado SHA-256 `e82f4ecde8304b7d78b530336a48e41a42b80c0ebac8af2d2c10448277644ebf`).

## Registro de verificación de fábrica

- aapt2:`versionCode=221 / versionName=2.2.1` ✓
- Validador oficial `ci/script/validate_release_apks.py`:Tres firmas de garantía PASS ✓
- Prueba única completa (app+memory, incluido el nuevo caso de uso de regresión de memoria 7) Verde ✓
- **Ejercicios prácticos de recuperación de la memoria (MuMu)**: Fabricación"Copia de seguridad mal archivada con 1 memoria + marca de archivo no consumido" → El primer registro de lanzamiento de la versión reparada.
  `Memoria de hechos recuperada del archivo: 1 Artículo` → 1 elemento en la biblioteca es completamente visible → Dos reinicios consecutivos con hechos persistidos y sin nuevos archivos ✓

## Instrucciones del lado del usuario (importante)

- Cuando abra la versión reparada por primera vez después de instalarla, se iniciará automáticamente desde"Una copia de seguridad mal archivada que contiene datos"Recupere los recuerdos que se pueden recuperar y solicite la cantidad de elementos que se restaurarán en la página de memoria; la copia de seguridad vacía (el WAL histórico ha sido eliminado por la versión anterior) no se puede restaurar——Esto es cierto, pero**Nunca lo pierdas de nuevo**.
- Si el paquete de depuración está instalado en el teléfono, la sobrescritura directa provocará conflictos de firma (primero debe desinstalarse); si está instalada la versión oficial 2.2.0, se puede sobrescribir y actualizar directamente.

## Descripción

- El directorio de compilación será sobrescrito por la siguiente compilación; este paquete es **2.2.1 Versión reparada**(No actualizado, ha sido reemplazado por v2.3.0, solo para registros históricos).
- ubicación del archivo (2026-09-28 Actualización): En el almacén `E:\1Project\Muse\1muse\releases\v2.2.1\`;APK por `.gitignore` Excluido, sólo para almacenamiento temporal de productos locales.
- 2.2.0 y las versiones anteriores no se archivan localmente; Los paquetes históricos están sujetos a los activos de la versión de GitHub.
