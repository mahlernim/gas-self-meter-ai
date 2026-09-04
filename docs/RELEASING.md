# Build and Closed Alpha release

The next test release uses application ID `dev.mahlernim.gasselfmeter`, version name `0.3.0`, and version code `7`. Supported devices run Android 8.0 or later. The build targets Android 16 / API 36. Distribution is limited to the Google Play Closed Alpha track. Do not attach an APK or AAB to a GitHub release.

The project uses AGP 9.2.0, its built-in Kotlin support, Kotlin Compose compiler 2.3.10, Gradle 9.4.1, and JDK 21. Dependency versions are explicit in the Gradle build file.

## Local build

Set `JAVA_HOME` and `ANDROID_HOME` or an ignored `local.properties` file. Install SDK platform 36 and build tools 36.0.0. On Windows, escape the drive separator in `local.properties`, for example `sdk.dir=C\:/Android/Sdk`.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew.bat :app:connectedDebugAndroidTest
./scripts/build-release.ps1
```

The release build reads `GAS_SIGNING_STORE` and `GAS_SIGNING_PASSWORD`. Use the `gas-self-meter-ai` keystore alias. Keep the keystore and password outside the repository, back them up privately and never publish either signing secret. The signing key must remain stable so Google Play can accept future updates.

The signing script passes the password through environment variables so it does not appear as a command-line argument. It generates `artifacts/gas-self-meter-ai-0.3.0.aab`. Upload this AAB only to the app's Closed Alpha track in Google Play Console. Do not distribute it from GitHub or another public download page.

Before every upload, increment `versionCode`. Update `versionName` when the tester-visible release version should change. Debug signing is not used for Play uploads.

## Publication checks

1. Unit tests and Android lint pass on the release source revision.
2. The onboarding, calibration, history and persistence workflow passes on Android.
3. The release AAB builds successfully with the retained signing key.
4. `jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab` passes.
5. A Play-installed Closed Alpha build opens correctly, with no test account or demo data preloaded.
6. GitHub Actions verifies the published source revision.
7. The exact tested AAB is uploaded to the Closed Alpha track.
8. The release is available to the intended tester group and the Play opt-in link works with a member account.

GitHub Actions runs unit tests, Android lint and a debug build, then retains only the test and lint reports. It does not upload a downloadable APK. The Closed Alpha AAB is signed on the release host, and signing secrets are not present in the repository or CI logs.

## Closed Alpha publication

1. Open Google Play Console with the `mahlerlabdiy` developer account and select the app with package ID `dev.mahlernim.gasselfmeter`.
2. Open the Closed Alpha testing track and create a new release.
3. Upload `app-release.aab` and add concise tester-facing release notes.
4. Confirm that the Google Group `gas-self-meter-ai@googlegroups.com` is assigned to the track.
5. Review the release, start the rollout to Closed Alpha and wait until Play reports it as available to selected testers.
6. Verify the [tester group](https://groups.google.com/g/gas-self-meter-ai) and [Play opt-in page](https://play.google.com/apps/testing/dev.mahlernim.gasselfmeter) with the same Google account.

Group membership alone does not opt a user into the test. Each tester must open the Play opt-in page and complete registration with the Google account that joined the group.

## Authorized live testing

Normal CI and instrumentation tests use only synthetic data. The research Python probe prompts for authorized credentials using hidden input. No credential is committed, passed as a process argument, or written to its output. Before treating submission as production-verified, use an authorized account during its actual reading window to verify one exact value. Confirm the contract, meter, period and value immediately before the single request. Never exercise automatic submission for this first live check, and never retry an uncertain response without reconciling the provider state. Closed Alpha testers must be told that submission remains unverified and is off by default.
