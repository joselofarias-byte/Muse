package io.zer0.muse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginManifest
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.statusColors
import io.zer0.muse.ui.theme.tiny
import org.koin.compose.koinInject

/**
 * v1.0.4 P2-3:AI Página de gestión de herramientas — Exponer la lista de herramientas registradas en ToolRegistry pero no visibles para el usuario.
 *
 * Capacidades de backend(se han realizado):
 *  - [ToolRegistry.listTools] enumerar todo ToolDef(name/description/parameters/riskLevel/category)
 *  - [ToolRegistry.BUILT_IN_TOOL_IDS] Lista estática de identificadores de herramientas integradas
 *  - [ToolRiskLevel] Nivel de riesgo nivel 3(SAFE / NORMAL / HIGH),Utilizado para la aprobación de permisos
 *
 * Responsabilidades de esta página:
 *  1. por categoría(built-in / local / mcp)Listar todas las herramientas en grupos
 *  2. mostrar por fila:name + description + Insignia de color de nivel de riesgo
 *  3. Haga clic para ver detalles:parámetros Schema + required + category + Instrucciones de seguridad
 *  4. guiar a los usuarios:"A través de la página de detalles del asistente → Expandir → Herramientas,Controla qué herramientas utiliza cada asistente"
 *
 * Nota:Habilitación global de herramientas./Deshabilitado controlado por AssistantEntity.toolIdsJson(per-Assistant Granularidad),
 *     No hay ningún cambio global en esta página.(Evite conflictos con el mecanismo por Asistente),Mostrar y explicar únicamente.
 */
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val toolRegistry: ToolRegistry = koinInject()
    // ToolRegistry Admite registro dinámico de complementos/MCP; suscríbase a la revisión para evitar que la página nunca se actualice después de estar vacía por primera vez.
    val registryRevision by toolRegistry.revision.collectAsStateWithLifecycle()
    // listTools() es una función sincrónica(leer ConcurrentHashMap.values),No es necesario IO
    val tools = remember(registryRevision) { toolRegistry.listTools().sortedBy { it.name } }
    // Agrupar por categoría
    val grouped = remember(tools) {
        tools.groupBy { it.category }.toSortedMap()
    }
    var detailTarget by remember { mutableStateOf<ToolRegistry.ToolDef?>(null) }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.tools_screen_title),
                onBack = onBack,
                largeTitle = true,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(vertical = MusePaddings.sectionGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            // ── tarjeta de instrucciones ────────────────────────────────────────────────────
            // v2.x: Finalización del efecto de movimiento — Entrada de elemento de lista(ficha animateItem)
            item(key = "intro") {
                Box(museAnimateItem()) {
                    CardGroup(modifier = Modifier.padding(horizontal = MusePaddings.screen)) {
                        item(
                            leadingContent = {
                                Icon(
                                    imageVector = MuseIcons.info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.tools_intro_what),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = stringResource(R.string.tools_intro_manage),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    }
                }
            }

            // ── Número total de herramientas + número de riesgos altos ────────────────────────────────────────
            // v2.x: Finalización del efecto de movimiento — Entrada de elemento de lista(ficha animateItem)
            item(key = "stats") {
                val highRiskCount = tools.count { it.riskLevel == ToolRiskLevel.HIGH }
                Box(museAnimateItem()) {
                    Text(
                        text = stringResource(R.string.tools_stats_count, tools.size, highRiskCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }
            }

            // ── Mostrar en grupos por categoría ─────────────────────────────────────────────
            if (tools.isEmpty()) {
                // v2.x: Finalización del efecto de movimiento — entrada vacía(ficha animateItem)
                item(key = "tools_empty") {
                    Box(museAnimateItem()) {
                        MuseEmptyState(
                            title = stringResource(R.string.tools_empty_title),
                            subtitle = stringResource(R.string.tools_empty_subtitle),
                            modifier = Modifier.padding(horizontal = MusePaddings.screen),
                        )
                    }
                }
            }
            grouped.forEach { (category, toolsInCategory) ->
                // v2.x: Finalización del efecto de movimiento — Admisión por categorías(ficha animateItem)
                item(key = "group_$category") {
                    Box(museAnimateItem()) {
                        CardGroup(
                            modifier = Modifier.padding(horizontal = MusePaddings.screen),
                            title = {
                                Text(text = categoryLabel(category))
                            },
                        ) {
                            toolsInCategory.forEach { tool ->
                                ToolRow(
                                    tool = tool,
                                    onClick = { detailTarget = tool },
                                )
                            }
                        }
                    }
                }
            }

            // ── v1.0.4 (P3-6): Lista de complementos integrados ──────────────────────────────
            // El backend PluginManifest.BUILT_IN declara 5 complementos integrados(image-gen / beautify /
            // media / mcp-bridge / office),Contiene eventos de confianza/capacidades/activación.
            // Revelado aquí a los usuarios.,Mejorar la transparencia de los límites de capacidad(pantalla de solo lectura,habilitar/La desactivación todavía está controlada por código).
            // v2.x: Finalización del efecto de movimiento — Admisión grupal enchufable(ficha animateItem)
            item(key = "plugins_group") {
                Box(museAnimateItem()) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                        title = {
                            Column {
                                Text(text = stringResource(R.string.tools_plugins_section))
                                Text(
                                    text = stringResource(R.string.tools_plugins_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        },
                    ) {
                        PluginManifest.BUILT_IN.forEach { plugin ->
                            PluginRow(plugin = plugin)
                        }
                    }
                }
            }
        }
    }

    // ── Ventana emergente de detalles ─────────────────────────────────────────────────────
    detailTarget?.let { tool ->
        ToolDetailDialog(
            tool = tool,
            onDismiss = { detailTarget = null },
        )
    }
}

/**
 * línea de herramienta única — icono + name + description + Insignia de nivel de riesgo.
 */
private fun CardGroupScope.ToolRow(tool: ToolRegistry.ToolDef, onClick: () -> Unit) {
    item(
        key = tool.name,
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = MuseIcons.wrench,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        },
        headlineContent = {
            Text(
                text = tool.name,
                fontFamily = MuseMonoFontFamily,
                maxLines = 1,
            )
        },
        supportingContent = {
            Text(
                text = tool.description,
                maxLines = 2,
            )
        },
        trailingContent = {
            RiskBadge(level = tool.riskLevel)
        },
    )
}

/**
 * Insignia de nivel de riesgo — color:SAFE VERDE / NARANJA NORMAL / ROJO ALTO,Formato cápsula blanda.
 */
@Composable
private fun RiskBadge(level: ToolRiskLevel) {
    // v1.0.52: El color de riesgo accede al color de estado semántico,El modo oscuro cambia automáticamente al modo brillante para garantizar el contraste
    val statusColors = MaterialTheme.statusColors
    val (label, color) = when (level) {
        ToolRiskLevel.SAFE -> stringResource(R.string.tools_risk_safe) to statusColors.success
        ToolRiskLevel.NORMAL -> stringResource(R.string.tools_risk_normal) to statusColors.warning
        ToolRiskLevel.HIGH -> stringResource(R.string.tools_risk_high) to statusColors.error
    }
    Surface(
        shape = MuseShapes.pill,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Ventana emergente de detalles de la herramienta — Parámetro de visualización Esquema + nivel de riesgo + instrucciones de seguridad.
 */
@Composable
private fun ToolDetailDialog(tool: ToolRegistry.ToolDef, onDismiss: () -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = tool.name,
        // v1.0.86: solo quédate"cerrar"un botón — Los detalles de la herramienta son de solo lectura.,
        // "Cancelar"con"cerrar"repetición semántica(Quítate los pantalones y tírate un pedo),Elimine el botón de cancelación predeterminado.
        dismissText = null,
        content = {
            // v1.0.7 arreglar falla:MuseDialog El área de contenido viene con verticalScroll(heightIn(max=420dp)),
            // Anidar verticalScroll aquí dará como resultado"Vertically scrollable component was measured
            // with an infinity maximum height constraints"colapsar. Retire el desplazamiento vertical interior.
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Descripción
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))

                // línea de metainformación:Clasificación + Nivel de Riesgo
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.tools_detail_category_label, categoryLabel(tool.category)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: Row Ancho del texto interno + límite de línea, evita el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RiskBadge(level = tool.riskLevel)
                }
                Spacer(Modifier.height(16.dp))

                // parámetros Schema
                if (tool.parameters.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.tools_detail_params),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    tool.parameters.forEach { (paramName, paramDesc) ->
                        val required = paramName in tool.required
                        val type = tool.parameterTypes[paramName] ?: "string"
                        ParamRow(
                            name = paramName,
                            type = type,
                            required = required,
                            description = paramDesc,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // execute_javascript Instrucciones de seguridad específicas
                if (tool.name == "execute_javascript") {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = MuseIcons.code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = stringResource(R.string.tools_detail_sandbox_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.tools_detail_sandbox_content),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
    )
}

/**
 * Línea de parámetros — Nombre(Ancho igual)+ Tipo + etiquetas requeridas + descripción.
 */
@Composable
private fun ParamRow(name: String, type: String, required: Boolean, description: String) {
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = MuseShapes.tiny,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (required) {
                    Spacer(Modifier.size(6.dp))
                    Surface(
                        shape = MuseShapes.tiny,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = stringResource(R.string.tools_param_required),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Asigne claves de clasificación a etiquetas localizadas. */
@Composable
private fun categoryLabel(category: String): String = when (category) {
    "built-in" -> stringResource(R.string.tools_category_built_in)
    "local" -> stringResource(R.string.tools_category_local)
    "mcp" -> stringResource(R.string.tools_category_mcp)
    else -> category
}

/**
 * v1.0.4 (P3-6): Línea de complementos incorporada — Nombre para mostrar/descripción/confianza/capacidades de PluginManifest.
 *
 * pantalla de solo lectura(sin interruptor):La activación real del complemento está determinada internamente por el código.,Aquí sólo se revelan al usuario los límites de las capacidades.
 * trust=full-access Resaltar con insignia roja,sandboxed Usa colores neutros,Permita que los usuarios identifiquen complementos de alto riesgo de un vistazo.
 */
private fun CardGroupScope.PluginRow(plugin: PluginManifest) {
    item(key = plugin.id) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.puzzle,
                contentDescription = null,
                tint = if (plugin.trust == "full-access") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (plugin.description.isNotEmpty()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                // Habilidad + letra pequeña del evento de activación
                val permissionsLabel = stringResource(R.string.tools_permissions_label)
                val activationLabel = stringResource(R.string.tools_activation_label)
                val capsText = buildString {
                    if (plugin.capabilities.isNotEmpty()) {
                        append(permissionsLabel)
                        append(plugin.capabilities.joinToString(", "))
                    }
                    if (plugin.activationEvents.isNotEmpty() && plugin.activationEvents != listOf("onStartup")) {
                        if (isNotEmpty()) append(" · ")
                        append(activationLabel)
                        append(plugin.activationEvents.joinToString(", "))
                    }
                }
                if (capsText.isNotEmpty()) {
                    Text(
                        text = capsText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MuseMonoFontFamily,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // trust insignia
            Surface(
                color = if (plugin.trust == "full-access") {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                shape = MuseShapes.small,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    text = plugin.trust,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (plugin.trust == "full-access") {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}
