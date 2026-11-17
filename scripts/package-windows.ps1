param([switch]$SkipTests, [switch]$Installer)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$appVersion = '1.3.0'
$toolsDir = Join-Path $env:LOCALAPPDATA 'BatePapo\build-tools'
& "$PSScriptRoot\bootstrap.ps1"
$mavenArgs = @('-B', '-ntp', 'package')
if ($SkipTests) { $mavenArgs += '-DskipTests' }
& "$PSScriptRoot\maven.ps1" @mavenArgs
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = Join-Path $toolsDir "package-$stamp"
$inputDir = Join-Path $stage 'input'
$outputDir = Join-Path $stage 'output'
$destination = Join-Path $root "dist\$stamp"
New-Item -ItemType Directory -Force -Path "$inputDir\lib", $outputDir, $destination | Out-Null
Copy-Item -LiteralPath "$root\client\target\client-1.0.0.jar" -Destination $inputDir
Get-ChildItem -LiteralPath "$root\client\target\lib" -Filter '*.jar' | Copy-Item -Destination "$inputDir\lib"
$jpackage = Join-Path $toolsDir 'jdk-21.0.13+9\bin\jpackage.exe'
& $jpackage --type app-image --name BatePapo --app-version $appVersion --vendor 'Bate-papo' --input $inputDir --dest $outputDir --main-jar client-1.0.0.jar --main-class br.com.batepapo.client.Launcher --add-modules 'java.base,java.desktop,java.logging,java.net.http,java.prefs,java.xml,java.sql,jdk.unsupported,jdk.crypto.ec' --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar aplicativo Windows.' }
Copy-Item -LiteralPath "$root\docs\COMO-USAR.txt" -Destination "$outputDir\BatePapo\COMO-USAR.txt"
Copy-Item -LiteralPath "$root\docs\CONEXAO-ONLINE.txt" -Destination "$outputDir\BatePapo\CONEXAO-ONLINE.txt"
Copy-Item -LiteralPath "$root\client\src\main\resources\fonts\OFL.txt" -Destination "$outputDir\BatePapo\FONTE-LICENCA.txt"
if ($Installer) {
    $oldPath = $env:PATH
    try {
        $env:PATH = "$toolsDir\wix;$oldPath"
        if (!(Get-Command candle.exe -ErrorAction SilentlyContinue)) { throw 'Instalador requer WiX 3 no PATH ou no cache build-tools\wix.' }
        & $jpackage --type msi --name BatePapo --app-version $appVersion --vendor 'Bate-papo' --app-image "$outputDir\BatePapo" --dest $destination --win-per-user-install --win-menu --win-shortcut --win-dir-chooser --win-upgrade-uuid 'e867db01-367c-4476-946e-caf47e45309e'
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar instalador MSI.' }
    } finally { $env:PATH = $oldPath }
}
Compress-Archive -LiteralPath "$outputDir\BatePapo" -DestinationPath "$destination\BatePapo-Windows-x64.zip"
Copy-Item -LiteralPath "$outputDir\BatePapo" -Destination $destination -Recurse
Get-ChildItem -LiteralPath $destination -File | Get-FileHash -Algorithm SHA256 | ForEach-Object { "$($_.Hash)  $([IO.Path]::GetFileName($_.Path))" } | Set-Content -LiteralPath "$destination\SHA256SUMS.txt"
Write-Output "Pacote Windows pronto: $destination"
