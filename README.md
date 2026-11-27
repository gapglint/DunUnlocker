# DunUnlocker (gapglint Fork)
삼성 갤럭시 기기를 비롯한 안드로이드 스마트폰에서 APN 타입에 `dun`을 추가하고, 비루팅 환경에서 테더링 제한 우회를 돕는 앱입니다.

본 저장소는 [naijun0403/DunUnlocker](https://github.com/naijun0403/DunUnlocker)의 포크(GPL-3.0-only)이며, 원본 앱과 별도로 설치할 수 있도록 패키지명(`com.gapglint.dununlocker`)과 커스텀 아이콘을 분리했습니다.

## 원본 대비 추가 기능
- **Android 13 (API 33) 지원**: `com.android.shell`에 `WRITE_APN_SETTINGS` 권한이 없는 기기에서 Shizuku(`MODIFY_PHONE_STATE`)로 `ICarrierConfigLoader.overrideConfig`를 통해 임시 Carrier Privileges를 획득하여 APN 조회/DUN 추가/복사/생성을 수행하고 즉시 원복합니다.
- **내장 테더링 프록시 & 원클릭 셋업 포털 (비루팅, Shizuku 불필요)**:
  - 기본 포트 `18889`(변경 가능)에서 HTTP/HTTPS `CONNECT`, SOCKS5, 자동 구성(`proxy.pac`), OS별 원클릭 설정 스크립트(`.bat`/`.sh`)를 제공합니다.
  - 기기의 핫스팟/USB 테더링/로컬 IP를 카드 상단에 자동 표시합니다.
- **표준 Android SDK 빌드 지원**: `:hidden-api-stub` `compileOnly` 모듈을 포함해 별도 수정 `android.jar` 없이 바로 빌드할 수 있습니다.
- **GitHub Actions 릴리즈 자동화**: `v*` 태그 푸시 또는 수동 실행(`workflow_dispatch`) 시 단위 테스트 후 Release APK를 빌드·배포합니다.

## 요구 사항
- 안드로이드 13 (API 33) 이상 기기
- APN `dun` 추가 기능: Shizuku (또는 ADB `am instrument`)
- 내장 테더링 프록시 & 셋업 포털: 별도 권한/루팅/Shizuku 불필요

## 주의 사항
안드로이드 시스템 특성상 같은 데이터로 이름만 다른 APN을 추가하면 추가되는 것이 아닌 이름이 수정됩니다.
이 점 참고해주세요!

**USE AT YOUR OWN RISK!**

## 빌드 및 릴리즈 서명 방법
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleRelease
```

서명된 Release APK를 빌드하려면 `local.properties`(로컬) 또는 GitHub Repository Secrets(Actions)에 키스토어 정보를 설정합니다:
- GitHub Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- 로컬 `local.properties`: `signing.keystorePath`, `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword`

## 라이선스
GPL-3.0-only

## Credits
- [naijun0403/DunUnlocker](https://github.com/naijun0403/DunUnlocker) - 원본 프로젝트
- [vvb2060](https://github.com/vvb2060) - 우회 방안에 대한 기본적인 아이디어
- [pixel-volte-patch](https://github.com/kyujin-cho/pixel-volte-patch) - 우회 방안에 대한 기본적인 아이디어
