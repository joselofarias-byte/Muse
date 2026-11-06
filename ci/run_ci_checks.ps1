[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('ci-scripts', 'lanes', 'static', 'unit', 'debug')]
    [string] $Lane,
    # P4-9: Consume la salida de carriles de pr_check — CI Pasar en el ámbito afectado(separados por comas).
    # esta vacio = desconocido/cantidad total(tag,push, local),Todos los scripts se ejecutan,Mantener la compatibilidad con versiones anteriores.
    [string] $ChangedScopes = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

$python = if (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' } else { 'python' }
$gradle = if ($IsWindows -and (Test-Path './gradlew.bat')) { './gradlew.bat' } else { './gradlew' }
$skipReleaseGuards = @('-PreleaseSkipVersionCheck=true', '-PreleaseSkipKeystoreCheck=true')

# Windows La consola china tiene por defecto GBK: algunos scripts se imprimirán ⚠/✓/✗(No en el juego de caracteres GBK),
# Cuando este elemento no está configurado, Python arroja directamente UnicodeEncodeError y hace que falle todo el carril.
# (2026-09-29 Medición real:check_repo_hygiene.py / check_localizations.py Caerá en el truco). Unity aplica UTF-8.
$env:PYTHONIOENCODING = 'utf-8'

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Command,
        [Parameter(Mandatory = $false)]
        [string[]] $Arguments = @()
    )

    Write-Host "> $Command $($Arguments -join ' ')"
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "CI command failed with exit code ${LASTEXITCODE}: $Command"
    }
}

