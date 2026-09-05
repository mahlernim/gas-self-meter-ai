param([Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$SigningDirectory)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation first.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$storePath = Join-Path $SigningDirectory 'release.jks'
$passwordPath = Join-Path $SigningDirectory 'password.dpapi'
if (-not (Test-Path -LiteralPath $storePath -PathType Leaf) -or -not (Test-Path -LiteralPath $passwordPath -PathType Leaf)) {
    throw 'Original upload signing files are required. This update tool never creates a key.'
}
$expectedSha256 = 'DC:38:8F:E5:7D:83:74:88:17:01:F8:49:00:8A:9B:1B:34:2D:5F:FC:2D:DE:AB:1B:FF:C0:35:47:83:39:02:E9'
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
function Assert-UploadCertificate([string[]]$CertificateOutput) {
    $text = $CertificateOutput -join "`n"
    if (-not $text.Contains($expectedSha256)) { throw 'Upload certificate mismatch. Stop and restore the original signing directory.' }
}
$protectedPassword = ConvertTo-SecureString ([System.IO.File]::ReadAllText($passwordPath))
$credential = New-Object System.Net.NetworkCredential('', $protectedPassword)
$env:GAS_SIGNING_STORE = $storePath
$env:GAS_SIGNING_PASSWORD = $credential.Password
try {
    $certificate = & $keytool '-J-Duser.language=en' '-J-Duser.country=US' -list -v -keystore $storePath -alias gas-self-meter-ai -storepass:env GAS_SIGNING_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the original upload certificate.' }
    Assert-UploadCertificate $certificate
    Write-Output "Verified upload certificate SHA-256 $expectedSha256"
    Push-Location $projectRoot
    try {
        $revision = (& git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot identify release source revision.' }
        $dirty = & git status --porcelain --untracked-files=no
        if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Commit tracked release changes before building.' }
        $gradle = [System.IO.File]::ReadAllText((Join-Path $projectRoot 'app/build.gradle.kts'))
        $versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([0-9.]+)"').Groups[1].Value
        $versionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
        if (-not $versionName -or -not $versionCode) { throw 'Cannot identify release version.' }
        # A fresh process prevents a daemon from retaining stale signing environment values.
        & ./gradlew.bat :app:bundleRelease --console=plain --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
        $built = Join-Path $projectRoot 'app/build/outputs/bundle/release/app-release.aab'
        & (Join-Path $env:JAVA_HOME 'bin/jarsigner.exe') '-J-Duser.language=en' -verify $built
        if ($LASTEXITCODE -ne 0) { throw 'AAB signature verification failed.' }
        $bundleCertificate = & $keytool '-J-Duser.language=en' '-J-Duser.country=US' -printcert -jarfile $built 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the AAB signing certificate.' }
        Assert-UploadCertificate $bundleCertificate
        $artifactDir = Join-Path $projectRoot 'artifacts'
        New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
        $bundleName = "gas-self-meter-ai-$versionName.aab"
        $bundle = Join-Path $artifactDir $bundleName
        Copy-Item -LiteralPath $built -Destination $bundle
        $digest = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
        [System.IO.File]::WriteAllText((Join-Path $artifactDir 'SHA256SUMS.txt'), "$digest  $bundleName`n")
        $metadata = [ordered]@{ package = 'dev.mahlernim.gasselfmeter'; versionName = $versionName; versionCode = [int]$versionCode; revision = $revision; sha256 = $digest; uploadCertificateSha256 = $expectedSha256 }
        [System.IO.File]::WriteAllText((Join-Path $artifactDir 'release-verification.json'), ($metadata | ConvertTo-Json))
        Write-Output "Verified AAB saved to $bundle"
        Write-Output "Source $revision, version $versionName ($versionCode), SHA-256 $digest"
    } finally { Pop-Location }
} finally {
    $env:GAS_SIGNING_STORE = $null
    $env:GAS_SIGNING_PASSWORD = $null
    $credential = $null
    $protectedPassword = $null
}
