param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot\bootstrap.ps1"
if (!(Test-Path -LiteralPath "$root\server\target\server-1.0.0.jar")) { & "$PSScriptRoot\maven.ps1" -B -ntp -pl server -am package }
$oldStore = $env:WAVECOVE_ACCOUNT_FILE
$oldPort = $env:PORT
try {
    if (!$env:DATABASE_URL -and !$env:WAVECOVE_ACCOUNT_FILE) {
        $storeDirectory = Join-Path $env:LOCALAPPDATA 'Wavecove\server'
        New-Item -ItemType Directory -Force -Path $storeDirectory | Out-Null
        $env:WAVECOVE_ACCOUNT_FILE = Join-Path $storeDirectory 'accounts'
    }
    $env:PORT = "$Port"
    & "$env:LOCALAPPDATA\BatePapo\build-tools\jdk-21.0.13+9\bin\java.exe" -jar "$root\server\target\server-1.0.0.jar"
    if ($LASTEXITCODE -ne 0) { throw 'Servidor encerrado com erro.' }
} finally { $env:WAVECOVE_ACCOUNT_FILE = $oldStore; $env:PORT = $oldPort }
