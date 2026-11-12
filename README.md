# 모두의 DJ (DJ for All)

## 1. 앱 이름 및 한 줄 소개

**모두의 DJ (DJ for All)** — 차에 탄 사람마다 한 곡씩 골라, 공평한 순서로 차량 스피커에서 함께 듣는 Android Automotive 앱

참여할 좌석을 정하면 좌석마다 QR 코드가 생깁니다. 동승자는 차량 화면이나 자기 휴대폰으로 자기 차례에 한 곡을 고릅니다. 모두 고르면 곡들을 재생 목록 하나로 묶어 차량 오디오로 재생합니다. 다음 라운드에서는 다음 좌석이 먼저 고릅니다.

| 항목 | 내용 |
| --- | --- |
| 앱 버전 | 0.2.0 (versionCode 2) |
| 패키지 | `io.github.myshihi.pleosproject26.e10seatdjparty` |
| 시연 영상 | [demo-video/모두의DJ-시연영상.mp4](demo-video/모두의DJ-시연영상.mp4) (약 70초, 자막 `.srt` 포함) |

### 폴더 구성

| 위치 | 내용 |
| --- | --- |
| `apk/` | 바로 설치할 수 있는 release APK |
| `project/` | 앱 소스 전체 (Android Studio 프로젝트) |
| `demo-video/` | 시연 영상과 자막 |

## 2. 실행 방법

### 2.1 APK로 실행 (권장)

1. Pleos Connect 에뮬레이터를 켭니다. 준비 방법은 [3. 테스트 환경 및 필요한 설정](#3-테스트-환경-및-필요한-설정)에 있습니다.
2. 저장소 최상위 폴더에서 APK를 설치하고 실행합니다.

```bash
adb install -r apk/설계3.5팀_DJforAll_release.apk
adb shell am start -n io.github.myshihi.pleosproject26.e10seatdjparty/.MainActivity
```

에뮬레이터 앱 목록에서 **모두의 DJ**를 눌러 실행해도 됩니다.

같은 패키지가 다른 서명(디버그 빌드 등)으로 이미 설치돼 있으면 설치가 거부됩니다. 이때는 먼저 지운 뒤 다시 설치합니다.

```bash
adb uninstall io.github.myshihi.pleosproject26.e10seatdjparty
```

### 2.2 소스에서 빌드해 실행

1. Android Studio에서 **`project` 폴더**를 엽니다(Open).
2. Gradle JDK는 Android Studio에 들어 있는 JBR(JDK 17 이상)을 씁니다.
3. 실행 기기로 Pleos Connect 에뮬레이터를 고르고 `app`을 실행(Run)합니다.

명령줄로 빌드하려면 다음과 같이 합니다.

```bash
cd project
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

서명 키는 저장소에 넣지 않았습니다. 그래서 소스에서 `assembleRelease`로 빌드하면 서명되지 않은 APK가 나옵니다. 소스 빌드는 debug로 실행하고, release 버전은 `apk/` 폴더의 APK로 확인해 주세요.

### 2.3 사용 순서

1. 기어 **P**, 속도 **0**인 상태에서 시작합니다.
2. **01 참여 좌석**에서 함께할 좌석을 고르고 **참여 좌석 확정**을 누릅니다.
3. **02 한 곡씩 선택**에서 좌석마다 한 곡을 고르고 **이 좌석 선곡 확정**을 누릅니다. 좌석별 QR 코드를 찍어 휴대폰에서 골라도 됩니다.
4. 모든 좌석이 고르면 **공동 큐 만들기**를 누른 뒤 **재생**을 누릅니다.
5. **다음 라운드 선곡**을 누르면 다음 곡들을 이어서 추가합니다. 라운드마다 다음 좌석이 먼저 고릅니다.

## 3. 테스트 환경 및 필요한 설정

### 3.1 빌드 환경

| 항목 | 내용 |
| --- | --- |
| 언어 | Kotlin |
| 빌드 도구 | Android Gradle Plugin 9.4.1, Gradle 9.6.0 |
| SDK | compileSdk 37, minSdk 30, targetSdk 37 |
| 차량 SDK | Pleos Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle:2.3.2`) |

### 3.2 에뮬레이터 준비

1. Android Studio → SDK Manager → SDK Update Sites에 Pleos Connect 시스템 이미지 주소를 추가합니다.
   - Windows / Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - macOS (Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. SDK Platforms에서 **Show Package Details**를 켜고, API 34의 **Pleos Connect 2.1.0** 이미지를 설치합니다.
3. Device Manager → Create Device → Automotive → **Pleos Connect 2.1.0**으로 기기를 만들고 실행합니다.
4. 소스에서 빌드하려면 Android SDK Platform 37도 설치해야 합니다. Vehicle SDK는 빌드할 때 Pleos 공개 Maven 저장소에서 자동으로 받으므로 인터넷 연결이 필요합니다.

### 3.3 좌석 신호 바꾸기 (선택)

에뮬레이터의 Virtual Console(<http://127.0.0.1:8088/>)에서 API Browser → Seat → Seat Occupancy로 들어가면 좌석별 착석 여부를 바꿀 수 있습니다. 신호가 바뀌면 앱이 참여 좌석을 다시 확인해 달라고 요청합니다.

에뮬레이터 신호 없이 보려면 앱 오른쪽 위 **연결 설정 → 샘플 체험으로 전환**을 누릅니다. 승하차, 신호 없음, 출발 같은 상황을 직접 만들어 볼 수 있습니다.

### 3.4 PC에서 휴대폰 QR 선곡 화면 보기 (선택)

에뮬레이터 안의 주소는 실제 휴대폰에서 열 수 없습니다. 대신 PC에서 아래 명령을 실행한 뒤, 앱의 연결 설정에 나오는 `http://127.0.0.1:8765/s/...` 주소를 PC 브라우저로 열면 됩니다.

```bash
adb forward tcp:8765 tcp:8765
```

### 3.5 차량 음악 앱 연결 (선택)

**연결 설정 → 음악 앱**에서 차량에 설치된 음악 앱을 고를 수 있습니다. 기본값은 앱에 들어 있는 음원 6곡이며, 따로 설정하지 않아도 바로 재생됩니다. 멜론, 지니 같은 외부 음악 앱과의 실제 연결은 아직 확인하지 않았습니다.

### 3.6 확인한 범위

- Pleos Connect 2.1.0 에뮬레이터에 release APK를 설치해 실행했습니다. 좌석 신호 받기, 참여 좌석 확정, QR 선곡 화면이 정상 동작하는 것을 확인했습니다. (2026-11-12)
- 실제 차량, 실제 휴대폰으로 QR 선곡하기, 멜론·지니 연결은 확인하지 않았습니다.

## 4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34, x86_64) |
| 화면 규격 | 2560 × 1440 px, 160 dpi, 가로 화면 |

## 5. APK 파일 위치

**[apk/설계3.5팀_DJforAll_release.apk](apk/설계3.5팀_DJforAll_release.apk)**

- 디버그 모드를 끈 release 빌드입니다.
- APK Signature Scheme v2로 서명했고, `apksigner verify`로 서명을 확인했습니다.
- 음원 6곡이 APK 안에 들어 있어 로그인이나 인터넷 연결 없이 실행됩니다.
