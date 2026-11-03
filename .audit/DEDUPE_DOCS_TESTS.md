# Enlace 10: Documentación / Salud del código abierto / Cobertura de prueba — Línea base de deduplicación

## Solución confirmada/Conocido (no hay más informes)
- Los documentos de entrega se han sincronizado v2.5.8 (esta ronda 3a00d72); disciplina del documento (README No hay emoji, hecho versus código) está archivado.
- A cada uno de los cinco paquetes de idiomas distintos del inglés le faltan 101 traducciones (conocidas);7 El proceso de adición de idiomas ya está documentado.
- P1 #43"Las pruebas faltantes con el mismo nombre no son prueba de cobertura"(Se ha aclarado la metodología);#44 La política es la activación manual de CI remota.
- SECURITY.md / docs Las estrategias de seguimiento requieren decisiones sobre la propiedad del almacén (condiciones externas conocidas).
- Manual del usuario Capítulo 19, LÉAME bilingüe,release_body Se establecen normas bilingües.
- Lista de falsos positivos en informes de revisión antiguos (git Informe de revisión técnica de la historia_dimensión completa.md §7) ha sido comprobado——La lección interior (no lo creas con ingenuidad)"MuseSpinner código muerto"conclusión de clase) se debe seguir.

## La dirección de exploración clave de este enlace.
1. **Documentación y deriva del código v2.5.8**:README/README_EN/Manual de usuario/Función de software.md Exportar a A2A/Tasker/Plug-in Hook/Diagnóstico/Cobertura de herramientas de autoprueba;CONTRIBUTING.md Coherencia con el proceso de contribución real.
2. **Nuevas lagunas de funciones en el manual del usuario**:v2.5.0 Carpeta de la base de conocimientos, rediseño del campo de entrada,v2.5.8 protocolo externo——Si el documento de perspectiva del usuario se enseña bien.
3. **Las pruebas cubren lagunas estructurales**(no"Falta prueba con el mismo nombre", pero"Faltan pruebas de comportamiento clave"):
   - A2A handler Prueba del protocolo JSON-RPC (código de error/solicitud mal formada);
   - TaskerTriggerReceiver El límite de resolución de intención;
   - Matriz de excepciones del complemento Hook onPromptFinalize;
   - GenerationTrace/DiagnosticExporter Prueba de revisión de contenido (la afirmación no contiene texto corporal)/clave);
   - Busque cobertura de prueba de análisis de API para 13 proveedores.
4. Desorden del directorio raíz:compile*.log/baseline_compile.log ¿Debería ingresarse el archivo de registro en .gitignore (ha sido rastreado por git)?
5. NOTICE/LICENSE Declaración de integridad de terceros (v2.5.x Nuevas dependencias introducidas——Node tiempo de ejecución,A2A, SDK de API de búsqueda).
6. Sitio web oficial museai.ltd（`E:\1Project\Muse\museai.ltd`) Sincronización con v2.5.8: visualización del enlace Latest.json en la página de descarga para v2.5.6/2.5.8; Coherencia del documento con las funciones de la nueva versión.
