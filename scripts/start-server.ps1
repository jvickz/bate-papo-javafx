param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot\bootstrap.ps1"
if (!(Test-Path -LiteralPath "$root\server\target\server-1.0.0.jar")) { & "$PSScriptRoot\maven.ps1" -B -ntp -pl server -am package }
$oldToken = $env:CHAT_ACCESS_TOKEN
$oldPort = $env:PORT
try {
    if (!$env:CHAT_ACCESS_TOKEN) {
        $secure = Read-Host 'Código da sala (24 a 128 caracteres)' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:CHAT_ACCESS_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
    $env:PORT = "$Port"
    & "$env:LOCALAPPDATA\BatePapo\build-tools\jdk-21.0.13+9\bin\java.exe" -jar "$root\server\target\server-1.0.0.jar"
    if ($LASTEXITCODE -ne 0) { throw 'Servidor encerrado com erro.' }
} finally { $env:CHAT_ACCESS_TOKEN = $oldToken; $env:PORT = $oldPort }
