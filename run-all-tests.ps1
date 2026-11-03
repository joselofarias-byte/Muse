# ============================================================
# Muse Script de prueba completo con un solo clic
# Uso: pwsh .\run-all-tests.ps1 [-Fast] [-NoReport] [-SkipAndroidTest] [-Gates]
# función: Ejecute los 6 módulos(app/memory/ai/common/accessibility/material3)Pruebas unitarias,
#       y cuando se detecta un dispositivo conectado/Ejecutado en el simulador. app androidTest(P4-4 completo).
# código de salida: 0 = Todo pasó; 1 = hay fracaso
# ============================================================
param(
    [switch]$Fast,           # Saltar recopilación,Usar caché directamente(Sólo cambios de medida)
    [switch]$NoReport,       # No generar archivos de informes resumidos
    [switch]$SkipAndroidTest, # hacer cumplir app androidTest(Incluso si el dispositivo no es detectado)
    [switch]$Gates           # Después de ejecutar la prueba única, vuelva a ejecutar la prueba completa. ci/script/check_*.py control de acceso(solo regreso)
)

$ErrorActionPreference = "Stop"
$start = Get-Date
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host " Muse prueba completa" -ForegroundColor Cyan
Write-Host " empezar: $(Get-Date -Format 'HH:mm:ss')" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# 1. Todos los módulos de prueba unitaria(P4-4: maquillar common / accessibility / material3)
$moduleTasks = @(
    ":app:testDebugUnitTest",
    ":memory:testDebugUnitTest",
    ":ai:testDebugUnitTest",
    ":common:testDebugUnitTest",
    ":accessibility:testDebugUnitTest",
    ":material3:testDebugUnitTest"
)
$unitArgs = $moduleTasks + @("--console=plain")
if ($Fast) { $unitArgs += "--rerun-tasks" }
Write-Host "`n[1/2] Pruebas unitarias(6 módulo)..." -ForegroundColor Yellow
$unitOut = & .\gradlew.bat @unitArgs 2>&1
$unitExit = $LASTEXITCODE
$unitOut | Select-Object -Last 5

# 2. app androidTest(Equipo requerido/emulador;Si no, omita y solicite)
$androidExit = 0
$androidRan = $false
$androidOut = @()
$hasDevice = $false
if (-not $SkipAndroidTest) {
    $adb = "adb"
    if (Get-Command adb -ErrorAction SilentlyContinue) {
        $devices = (& adb devices 2>$null | Select-String -Pattern "^\S+\s+device$")
        $hasDevice = $devices -and $devices.Count -gt 0
    }
}
if ($SkipAndroidTest) {
    Write-Host "`n[2/2] androidTest Presionado -SkipAndroidTest para omitir" -ForegroundColor DarkYellow
} elseif (-not $hasDevice) {
    Write-Host "`n[2/2] androidTest saltar:Dispositivo conectado no detectado/emulador(adb devices esta vacio)." -ForegroundColor DarkYellow
    Write-Host "       Vuelva a ejecutar este script después de conectarse al dispositivo,O utilice -SkipAndroidTest para omitir intencionalmente." -ForegroundColor DarkYellow
} else {
    Write-Host "`n[2/2] app androidTest(El dispositivo está conectado)..." -ForegroundColor Yellow
    $androidOut = & .\gradlew.bat ":app:connectedDebugAndroidTest" "--console=plain" 2>&1
    $androidExit = $LASTEXITCODE
    $androidRan = $true
    $androidOut | Select-Object -Last 5
}

# ---- Resumen ----
$elapsed = ((Get-Date) - $start).TotalSeconds
Write-Host "`n==============================================" -ForegroundColor Cyan
Write-Host " Prueba completada: $([math]::Round($elapsed,1))s" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

$allPass = ($unitExit -eq 0) -and ($androidExit -eq 0)

