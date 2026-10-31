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

요구 사항: Android Studio(JDK 17 이상), Android SDK 36

```bash
./gradlew :core:test          # 규칙 엔진 단위 테스트
./gradlew :app:assembleDebug  # 디버그 APK
```

테스트 환경: Pleos Connect v2.1.0 에뮬레이터(API 34, Automotive)

## APK

릴리스 APK는 `apk/` 폴더에 둘 예정입니다.
