param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot\bootstrap.ps1"
& "$PSScriptRoot\maven.ps1" -B -ntp -pl server -am '-DskipTests' package
$oldStore = $env:WAVECOVE_ACCOUNT_FILE
$oldPort = $env:PORT
$oldBind = $env:WAVECOVE_BIND_ADDRESS
try {
    if (!$env:DATABASE_URL -and !$env:WAVECOVE_ACCOUNT_FILE) {
        $storeDirectory = Join-Path $env:LOCALAPPDATA 'Wavecove\server'
        New-Item -ItemType Directory -Force -Path $storeDirectory | Out-Null
        $env:WAVECOVE_ACCOUNT_FILE = Join-Path $storeDirectory 'accounts'
    }
    $env:PORT = "$Port"
    if (!$oldBind) { $env:WAVECOVE_BIND_ADDRESS = '127.0.0.1' }
    & "$env:LOCALAPPDATA\BatePapo\build-tools\jdk-21.0.13+9\bin\java.exe" -jar "$root\server\target\server-1.0.0.jar"
    if ($LASTEXITCODE -ne 0) { throw 'Servidor encerrado com erro.' }
} finally { $env:WAVECOVE_ACCOUNT_FILE = $oldStore; $env:PORT = $oldPort; $env:WAVECOVE_BIND_ADDRESS = $oldBind }
