# Google Play Closed Alpha 릴리스

앱 ID, `versionName`, `versionCode`, 최소 및 대상 API는 릴리스할 소스의 `app/build.gradle.kts`에서 확인합니다. Play에 업로드하는 AAB는 GitHub Release나 다른 공개 다운로드 위치에 올리지 않습니다.

## 빌드와 서명

JDK와 Android SDK를 준비한 뒤, 릴리스 소스에서 다음 검사를 실행합니다.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew.bat :app:connectedDebugAndroidTest
```

기존 Play 앱 업데이트에는 이미 등록된 업로드 키와 동일한 인증서가 필요합니다. 서명 파일이 없을 때 새 키를 생성할 수 있는 이전 `scripts/build-release.ps1`의 기본 실행은 기존 앱 업데이트에 사용하지 마세요. 검증된 기존 업로드 키가 있는 디렉터리를 명시해, 인증서 일치를 확인하는 릴리스 절차에서만 사용합니다.

```powershell
./scripts/build-release.ps1 -SigningDirectory '<verified-signing-directory>'
```

키 파일과 비밀번호는 저장소 밖의 승인된 보호 저장소에서만 다룹니다. 명령줄, 로그, 문서와 CI 출력에 비밀번호를 남기지 않습니다. debug 키는 Play 업로드에 사용할 수 없습니다.

번들을 만든 뒤 다음을 확인합니다.

1. `jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab`가 통과합니다.
2. `keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab`의 업로드 인증서가 기존 Play 앱의 업로드 인증서와 일치합니다.
3. 최종 AAB의 SHA-256, 패키지, `versionName`, `versionCode`, 소스 revision을 기록하고 그 파일만 업로드합니다.

`jarsigner` 검사는 파일의 서명 무결성을 확인하지만 업로드 키의 연속성을 대신 확인하지는 않습니다.

## Closed Alpha 게시

1. Google Play Console에서 `dev.mahlernim.gasselfmeter` 앱의 Closed Alpha 트랙을 엽니다.
2. 검증한 AAB를 새 릴리스에 추가하고 이용자에게 필요한 변경 사항을 작성합니다.
3. 지정한 테스터 그룹을 선택하고 검토 후 rollout을 시작합니다.
4. Play가 선택한 테스터에게 이용 가능하다고 표시한 뒤, 그룹 구성원 계정으로 opt-in 경로를 확인합니다.

그룹 구성원은 Play opt-in 절차도 완료해야 테스트 릴리스를 설치할 수 있습니다.
