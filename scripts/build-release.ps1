param([string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'GasSelfMeterAI/signing'))
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation first.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$storePath = Join-Path $SigningDirectory 'release.jks'
$passwordPath = Join-Path $SigningDirectory 'password.dpapi'
New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
if ((Test-Path -LiteralPath $storePath) -xor (Test-Path -LiteralPath $passwordPath)) {
    throw 'Signing files are incomplete. Restore the matching signing files. Existing keys are never overwritten.'
}
if (-not (Test-Path -LiteralPath $storePath)) {
    $randomBytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($randomBytes)
    $env:GAS_SIGNING_PASSWORD = [Convert]::ToBase64String($randomBytes)
    try {
        & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -genkeypair -keystore $storePath -storetype JKS -alias gas-self-meter-ai -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Gas Self Meter AI, O=mahlernim, C=KR' -storepass:env GAS_SIGNING_PASSWORD -keypass:env GAS_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
        $encrypted = ConvertFrom-SecureString (ConvertTo-SecureString $env:GAS_SIGNING_PASSWORD -AsPlainText -Force)
        [System.IO.File]::WriteAllText($passwordPath, $encrypted)
    } finally { $env:GAS_SIGNING_PASSWORD = $null }
}
$protectedPassword = ConvertTo-SecureString ([System.IO.File]::ReadAllText($passwordPath))
$credential = New-Object System.Net.NetworkCredential('', $protectedPassword)
$env:GAS_SIGNING_STORE = $storePath
$env:GAS_SIGNING_PASSWORD = $credential.Password
try {
    Push-Location $projectRoot
    # A fresh process prevents a long-lived Gradle daemon from retaining stale signing environment values.
    & ./gradlew.bat :app:bundleRelease --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    $artifactDir = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    $bundleName = 'gas-self-meter-ai-0.1.1.aab'
    $bundle = Join-Path $artifactDir $bundleName
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/outputs/bundle/release/app-release.aab') -Destination $bundle
    $digest = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $artifactDir 'SHA256SUMS.txt'), "$digest  $bundleName`n")
    Write-Output "Signed AAB saved to $bundle"
    Write-Output "Signing key retained at $storePath"
} finally {
    Pop-Location
    $env:GAS_SIGNING_STORE = $null
    $env:GAS_SIGNING_PASSWORD = $null
    $credential = $null
}