# Recopilar estadísticas de informes XML(Módulo de prueba única 6 + informe de prueba de Android si está disponible)
$totalTests = 0; $totalFail = 0; $totalErr = 0; $totalSkip = 0
$failedSuites = @()
$reportDirs = @(
    "$root\app\build\test-results\testDebugUnitTest",
    "$root\memory\build\test-results\testDebugUnitTest",
    "$root\ai\build\test-results\testDebugUnitTest",
    "$root\common\build\test-results\testDebugUnitTest",
    "$root\accessibility\build\test-results\testDebugUnitTest",
    "$root\material3\build\test-results\testDebugUnitTest",
    "$root\app\build\outputs\androidTest-results\connected"
)
foreach ($dir in $reportDirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem $dir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            [xml]$x = Get-Content $_.FullName
            $totalTests += [int]$x.testsuite.tests
            $totalFail  += [int]$x.testsuite.failures
            $totalErr   += [int]$x.testsuite.errors
            $totalSkip  += [int]$x.testsuite.skipped
            if (([int]$x.testsuite.failures + [int]$x.testsuite.errors) -gt 0) {
                $failedSuites += $x.testsuite.name
            }
        } catch { }
    }
}

Write-Host ""
Write-Host "  Número total de casos de uso: $totalTests" -ForegroundColor White
Write-Host "  Pase:     $($totalTests - $totalFail - $totalErr - $totalSkip)" -ForegroundColor Green
Write-Host "  falló:     $totalFail" -ForegroundColor $(if ($totalFail -gt 0) { "Red" } else { "Green" })
Write-Host "  error:     $totalErr" -ForegroundColor $(if ($totalErr -gt 0) { "Red" } else { "Green" })
Write-Host "  saltar:     $totalSkip" -ForegroundColor Yellow

if ($failedSuites.Count -gt 0) {
    Write-Host "`n  conjunto de fracasos:" -ForegroundColor Red
    $failedSuites | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
}

if ($androidRan) {
    Write-Host "`n  androidTest: ejecutado" -ForegroundColor Green
} else {
    Write-Host "`n  androidTest: saltar(Ningún dispositivo o -SkipAndroidTest)" -ForegroundColor DarkYellow
}

# control de acceso(-Gates): ejecutar todo check_*.py, presione "Si regresar" para determinar
$gatesExit = $null
$gatesSummary = "SKIPPED (-Gates not set)"
if ($Gates) {
    Write-Host "`n== control de acceso (check_*.py) ==" -ForegroundColor Cyan
    $gatesLog = python "$root\ci\script\run_gates.py" --verbose 2>&1
    $gatesExit = $LASTEXITCODE
    $gatesLog | ForEach-Object { Write-Host $_ }
    if ($gatesExit -eq 0) {
        $gatesSummary = "PASS (no regression)"
        Write-Host "  control de acceso: sin regresión OK" -ForegroundColor Green
    } else {
        $gatesSummary = "FAIL (regression)"
        Write-Host "  control de acceso: hay un regreso FAIL" -ForegroundColor Red
    }
}

# archivo de informe resumido
if (-not $NoReport) {
    $reportFile = "$root\test-report-$(Get-Date -Format 'yyyyMMdd-HHmm').txt"
    @"
Muse Informe de prueba completo
tiempo: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Consume mucho tiempo: $([math]::Round($elapsed,1))s
resultado: $(if ($allPass) { 'PASS' } else { 'FAIL' })
androidTest: $(if ($androidRan) { 'EXECUTED' } else { 'SKIPPED (no device or -SkipAndroidTest)' })
caso de uso: $totalTests (Pase $($totalTests - $totalFail - $totalErr - $totalSkip) / falló $totalFail / error $totalErr / saltar $totalSkip)
control de acceso: $gatesSummary
$(if ($failedSuites.Count -gt 0) { "conjunto de fracasos:`n" + ($failedSuites | ForEach-Object { "  - $_" }) -join "`n" } else { '' })
"@ | Set-Content $reportFile
    Write-Host "`n  Informe guardado: $reportFile" -ForegroundColor Cyan
}

# La devolución del control de acceso también se considera un fracaso.(Sólo se determina cuando se utiliza -Gates para evitar que el proceso de impago se vea estancado por la deuda existente.)
$finalPass = $allPass -and ($gatesExit -eq $null -or $gatesExit -eq 0)
if ($finalPass) {
    Write-Host "`n  resultado: Todo pasó ✓  se puede publicar" -ForegroundColor Green
} else {
    Write-Host "`n  resultado: fracaso existencial ✗  Por favor corríjalo antes de publicar." -ForegroundColor Red
}
exit $(if ($finalPass) { 0 } else { 1 })