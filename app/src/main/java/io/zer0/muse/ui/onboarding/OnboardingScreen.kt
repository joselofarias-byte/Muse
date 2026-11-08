package io.zer0.muse.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.data.provider.ProviderModelFetcher
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.settings.ModelAbilityChips
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.PresetThemes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// ── Estado de la conexión de prueba ──────────────────────────────────────────────
private sealed class TestStatus {
    data object Idle : TestStatus()
    data object Loading : TestStatus()
    data class Success(val models: List<Model>) : TestStatus()
    data class Error(val message: String) : TestStatus()
}

/**
 * Página de inicio — pantalla completa HorizontalPager,8 Complete la configuración inicial en un solo paso:
 * bienvenido(Carrusel de saludos multilingüe) → Idioma → saber → memoria → Acceso → modelo → Apariencia → Activar.
 *
 * Flujo de datos:OnboardingScreen recibir [onComplete] Devolución de llamada, obtenida internamente usando koinInject
 * [SettingsRepository] / [AssistantRepository] / [PresetProviders],
 * Guarde instantáneamente en el repositorio de configuración después de completar cada paso.
 *
 * Ficha de diseño: uso [MuseShapes](esquinas redondeadas) y [MusePaddings](ficha de espaciado),
 * Íconos materiales en todas partes (sin emoji),Material 3 componentes.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val assistantRepo: AssistantRepository = koinInject()
    val presetProviders: PresetProviders = koinInject()
    val scope = rememberCoroutineScope()
    val localizedResources = androidx.compose.ui.platform.LocalResources.current
    val pagerState = rememberPagerState(pageCount = { 8 })
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── Estado de cada paso ──
    var selectedLanguage by rememberSaveable { mutableStateOf("es") }
    var selectedThemeId by rememberSaveable { mutableStateOf("mono") }
    var userName by rememberSaveable { mutableStateOf("") }
    var agentName by rememberSaveable { mutableStateOf("Muse") }
    var selectedPresetId by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var customBaseUrl by rememberSaveable { mutableStateOf("") }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<TestStatus>(TestStatus.Idle) }
    var fetchedModels by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var modelSearchQuery by rememberSaveable { mutableStateOf("") }
    var providerSkipped by rememberSaveable { mutableStateOf(false) }
    // v2.x: interruptor del sistema de memoria(Agregar nuevos pasos;Activado de forma predeterminada,Cambiar a acceso instantáneo)
    var memoryEnabled by rememberSaveable { mutableStateOf(true) }

    // Ocultar el teclado al pasar páginas
    LaunchedEffect(pagerState.currentPage) {
        keyboardController?.hide()
    }

    // El proveedor predeterminado actualmente seleccionado (la baseUrl completada por el usuario se inyecta al personalizar)
    val selectedPreset: ProviderConfig? = remember(selectedPresetId, customBaseUrl) {
        presetProviders.all.firstOrNull { it.id == selectedPresetId }?.let { preset ->
            if (preset.id == "preset_custom_openai" && customBaseUrl.isNotBlank()) {
                preset.copy(baseUrl = customBaseUrl)
            } else {
                preset
            }
        }
    }

    val pageCount = 8
    val currentPage = pagerState.currentPage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // punto de progreso superior
        ProgressDots(
            currentPage = currentPage,
            pageCount = pageCount,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MusePaddings.sectionGap),
        )

        // área de contenido
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> StepWelcome()
                1 -> StepLanguage(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { lang ->
                        selectedLanguage = lang
                        // I4: Cambio de idioma en caliente — Reorganizado inmediatamente por RuntimeLocaleProvider después de guardar,no reconstruir Activity
                        scope.launch { settings.saveLanguage(lang) }
                    },
                )
                2 -> StepNames(
                    userName = userName,
                    onUserNameChange = { userName = it },
                    agentName = agentName,
                    onAgentNameChange = { agentName = it },
                )
                3 -> StepMemory(
                    memoryEnabled = memoryEnabled,
                    onMemoryEnabledChange = { v ->
                        memoryEnabled = v
                        scope.launch { settings.saveMemoryEnabled(v) }
                    },
                )
                4 -> StepProviderConfig(
                    presetProviders = presetProviders,
                    selectedPresetId = selectedPresetId,
                    onPresetSelected = { id ->
                        selectedPresetId = id
                        testStatus = TestStatus.Idle
                        fetchedModels = emptyList()
                    },
                    apiKey = apiKey,
                    onApiKeyChange = {
                        apiKey = it
                        testStatus = TestStatus.Idle
                    },
                    customBaseUrl = customBaseUrl,
                    onCustomBaseUrlChange = {
                        customBaseUrl = it
                        testStatus = TestStatus.Idle
                    },
                    apiKeyVisible = apiKeyVisible,
                    onToggleApiKeyVisible = { apiKeyVisible = !apiKeyVisible },
                    testStatus = testStatus,
                    onTestConnection = {
                        val preset = selectedPreset
                        // v1.0.18: allowMissingApiKey proveedor(Como SiliconFlow gratuito)Permite probar la conexión sin necesidad de rellenar la clave.
                        if (preset == null || (apiKey.isBlank() && !preset.allowMissingApiKey)) return@StepProviderConfig
                        testStatus = TestStatus.Loading
                        scope.launch {
                            val config = preset.copy(apiKey = apiKey)
                            // Verifique con una conversación real en lugar de simplemente mirar /modelos.
                            // La página de introducción es donde el usuario contacta por primera vez con un proveedor. Fracasar aquí equivale a decirle“No puedo usarlo”,
                            // Y /models a menudo simplemente no está implementado o tiene autenticación separada.
                            when (val outcome = ProviderModelFetcher.verify(config)) {
                                is ProviderModelFetcher.VerifyOutcome.Ready -> {
                                    fetchedModels = outcome.models
                                    testStatus = TestStatus.Success(outcome.models)
                                    selectedModelId = outcome.modelId
                                    // Una vez que la prueba sea exitosa, se colocará en la biblioteca.(idempotente),Ya no depende de"Siguiente paso"sincronización ——
                                    // Haga clic nuevamente después de que la prueba anterior sea exitosa"saltar"El contenido completado se descartará silenciosamente.
                                    val bound = config.copy(
                                        baseUrl = outcome.usedConfig.baseUrl,
                                        models = outcome.models,
                                    )
                                    settings.upsertProvider(bound)
                                    settings.setActiveProvider(bound.id)
                                    settings.saveSelectedModel(outcome.modelId)
                                }
                                is ProviderModelFetcher.VerifyOutcome.NotReady -> {
                                    Logger.w("Onboarding", "La conexión de prueba falló: ${outcome.kind} ${outcome.reason}")
                                    testStatus = TestStatus.Error(
                                        localizedResources.getString(
                                            R.string.settings_provider_test_error_failed,
                                            friendlyConnectError(outcome.reason),
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    onSkip = {
                        // v2.x: Cuando la prueba sea exitosa, la configuración se colocará inmediatamente en la biblioteca durante el proceso de prueba.;saltar = saltar"Acceso+modelo"dos pasos,
                        // Retención del tema y del final(saltar a"Apariencia"paso).
                        val preset = selectedPreset
                        if (testStatus is TestStatus.Success && preset != null) {
                            scope.launch {
                                if (selectedModelId.isNotBlank()) {
                                    settings.saveSelectedModel(selectedModelId)
                                }
                                pagerState.animateScrollToPage(6)
                            }
                        } else {
                            providerSkipped = true
                            scope.launch { pagerState.animateScrollToPage(6) }
                        }
                    },
                )
                5 -> StepSelectModel(
                    models = fetchedModels,
                    selectedModelId = selectedModelId,
                    onSelectModel = { id ->
                        selectedModelId = id
                        scope.launch { settings.saveSelectedModel(id) }
                    },
                    searchQuery = modelSearchQuery,
                    onSearchQueryChange = { modelSearchQuery = it },
                    providerSkipped = providerSkipped,
                )
                6 -> StepTheme(
                    selectedThemeId = selectedThemeId,
                    onThemeSelected = { id ->
                        selectedThemeId = id
                        scope.launch { settings.saveThemeId(id) }
                    },
                )
                7 -> StepComplete()
            }
        }

        // Área del botón inferior
        BottomButtons(
            currentPage = currentPage,
            // paso de acceso(4)La prueba debe tener éxito antes de poder dar el siguiente paso.
            canGoNext = when (currentPage) {
                4 -> testStatus is TestStatus.Success
                else -> true
            },
            onPrevious = {
                scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
            },
            onNext = {
                // Guarde los datos del paso actual antes de pasar las páginas
                scope.launch {
                    saveStepData(
                        currentPage = currentPage,
                        settings = settings,
                        assistantRepo = assistantRepo,
                        userName = userName,
                        agentName = agentName,
                        selectedPreset = selectedPreset,
                        apiKey = apiKey,
                        fetchedModels = fetchedModels,
                        selectedModelId = selectedModelId,
                        providerSkipped = providerSkipped,
                    )
                    val targetPage = when {
                        // paso de acceso(4)→ Si te has saltado la configuración, ve directamente a"Apariencia"(6),Por cierto, salta el paso del modelo.
                        currentPage == 4 && providerSkipped -> 6
                        else -> currentPage + 1
                    }
                    pagerState.animateScrollToPage(targetPage)
                }
            },
            onComplete = {
                scope.launch {
                    saveStepData(
                        currentPage = currentPage,
                        settings = settings,
                        assistantRepo = assistantRepo,
                        userName = userName,
                        agentName = agentName,
                        selectedPreset = selectedPreset,
                        apiKey = apiKey,
                        fetchedModels = fetchedModels,
                        selectedModelId = selectedModelId,
                        providerSkipped = providerSkipped,
                    )
                    settings.saveOnboardingShown()
                    onComplete()
                }
            },
        )
    }
}

// ── punto de progreso superior ──────────────────────────────────────────────
@Composable
private fun ProgressDots(currentPage: Int, pageCount: Int, modifier: Modifier = Modifier) {
    // Los puntos sólo pueden expresar "aproximadamente cuántos más hay" y no pueden leer la ubicación específica. Añade un "N / M"
    // Informe a los usuarios directamente en qué paso se encuentran y cuántos pasos tienen en total.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isCurrent = index == currentPage
            val isCompleted = index < currentPage
            val size by animateDpAsState(
                targetValue = if (isCurrent) 10.dp else 6.dp,
                animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                label = "dot_size",
            )
            val color = when {
                isCurrent -> MaterialTheme.colorScheme.onSurface
                isCompleted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = MusePaddings.tightGap)
                    .size(size)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_step_progress, currentPage + 1, pageCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Paso 0: página de bienvenida ──────────────────────────────────────────────
/**
 * Carrusel de saludos — Siga los idiomas admitidos para cambiar uno por uno(chino primero,en segundo lugar ingles,El resto del pedido rota).
 * Cada artículo en sí es el texto original en el idioma correspondiente.,No localizar recursos.
 */
