# Google Play 배포 절차

대상은 `mahlerlabdiy`가 소유한 `dev.mahlernim.gasselfmeter`의 기존 Closed Alpha다. API 트랙 식별자는 아래 예시에서 `alpha`이며, 실제 inspect 결과와 Console에서 같은 트랙임을 확인해야 한다. 개발자 표시 이름만으로 인증이 설정되거나 계정 소유권이 검증되는 것은 아니다. production, 테스터, 국가, 계정 권한을 변경하지 않는다.

## 0.6.0 준비 상태

소스 버전은 0.6.0, versionCode 15다. 코드의 버전 증가는 Play 예약이나 업로드가 아니다. Play의 모든 트랙에서 15가 이미 사용됐는지 먼저 확인하고, 충돌하면 배포하지 않는다. PR #84, #85, #86이 병합된 동일 소스에서 빌드한다.

CI의 `unsigned-release-candidate`는 난독화·리소스 축소를 적용한 **서명되지 않은** 빌드 검사 산출물이다. 설치용 APK, Play 업로드용으로 검증된 AAB, 심사 제출 완료 증거가 아니다. `unsigned-build.json`에 소스 revision과 SHA-256을 함께 기록한다.

## 기존 자격 증명과 도구

기존 `scripts/build-release.ps1`는 원래 Windows 서명 폴더의 `release.jks`와 `password.dpapi`를 요구한다. DPAPI는 원래 Windows 사용자 환경에서 해제한다. 새 업로드 키를 생성하거나 임의의 디버그 키로 대체하지 않는다. 기존 업로드 인증서 SHA-256은 스크립트에 고정되어 있다.

Play API는 해당 앱의 테스트 배포 권한이 있는 기존 Google Application Default Credentials를 사용한다. Gmail/Drive 로그인이나 개발자 계정 이름만으로 이 권한을 대신할 수 없다. 자격 증명을 Git, issue, 로그, artifact에 넣지 않는다.

`mahlernim/google-store-publisher`의 `playstore-publisher` 스킬과 CLI를 사용한다. 검토한 기준 커밋은 `165647986b5343954e1b1cf718ba0ffed0a84560`이다. 신뢰할 수 있는 별도 체크아웃에서 Node 22 이상으로 `corepack pnpm install --frozen-lockfile`, `corepack pnpm build`를 실행한다. 스킬을 Codex에 설치할 때에는 해당 저장소의 `skills/playstore-publisher` 디렉터리를 사용한다. 스킬 설치는 Google 인증 설정이 아니다.

Java, keytool, jarsigner와 별도로 검증한 고정 버전의 bundletool이 필요하다. publisher의 setup/recovery 문서도 읽는다.

## 신규 릴리스 순서

아래 PowerShell 예시의 경로는 실제 기존 경로로 지정한다. 각 명령이 실패하면 다음 단계로 진행하지 않는다. 기존 journal이 있다면 신규 순서를 재시작하지 말고 마지막 확인된 단계에서 복구한다.

```powershell
$publisher = 'C:\tools\google-store-publisher\packages\cli\dist\index.js'
$bundletool = 'C:\tools\bundletool.jar'
$state = 'C:\private\gas-play-state'
node $publisher --help
node $publisher play status --package dev.mahlernim.gasselfmeter --track alpha
```

진행 중인 journal/edit가 없을 때만 임시 edit를 사용하는 inspect를 실행한다. Console 동시 작업을 멈추고, 실제 트랙·테스터 그룹·국가·심사 상태를 검토한다. inspect 출력의 configuration SHA-256을 임의로 대체하거나 자동 수락하지 않는다.

```powershell
node $publisher play inspect --package dev.mahlernim.gasselfmeter --track alpha --execute
.\scripts\build-release.ps1 -SigningDirectory 'C:\private\original-gas-signing'
node .\scripts\prepare-play-release.mjs --configuration-sha256 '<검토한 소문자 64자리 SHA-256>'
$manifest = (Resolve-Path .\artifacts\play-release.json).Path
node $publisher play plan --manifest $manifest --bundletool $bundletool
node $publisher play upload --manifest $manifest --bundletool $bundletool --state-dir $state --execute
node $publisher play validate --manifest $manifest --state-dir $state --execute
node $publisher play submit --manifest $manifest --state-dir $state --execute
node $publisher play status --package dev.mahlernim.gasselfmeter --track alpha --version-code 15
```

prepare 스크립트는 기존 빌드 검증 JSON의 패키지·버전·소스·AAB 해시·업로드 인증서를 대조하고 새 manifest를 만든다. manifest를 덮어쓰지 않으며 네트워크 요청도 하지 않는다. 실제 JAR 서명과 내장 버전 검증은 publisher `plan`에서 다시 수행한다. 모든 mutation에 같은 manifest와 절대 state 디렉터리를 유지한다.

`upload`는 번들 업로드, `validate`는 배포안 검증, `submit`만 심사 제출 commit이다. publisher는 진행 중인 심사를 취소하지 않도록 `ERROR_IF_IN_REVIEW`를 사용한다. 결과가 불확실한 업로드·commit을 자동 재시도하지 말고 journal과 공식 상태를 대조한다. submit 성공도 심사 승인이나 테스터 설치 가능을 뜻하지 않는다.

이번 작업 환경에는 원래 Windows 서명 파일과 Play ADC가 없어서 실제 Play 상태·업로드·심사 제출은 확인하지 못했다. 후속 실행 결과는 이 문서의 준비 상태와 구분해 PR/issue에 기록한다.

## 로컬 회귀 검사

```powershell
node --test scripts/prepare-play-release.test.mjs
```

공식 API 근거: https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit
