$base = "app/src/main/res/values"
$en = "app/src/main/res/values-en"
$locales = @("values-ja", "values-ko", "values-ru", "values-es", "values-pt-rBR")

# Obtenga todos los artículos en el directorio en inglés strings_*.xml Archivo
$enFiles = Get-ChildItem $en -Filter "strings_*.xml"

foreach ($loc in $locales) {
    $dest = "app/src/main/res/$loc"
    if (!(Test-Path $dest)) {
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
    }
    $copied = 0
    foreach ($f in $enFiles) {
        $target = Join-Path $dest $f.Name
        # Solo copia archivos que no existen en el directorio de destino(Mantener las traducciones existentes)
        if (!(Test-Path $target)) {
            Copy-Item $f.FullName $target
            $copied++
        }
    }
    Write-Host "$loc : copied $copied files"
}

Write-Host "`nDone. Verifying..."