private val WELCOME_GREETINGS = listOf(
    "Hola, por fin nos conocemos.",
    "Hi, nice to meet you.",
    "はじめまして.",
    "반가워요.",
    "Hola, encantado.",
    "Olá, prazer.",
    "Привет, рады знакомству.",
)

@Composable
private fun StepWelcome() {
    // v2.x: Reconstrucción de la primera pantalla. — Logotipo grande solo(Sin marco inferior),El nombre está debajo,saludo del logotipo"Expandir"Admisión.
    // No más subtítulos y mosaicos de funciones:En la primera pantalla solo queda el logo/nombre/un saludo.
    var entered by remember { mutableStateOf(false) }
    var nameVisible by remember { mutableStateOf(false) }
    var greetingVisible by remember { mutableStateOf(false) }
    var greetingIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        // Horario de admisión:logo → nombre → Saludos"Expandir";Luego ingresa al carrusel multiidioma(2.6s/Artículo)
        entered = true
        delay(260)
        nameVisible = true
        delay(390)
        greetingVisible = true
        while (true) {
            delay(2600)
            greetingIndex = (greetingIndex + 1) % WELCOME_GREETINGS.size
        }
    }

    val logoAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = MuseMotion.tween(700),
        label = "welcomeLogoAlpha",
    )
    val logoScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.86f,
        animationSpec = MuseMotion.tween(900),
        label = "welcomeLogoScale",
    )
    val nameAlpha by animateFloatAsState(
        targetValue = if (nameVisible) 1f else 0f,
        animationSpec = MuseMotion.tween(520),
        label = "welcomeNameAlpha",
    )
    val nameOffset by animateDpAsState(
        targetValue = if (nameVisible) 0.dp else 10.dp,
        animationSpec = MuseMotion.tween(560),
        label = "welcomeNameOffset",
    )
    val greetingAlpha by animateFloatAsState(
        targetValue = if (greetingVisible) 1f else 0f,
        animationSpec = MuseMotion.tween(560),
        label = "welcomeGreetingAlpha",
    )
    val greetingScale by animateFloatAsState(
        targetValue = if (greetingVisible) 1f else 0.5f,
        animationSpec = MuseMotion.tween(680, easing = MuseAnimation.EaseOutCubic),
        label = "welcomeGreetingScale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // grande logo — jugar solo,Sin marco inferior
        Image(
            painter = painterResource(R.drawable.ic_muse_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(176.dp)
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                },
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .graphicsLayer { alpha = nameAlpha }
                .offset(y = nameOffset),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        // saludo:Desde la dirección del logotipo"Expandir"aparecer(La parte superior es el origen del zoom.),Luego, el carrusel multilingüe se fusiona
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .graphicsLayer {
                    alpha = greetingAlpha
                    scaleX = greetingScale
                    scaleY = greetingScale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = greetingIndex,
                animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                // Utilice Crossfade en lugar de AnimatedContent: este último viene con SizeTransform de forma predeterminada.
                // Dos saludos con diferentes alturas participarán en el diseño al mismo tiempo y se apilarán en la misma posición.
                // Visualmente, las dos frases están juntas.Crossfade Solo haz desvanecimientos dentro de la misma ranura.
                label = "welcomeGreeting",
            ) { index ->
                Text(
                    text = WELCOME_GREETINGS[index],
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class FeatureItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val description: String,
)

// ── Paso 1: Idioma ────────────────────────────────────────────────
@Composable
private fun StepLanguage(selectedLanguage: String, onLanguageSelected: (String) -> Unit) {
    val languages = listOf(
        "es" to stringResource(R.string.onboarding_lang_es),
        "en" to stringResource(R.string.onboarding_lang_en),
        "pt-rBR" to stringResource(R.string.onboarding_lang_pt),
        "ja" to stringResource(R.string.onboarding_lang_ja),
        "ko" to stringResource(R.string.onboarding_lang_ko),
        "ru" to stringResource(R.string.onboarding_lang_ru),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_language_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_language_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        languages.forEach { (code, label) ->
            LanguageCard(
                label = label,
                selected = selectedLanguage == code,
                onClick = { onLanguageSelected(code) },
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }
    }
}

// ── Paso 6: Apariencia ────────────────────────────────────────────────
@Composable
private fun StepTheme(selectedThemeId: String, onThemeSelected: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_theme_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_theme_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        // 12 Conjunto de cuadrículas de temas (3 columnas x 4 filas)
        PresetThemes.chunked(3).forEach { rowThemes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                rowThemes.forEach { theme ->
                    ThemeColorBlock(
                        theme = theme,
                        selected = selectedThemeId == theme.id,
                        onClick = { onThemeSelected(theme.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Si hay menos de 3, complete los marcadores de posición y mantenga la cuadrícula alineada.
                repeat(3 - rowThemes.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }
    }
}

@Composable
private fun LanguageCard(label: String, selected: Boolean, onClick: () -> Unit) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val borderWidth = if (selected) 1.5.dp else 1.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (selected) {
                Icon(
                    imageVector = MuseIcons.circleCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ThemeColorBlock(
    theme: io.zer0.muse.ui.theme.PresetTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
    val borderWidth = if (selected) 1.5.dp else 1.dp
    val label = stringResource(theme.nameResId)
    Surface(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick),
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = theme.lightScheme.primary,
                modifier = Modifier.size(28.dp),
            ) {}
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Paso 2: Tu nombre ──────────────────────────────────────────
@Composable
private fun StepNames(userName: String, onUserNameChange: (String) -> Unit, agentName: String, onAgentNameChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_names_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_names_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        // v1.0.28: label Colóquelo encima del cuadro de entrada.,Evite el bloque de fondo blanco de la etiqueta interior de OutlinedTextField
        Text(
            text = stringResource(R.string.onboarding_names_user_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
        MuseTextField(
            value = userName,
            onValueChange = onUserNameChange,
            placeholder = { Text(stringResource(R.string.onboarding_names_user_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_names_agent_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
        MuseTextField(
            value = agentName,
            onValueChange = onAgentNameChange,
            placeholder = { Text(stringResource(R.string.onboarding_names_agent_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_names_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Paso 3: recuerda ────────────────────────────────────────────────
@Composable
private fun StepMemory(memoryEnabled: Boolean, onMemoryEnabledChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_memory_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_memory_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        // tarjeta de interruptor de memoria:Título + descripción de una línea + lado derecho Switch
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MuseIcons.atom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_memory_switch_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.onboarding_memory_switch_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(MusePaddings.contentGap))
                MuseSwitch(
                    checked = memoryEnabled,
                    onCheckedChange = onMemoryEnabledChange,
                )
            }
        }
    }
}

// ── Paso 4: Configurar el proveedor ──────────────────────────────────────────
/**
 * poner río arriba/Los errores internos se traducen en una frase procesable para el usuario.
 *
 * El texto original devuelto por el upstream (como `HTTP 500 [server error]: Model ...`) permanecer en el registro,
 * La interfaz primero proporciona "qué se debe cambiar" y luego adjunta una breve información original para copiar cuando se solicita ayuda.
 */
private fun friendlyConnectError(raw: String): String {
    val lower = raw.lowercase()
    // Tabla de reglas: combinar en orden y regresar si se golpea.
    // Utilice una tabla en lugar de un cuándo largo para evitar exceder la complejidad ciclomática de una sola función.
    val rules = listOf(
        listOf("401", "unauthorized", "invalid api key") to "API La clave no es válida o ha caducado, complétela nuevamente",
        listOf("403", "forbidden") to "Sin derechos de acceso, confirme que la clave se ha activado para este modelo",
        listOf("404", "model_not_found", "no such model") to "La dirección de la interfaz o el nombre del modelo es incorrecto, verifíquelo e inténtelo nuevamente.",
        listOf("429", "rate limit") to "La solicitud es demasiado frecuente, inténtalo de nuevo más tarde.",
        listOf("timeout", "timed out") to "Se agotó el tiempo de conexión; verifique la dirección de red o de interfaz",
        listOf("500", "502", "503", "server error") to "El servicio del proveedor es anormal, inténtelo nuevamente más tarde; Si continúa fallando, es posible que el modelo integrado esté fuera de línea.",
        listOf("no model") to "No se encontró ningún modelo comprobable. Haga clic en \"Obtener\" para obtener la lista de modelos primero.",
    )
    val hit = rules.firstOrNull { (keys, _) -> keys.any { it in lower } }
    return hit?.second ?: raw
}

@Composable
private fun StepProviderConfig(
    presetProviders: PresetProviders,
    selectedPresetId: String,
    onPresetSelected: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    customBaseUrl: String,
    onCustomBaseUrlChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisible: () -> Unit,
    testStatus: TestStatus,
    onTestConnection: () -> Unit,
    onSkip: () -> Unit,
) {
    val isCustomSelected = selectedPresetId == "preset_custom_openai"
    val selectedPreset = presetProviders.all.firstOrNull { it.id == selectedPresetId }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_provider_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_provider_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))

        MuseDropdown(
            value = selectedPresetId,
            onValueChange = onPresetSelected,
            label = stringResource(R.string.onboarding_provider_select_label),
            options = presetProviders.onboardingPresets.map { it.id to it.displayName },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))

        // Proveedor personalizado:baseUrl entrada
        androidx.compose.animation.AnimatedVisibility(
            visible = isCustomSelected,
            enter = MuseMotion.expandFadeEnter(),
            exit = MuseMotion.expandFadeExit(),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.onboarding_provider_custom_baseurl_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                MuseTextField(
                    value = customBaseUrl,
                    onValueChange = onCustomBaseUrlChange,
                    placeholder = { Text(stringResource(R.string.onboarding_provider_custom_baseurl_input)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
            }
        }

        // API Key entrada
        Text(
            text = stringResource(R.string.onboarding_provider_apikey_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        MuseTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            placeholder = { Text(stringResource(R.string.onboarding_provider_apikey_input)) },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                MuseTactileButton(
                    icon = if (apiKeyVisible) {
                        MuseIcons.eyeOff
                    } else {
                        MuseIcons.eye
                    },
                    onClick = onToggleApiKeyVisible,
                    contentDescription = stringResource(
                        if (apiKeyVisible) {
                            R.string.onboarding_provider_apikey_hide
                        } else {
                            R.string.onboarding_provider_apikey_show
                        },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(MusePaddings.itemGap))

        // Botón de prueba de conexión (el proveedor personalizado también requiere baseUrl)
        // v1.0.18: allowMissingApiKey proveedor(Como SiliconFlow gratuito)Permite probar la conexión sin necesidad de rellenar la clave.
        val canTest = selectedPresetId.isNotBlank() &&
            (apiKey.isNotBlank() || selectedPreset?.allowMissingApiKey == true) &&
            (!isCustomSelected || customBaseUrl.isNotBlank())
        val testEnabled = canTest && testStatus !is TestStatus.Loading
        Surface(
            onClick = onTestConnection,
            enabled = testEnabled,
            shape = MuseShapes.pill,
            color = if (testEnabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (testStatus is TestStatus.Loading) {
                    MuseSpinner(
                        size = 20.dp,
                        color = MaterialTheme.colorScheme.surface,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.onboarding_provider_test_button),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = if (testEnabled) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        }

        // Visualización del resultado de la prueba
        when (testStatus) {
            is TestStatus.Success -> {
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.circleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                    Text(
                        text = stringResource(R.string.onboarding_provider_test_success, testStatus.models.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // v2.x: Resumen de coincidencia de competencias — probar"La lista extraída ya tiene información de capacidad."(Visión/razonamiento/Herramientas)
                val visionCount = testStatus.models.count { it.supportsVisionInput() }
                val reasoningCount = testStatus.models.count { it.supportsReasoning() }
                val toolCount = testStatus.models.count { it.supportsToolCalling() }
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = stringResource(R.string.onboarding_provider_caps, visionCount, reasoningCount, toolCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TestStatus.Error -> {
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.alertCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                    Text(
                        text = testStatus.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        // Saltar configuración
        MuseCapsuleButton(
            text = stringResource(R.string.onboarding_provider_skip),
            onClick = onSkip,
            variant = IosCapsuleButtonVariant.Text,
            fillWidth = false,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// ── Paso 4: Selecciona un modelo ──────────────────────────────────────────
@Composable
private fun StepSelectModel(
    models: List<Model>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    providerSkipped: Boolean,
) {
    if (providerSkipped || models.isEmpty()) {
        // Cuando se omite el paso 3 o no hay ningún modelo, solicite al usuario
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MusePaddings.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_model_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.onboarding_model_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val filteredModels = remember(searchQuery, models) {
        if (searchQuery.isBlank()) {
            models
        } else {
            models.filter { model ->
                model.id.contains(searchQuery, ignoreCase = true) ||
                    model.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_model_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        MuseTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text(stringResource(R.string.onboarding_model_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            items(filteredModels, key = { "${it.providerId}:${it.id}" }) { model ->
                ModelItem(
                    model = model,
                    selected = selectedModelId == model.id,
                    onClick = { onSelectModel(model.id) },
                )
            }
        }
    }
}

@Composable
private fun ModelItem(model: Model, selected: Boolean, onClick: () -> Unit) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val borderWidth = if (selected) 1.5.dp else 1.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                // v2.x: Insignia de competencia — Mismo calibre que la página de edición del proveedor.(Reutilizar el mismo componente)
                ModelAbilityChips(model = model)
            }
            if (selected) {
                Icon(
                    imageVector = MuseIcons.circleCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── Paso 5: Completa la página ──────────────────────────────────────────────
@Composable
private fun StepComplete() {
    val tutorials = listOf(
        FeatureItem(
            MuseIcons.chat,
            stringResource(R.string.onboarding_tutorial_chat_title),
            stringResource(R.string.onboarding_tutorial_chat_desc),
        ),
        FeatureItem(
            MuseIcons.user,
            stringResource(R.string.onboarding_tutorial_assistant_title),
            stringResource(R.string.onboarding_tutorial_assistant_desc),
        ),
        FeatureItem(
            MuseIcons.wrench,
            stringResource(R.string.onboarding_tutorial_tools_title),
            stringResource(R.string.onboarding_tutorial_tools_desc),
        ),
        FeatureItem(
            MuseIcons.cloudUpload,
            stringResource(R.string.onboarding_tutorial_backup_title),
            stringResource(R.string.onboarding_tutorial_backup_desc),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // icono hecho:Usar avatar de asistente predeterminado
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(80.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.default_assistant_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.onboarding_complete_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_complete_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        tutorials.forEach { item ->
            TutorialCard(item = item)
            Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        }
    }
}

@Composable
private fun TutorialCard(item: FeatureItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(MusePaddings.cardInner.calculateTopPadding()))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── botón de cápsula ──────────────────────────────────────────

@Composable
private fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MuseShapes.pill,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        },
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
                Spacer(modifier = Modifier.width(MusePaddings.tightGap))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (enabled) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

@Composable
private fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            // El trazo alfa de 0,2 original es casi invisible y el contorno del botón está borroso;
            // El botón principal tiene un fondo negro sólido y el contraste entre los dos es demasiado extremo. Oscurezca a 0,45 para que las operaciones secundarias sean igualmente visibles.
            width = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        ),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(MusePaddings.tightGap))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── botón inferior ──────────────────────────────────────────────────

@Composable
private fun BottomButtons(currentPage: Int, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onComplete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (currentPage) {
            // Paso 0: Sólo"Empezar a configurar"Botón (centrado)
            0 -> {
                PrimaryPillButton(
                    text = stringResource(R.string.onboarding_button_start),
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                )
            }
            // Paso 7: Sólo"empezar"Botón (centrado)
            7 -> {
                // v2.0 Reparación: Surface Internamente Box,Si dos botones se yuxtaponen directamente, se superpondrán y el texto se superpondrá. —
                // Cambiar a disposición vertical(El botón principal está en la parte superior y la entrada de visitantes está en la parte inferior.).
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PrimaryPillButton(
                        text = stringResource(R.string.onboarding_button_finish),
                        onClick = onComplete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen)
                            .padding(top = MusePaddings.screen),
                    )
                }
            }
            // Pasos 1-6: izquierda"Paso anterior", cierto"Siguiente paso"
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    SecondaryPillButton(
                        text = stringResource(R.string.onboarding_button_previous),
                        icon = MuseIcons.arrowLeft,
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryPillButton(
                        text = stringResource(R.string.onboarding_button_next),
                        icon = MuseIcons.arrowRight,
                        onClick = onNext,
                        enabled = canGoNext,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ── Guardar datos de paso ──────────────────────────────────────────────

/**
 * Guarde los datos del paso actual en un almacenamiento persistente antes de pasar la página.
 *
 * La lógica de guardado correspondiente a cada paso:
 *  - Paso 2: guardar el nombre de usuario (mockLogin)+ Actualizar el nombre del asistente predeterminado
 *  - Paso 4: Cree y guarde la configuración de ProviderConfig + para activar el proveedor
 *  - Paso 5: guarda el modelo seleccionado id
 *
 * Idioma(pasos 1)/interruptor de memoria(pasos 3)/Tema(pasos 6)Se guarda inmediatamente cuando el usuario opera y no se repetirá aquí.
 */
private suspend fun saveStepData(
    currentPage: Int,
    settings: SettingsRepository,
    assistantRepo: AssistantRepository,
    userName: String,
    agentName: String,
    selectedPreset: ProviderConfig?,
    apiKey: String,
    fetchedModels: List<Model>,
    selectedModelId: String,
    providerSkipped: Boolean,
) {
    when (currentPage) {
        2 -> {
            // Guardar nombre de usuario
            settings.mockLogin(userName)
            // Actualizar el nombre del asistente predeterminado
            val defaultAssistant = assistantRepo.getById("default")
            if (defaultAssistant != null && agentName.isNotBlank()) {
                assistantRepo.upsert(defaultAssistant.copy(name = agentName))
            }
            // v1.0.27 Reparación: El título completado en la página de la guía se sincroniza con UserProfile,Hacer visible la página de perfil e inyectar system prompt
            // La implementación anterior solo escribe en la tabla account_user_name + Assistant,UserProfileEditPage lo que se lee es user_profile_json,Tres conjuntos de almacenamiento independiente sin sincronización
            val profile = settings.getUserProfile()
            settings.saveUserProfile(
                profile.copy(
                    userNickName = userName.ifBlank { profile.userNickName },
                    assistantName = agentName.ifBlank { profile.assistantName },
                ),
            )
        }
        4 -> {
            // Crear y guardar la configuración del proveedor (solo si la prueba es exitosa y no se omite)
            // v1.0.18: allowMissingApiKey proveedor(Como SiliconFlow gratuito)Permitir guardar sin clave
            // v2.0: Utilice upsert para guardar de forma idempotente. — Cuando la prueba sea exitosa, se colocará inmediatamente en la biblioteca.,Aquí está la página para pasar al final.,evitar entradas duplicadas
            if (!providerSkipped && selectedPreset != null &&
                (apiKey.isNotBlank() || selectedPreset.allowMissingApiKey)
            ) {
                val config = selectedPreset.copy(
                    apiKey = apiKey,
                    models = fetchedModels,
                )
                settings.upsertProvider(config)
                settings.setActiveProvider(config.id)
            }
        }
        5 -> {
            // Guardar modelo seleccionado
            settings.saveSelectedModel(selectedModelId.ifBlank { null })
        }
    }
}
