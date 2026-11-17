$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolsDir = Join-Path $env:LOCALAPPDATA 'BatePapo\build-tools'
$jdkDir = Join-Path $toolsDir 'jdk-21.0.13+9'
$maven = Join-Path $toolsDir 'apache-maven-3.10.1\bin\mvn.cmd'
if (!(Test-Path -LiteralPath $maven) -or !(Test-Path -LiteralPath "$jdkDir\bin\java.exe")) {
    & "$PSScriptRoot\bootstrap.ps1"
}
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $jdkDir
    & $maven -f "$root\pom.xml" @args
    if ($LASTEXITCODE -ne 0) { throw "Maven terminou com código $LASTEXITCODE" }
} finally { $env:JAVA_HOME = $previousJavaHome }
