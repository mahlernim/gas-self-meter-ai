# Build and release

The first release uses application ID `dev.mahlernim.gasselfmeter`, version name `0.1.0`, and version code `1`. Supported devices run Android 8.0 or later. The build targets Android 16 / API 36. The APK includes all packaged AndroidX native ABIs rather than assuming the user's device architecture.

The project uses AGP 9.2.0, its built-in Kotlin support, Kotlin Compose compiler 2.3.10, Gradle 9.4.1, and JDK 21. Dependency versions are explicit in the Gradle build file.

## Local build

Set `JAVA_HOME` and `ANDROID_HOME` or an ignored `local.properties` file. Install SDK platform 36 and build tools 36.0.0. On Windows, escape the drive separator in `local.properties`, for example `sdk.dir=C\:/Android/Sdk`.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew.bat :app:connectedDebugAndroidTest
./scripts/build-release.ps1
```

The signing script creates an app-specific RSA key only when neither signing file exists. It preserves that key for future upgrades. It keeps the keystore outside the repository under `%LOCALAPPDATA%/GasSelfMeterAI/signing/`, together with a Windows DPAPI-protected password. DPAPI protection is tied to the Windows account. Back up the signing key and a privately recoverable password before moving to another machine. Never publish either signing secret.

The script passes passwords through environment variables. They do not appear as command-line arguments. It creates `artifacts/gas-self-meter-ai-0.1.0.apk` and `artifacts/SHA256SUMS.txt`. Increment both Android version values and the artifact name for future releases.

For another build host, provide `GAS_SIGNING_STORE` and `GAS_SIGNING_PASSWORD`, and use a keystore alias of `gas-self-meter-ai`. Debug signing is not used for releases.

## Publication checks

1. Unit tests and Android lint pass on the release source revision.
2. The onboarding, calibration, history and persistence workflow passes on Android.
3. Release build succeeds with the retained signing key.
4. `apksigner verify --verbose --print-certs` and `zipalign -c -P 16 4` pass for the final APK.
5. A clean install of the release APK opens correctly, with no test account or demo data preloaded.
6. GitHub Actions verifies the published source revision.
7. The release APK and SHA256 file are uploaded, downloaded again and matched to the local artifact.

GitHub Actions builds a debug artifact and checks the code. The published user APK is signed by the app-specific release key on the release host. Signing secrets are not present in the repository or CI logs.

## Authorized live testing

Normal CI and instrumentation tests use only synthetic data. The research Python probe prompts for authorized credentials using hidden input. No credential is committed, passed as a process argument, or written to its output. Live endpoint access is limited to login and reading account data. No meter submission endpoints are implemented in the Android app.
