$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolsDir = Join-Path $env:LOCALAPPDATA 'BatePapo\build-tools'
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
function Get-VerifiedArchive($Url, $Destination, $Checksum, $Algorithm) {
    if (!(Test-Path -LiteralPath $Destination)) {
        & curl.exe --fail --location --retry 3 --silent --show-error $Url --output $Destination
        if ($LASTEXITCODE -ne 0) { throw "Falha ao baixar $Url" }
    }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ne $Checksum.Trim()) {
        throw "Checksum incorreto: $Destination. Remova esse arquivo e tente novamente."
    }
}
$jdkDir = Join-Path $toolsDir 'jdk-21.0.13+9'
if (!(Test-Path -LiteralPath "$jdkDir\bin\java.exe")) {
    $zip = Join-Path $toolsDir 'jdk.zip'
    Get-VerifiedArchive 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip' $zip 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9' 'SHA256'
    & tar.exe -xf $zip -C $toolsDir
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao extrair Java.' }
}
$mavenDir = Join-Path $toolsDir 'apache-maven-3.10.1'
if (!(Test-Path -LiteralPath "$mavenDir\bin\mvn.cmd")) {
    $url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.1/apache-maven-3.10.1-bin.zip'
    $checksum = (Invoke-WebRequest "$url.sha512" -UseBasicParsing).Content
    if ($checksum -is [byte[]]) { $checksum = [Text.Encoding]::UTF8.GetString($checksum) }
    $zip = Join-Path $toolsDir 'maven.zip'
    Get-VerifiedArchive $url $zip $checksum 'SHA512'
    & tar.exe -xf $zip -C $toolsDir
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao extrair Maven.' }
}
Write-Output "Ferramentas prontas em $toolsDir"