switch ($Lane) {
    'ci-scripts' {
        # Ejecutar archivo por archivo de forma independiente:python Ejecute solo el primer archivo como __main__ a la vez,
        # Varios archivos en el mismo proceso omitirán silenciosamente los archivos de prueba posteriores(verde falso).
        @(
            'ci/test/test_pr_check.py',
            'ci/test/test_engineering_discipline.py',
            'ci/test/test_hardcoded_cjk.py',
            'ci/test/test_release_preflight.py',
            'ci/test/test_check_ktlint_report.py',
            'ci/test/test_check_detekt_debt.py',
            'ci/test/test_baseline_fail_closed.py',
            'ci/test/test_check_localizations.py',
            'ci/test/test_repo_hygiene.py',
            'ci/test/test_migration_coverage.py',
            # CMP-11 / I18N-02 / A11Y-03 Prueba única de script de barandilla
            'ci/test/test_component_convergence.py',
            'ci/test/test_translation_residue.py',
            'ci/test/test_font_scale_clipping.py',
            # LAYOUT-01 Prueba única de script de barandilla
            'ci/test/test_row_text_width.py'
        ) | ForEach-Object {
            Invoke-Checked $python @($_)
        }
    }
    'lanes' {
        # tag Los lanzamientos no tienen una base de relaciones públicas; La comparación con origen/principal informará erróneamente las confirmaciones publicadas como lotes de cambios.
        # tag Aún así, ejecute todos los scripts de calidad del repositorio a continuación, omitiendo solo el análisis de ruta que solo sea significativo para los cambios de relaciones públicas.
        if ($env:GITHUB_REF_TYPE -ne 'tag') {
            Invoke-Checked $python @('ci/script/pr_check.py', '--base', 'origin/main', '--output', 'json')
        } else {
            Write-Host 'tag build: skip PR diff routing, keep repository lane checks'
        }
        # P4-9: Ruta por alcance pr_check — Ejecute solo las categorías afectadas;No se pasó ningún alcance(=cantidad total)Todos corren.
        $scopes = @()
        if (-not [string]::IsNullOrWhiteSpace($ChangedScopes)) {
            $scopes = $ChangedScopes.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
        }
        function Test-ScopeAny([string[]]$Need) {
            if ($scopes.Count -eq 0) { return $true }  # desconocido → carrera completa
            foreach ($n in $Need) { if ($scopes -contains $n) { return $true } }
            return $false
        }
        function Invoke-LaneScript([string]$Name, [string[]]$Need) {
            if (Test-ScopeAny $Need) {
                Invoke-Checked $python @("ci/script/$Name")
            } else {
                Write-Host "skip $Name (no affected scope in $($Need -join '/'))"
            }
        }
        # Control de salud rápido:No importa qué cambios se realicen, ejecute
        @(
            'check_repo_hygiene.py',
            'check_engineering_discipline.py',
            'check_hardcoded_cjk.py'
        ) | ForEach-Object {
            Invoke-Checked $python @("ci/script/$_")
        }
        Invoke-LaneScript 'check_localizations.py' @('localization')
        Invoke-LaneScript 'check_migration_coverage.py' @('android_jvm', 'android_full', 'script')
        Invoke-LaneScript 'check_design_tokens.py' @('android_jvm', 'android_resources', 'android_full')
        Invoke-LaneScript 'check_hardcoded_font_size.py' @('android_jvm', 'android_resources', 'android_full')
        Invoke-LaneScript 'check_icon_content_description.py' @('android_jvm', 'android_resources', 'android_full')
        Invoke-LaneScript 'check_touch_target.py' @('android_jvm', 'android_resources', 'android_full')
        Invoke-LaneScript 'check_horizontal_inset.py' @('android_jvm', 'android_resources', 'android_full')
        # LAYOUT-01: Row El texto interior no está ampliado.(Ajuste de línea palabra por palabra/Columna de texto colapsada) — Escaneo léxico puro bajo ui/ .kt
        Invoke-LaneScript 'check_row_text_width.py' @('android_jvm', 'android_resources', 'android_full')
        # v2.6.3: Está prohibido pasar % de literales de marcador de posición como parámetros de formato para recursos de cadena.
        # v2.6.2 El inevitable regreso de (establecimiento→Chat de página completa IllegalFormatConversionException)
        # El tipo es legal,lint/El compilador no puede encontrarlo.,Sólo explota durante el período de combinación de la máquina real.,Por eso el control de acceso es necesario.
        Invoke-LaneScript 'check_string_resource_args.py' @('android_jvm', 'android_resources', 'android_full')
    }
    'static' {
        Invoke-Checked $gradle (@(
            'detekt',
            'ktlintCheck',
            # P4-1: Fuente Kotlin de pelusa verdadera(AGP9 Descargue ktlint-gradle y verifique solo .kts).
            # Escanee cada módulo ktlintKotlinSourceCheck con la CLI oficial .kt,Generado automáticamente para la primera ejecución ktlint-baseline.xml(Ya en stock),
            # Agregar nuevas preguntas después no funcionará.;check_ktlint_report.py Luego, afirme que el informe de cada módulo no está vacío y registra la cantidad real de archivos lint.
            'ktlintKotlinSourceCheck',
            'lintDebug',
            'koverXmlReport',
            # P4-5: Finalización del control de acceso a la cobertura — common/accessibility/material3 Añade minBound e inclúyelo en la verificación.
            ':ai:koverCachedVerifyDebug',
            ':memory:koverCachedVerifyDebug',
            ':app:koverCachedVerifyDebug',
            ':common:koverCachedVerifyDebug',
            ':accessibility:koverCachedVerifyDebug',
            ':material3:koverCachedVerifyDebug'
        ) + $skipReleaseGuards)
        # P4-2: detekt afirmación del techo de deuda(Valla doble total + módulo individual,Se prohíben nuevas incorporaciones.;Bajar manualmente después de la limpieza por lotes cap)
        Invoke-Checked $python @('ci/script/check_detekt_debt.py')
        Invoke-Checked $python @('ci/script/check_ktlint_report.py')
        # CMP-11: Barandilla de convergencia de componentes — ui/** Citar directamente el control interactivo M3 sólo lo reducirá pero no lo aumentará.(Los archivos nuevos fuera de la línea base fallarán)
        Invoke-Checked $python @('ci/script/check_component_convergence.py')
        # I18N-02: El paquete de idioma distinto del chino, CJK, solo permanece degradado pero no actualizado(Línea de base completa 0,Por encima de la línea de base significa fracaso)
        Invoke-Checked $python @('ci/script/check_translation_residue.py')
        # A11Y-03: Barandilla de corte de fuente grande — Altura fija para contener texto.(>=32dp)Solo cayendo pero no subiendo(Línea de base completa 0)
        Invoke-Checked $python @('ci/script/check_font_scale_clipping.py')
    }
    'unit' {
        Invoke-Checked $gradle (@(
            ':app:testDebugUnitTest',
            ':memory:testDebugUnitTest',
            ':ai:testDebugUnitTest',
            ':common:testDebugUnitTest',
            ':accessibility:testDebugUnitTest',
            # P4-4: material3 Anteriormente, se filtró fuera del carril de prueba único.
            ':material3:testDebugUnitTest'
        ) + $skipReleaseGuards)
    }
    'debug' {
        Invoke-Checked $gradle (@('assembleDebug') + $skipReleaseGuards)
    }
}

Write-Host "CI lane '$Lane' PASS"
