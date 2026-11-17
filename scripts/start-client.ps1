$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot\bootstrap.ps1"
if (!(Test-Path -LiteralPath "$root\client\target\client-1.0.0.jar")) { & "$PSScriptRoot\maven.ps1" -B -ntp package }
& "$env:LOCALAPPDATA\BatePapo\build-tools\jdk-21.0.13+9\bin\java.exe" -jar "$root\client\target\client-1.0.0.jar"
