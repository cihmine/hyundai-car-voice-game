# AI 음성 차량 게임 (가칭)

혼자 차량을 이용할 때 AI와 음성으로 즐기는 언어 게임 앱입니다. Pleos Connect용으로 개발합니다.
끝말잇기 · 초성 대결 · 스무고개 · 양세찬 게임을 제공합니다.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작 · 개발 중

## 구조

| 모듈 | 내용 |
|---|---|
| `core` | 순수 Kotlin 규칙 엔진. 단어 판정(두음법칙·표기 기준 초성), 제한시간·힌트, 질문 횟수·연장, 포인트(중복 지급 방지), 음성 명령 해석, 저장·복구 |
| `app` | Android 앱. 음성 입출력·차량 상태·AI 연결은 `core/port` 인터페이스로 분리해 교체할 수 있음 |

점수·타이머·횟수·중복 판정은 AI가 아니라 규칙 엔진이 결정합니다.

## 문서

| 파일 | 내용 |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | 개발 계획서 — 구현 범위(P0/P1), 게임 규칙, 상태 모델, 검증 사례 |
| [`docs/COMMON.md`](docs/COMMON.md) | 세 앱 공통 개발·빌드·검증 원칙, 일정, release 절차 |
| [`docs/SUBMISSION.md`](docs/SUBMISSION.md) | 1차 제출·가산점 준비 체크리스트 |
| [`docs/SDK_VERIFICATION.md`](docs/SDK_VERIFICATION.md) | Pleos 에뮬레이터에서 확인한 SDK·음성 기능 결과 |

## 실행

요구 사항: Android Studio(JDK 17 이상), Android SDK 36. Pleos SDK 는 `settings.gradle.kts` 의 Pleos Maven 저장소에서 받는다.

```bash
./gradlew :core:test          # 규칙 엔진·사전 데이터 테스트
./gradlew :app:assembleDebug  # 디버그 APK
./gradlew :app:assembleRelease # release APK(서명 설정이 있을 때만 서명됨)
```

release 서명 키는 저장소에 넣지 않는다. 기본 위치 `~/.android-keys/carvoicegame.properties`(storeFile·storePassword·keyAlias·keyPassword), 다른 곳이면 `-Pcarvoicegame.signing=경로`.

테스트 환경: Pleos Connect v2.1.0 에뮬레이터(API 34, Automotive, 2560×1440). 일반 안드로이드 기기에서도 실행된다.

### 음성

- Pleos 음성 서비스가 있는 기기(차량·Pleos 에뮬레이터): Pleos TTS·STT SDK(기기 내 처리)
- 그 밖의 기기: 안드로이드 기본 음성 인식·TTS
- 음성 인식이 없으면 시연 패널의 글자 입력으로 같은 흐름을 진행한다(화면에 "글자 입력 대체 시연" 표시).

### 실제 기능과 모의 기능

| 구분 | 내용 |
|---|---|
| 실제 | 끝말잇기·초성 대결 규칙 엔진, 음성 안내·인식(Pleos SDK), 제한시간·힌트, 잘못 들었어 정정, 저장·이어하기, 기록·포인트(중복 지급 방지) |
| 모의 | 차량 주차·주행 상태(시연 패널), 내비 안내 끼어들기, 주유·충전 쿠폰(데모 — 사용 불가) |
| 준비 중 | 스무고개·양세찬 게임, 외부 AI·사전 검색 |

기록·포인트는 기기 안에만 저장되며 앱을 지우거나 데이터를 초기화하면 사라진다. 단어 목록은 자체 작성한 일반 명사 699개다(표준 사전보다 작아 실제 단어도 "단어장에 없다"고 할 수 있다).

## APK

릴리스 APK는 `apk/` 폴더에 둘 예정입니다.
