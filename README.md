# 말게임 (가칭)

주차·충전 중에 화면을 보지 않고 AI와 **말로** 끝말잇기·초성 대결을 하는 Pleos Connect 앱입니다.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작 · 시제품

## 30초 체험

**마이크 없이 (가장 빠름)**

1. 앱을 켜면 AI가 "어떤 게임을 시작할까요?"라고 묻습니다. 오른쪽 위 **터치 화면 ▸** 을 누릅니다.
2. **시작** 을 누르면 AI가 첫 단어를 말합니다(규칙 질문이 나오면 **아니요**).
3. 오른쪽 시연 패널의 **글자 입력** 칸에 이을 단어를 적고 **말한 것으로 보내기** 를 누릅니다. 음성으로 말한 것과 같은 길로 판정됩니다.
4. 틀린 글자로 시작하는 말이나 없는 말을 보내 보면 AI가 이유를 말해 줍니다. **힌트**·**뜻**·**잘못 들었어** 도 눌러 보세요.

**마이크로** — Pleos 에뮬레이터는 기본 설정에서 마이크 소리가 0이므로 `-allow-host-audio` 를 붙여 켭니다.

```
emulator -avd <Pleos AVD 이름> -no-snapshot-load -allow-host-audio
```

"끝말잇기 어렵게"라고 말하면 바로 시작합니다. 게임 중에는 "힌트", "모르겠어", "뭐라고 했더라", "잘못 들었어", "좀 더 빠르게 말해 줘", "내가 졌어", "그만하자"가 통합니다. 검은 화면의 빛나는 원이 AI 목소리에 반응하고, 내 말은 인식되는 대로 회색 자막으로 나옵니다.

## 코드 지도

| 보고 싶은 것 | 파일 |
|---|---|
| 끝말잇기·초성 규칙과 판정 | `core/.../word/WordGame.kt` |
| 난이도별 AI 단어 선택 | `core/.../word/WordPicker.kt` |
| 단어장·판정 전용 목록 | `core/.../word/WordDictionary.kt` |
| 두음법칙·초성 분해 | `core/.../hangul/Hangul.kt` |
| 음성 명령 해석 | `core/.../command/CommandParser.kt`, `MenuParser.kt` |
| 브랜드 단어 유도 | `core/.../ad/AdPlanner.kt` |
| 음성 흐름 전체(듣기·말하기·판 진행) | `app/.../game/GameController.kt` |
| 음성 화면(원·자막) | `app/.../ui/VoiceStage.kt` |
| Pleos 음성 SDK 연결 | `app/.../speech/PleosSpeechInput.kt`, `PleosSpeechOutput.kt` |

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
| [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) | 기능별 구현 상태와 한계 |
| [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) | 시험 결과 |

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
| 실제 | 사용자 화면(검은 화면 · AI 가 말할 때 빛나는 흰 원 · AI 말은 흰 자막, 사용자 말은 인식되는 대로 회색 자막 — Pleos STT 중간 결과·소리 크기 사용), 앱을 켜면 "어떤 게임을 시작할까요?"로 음성 시작(처음 화면은 마이크를 계속 켜 둠, "초성 대결 어렵게"처럼 한 번에), 판이 끝나면 "다음은 어떤 게임을 할까요?"(예/아니요로 되묻지 않음), 끝말잇기·초성 대결 규칙 엔진, 음성 안내·인식(Pleos SDK), 제한시간·힌트, 잘못 들었어 정정, 저장·이어하기, 기록·포인트(중복 지급 방지) |
| 모의 | 차량 주차·주행 상태(시연 패널), 내비 안내 끼어들기, 주유·충전 쿠폰(데모 — 사용 불가) |
| 준비 중 | 스무고개·양세찬 게임(규칙 엔진만 있고 화면은 잠겨 있음), 외부 AI, 인터넷 단어 검색(제출 APK 에서는 꺼져 있음) |

### 난이도 = AI 실력

AI 가 내는 단어는 어느 난이도든 직접 고른 일상 단어가 먼저다. 난이도는 단어가 아니라 AI 의 실력으로 나눈다.

| | 쉬움 | 보통 | 어려움 |
|---|---|---|---|
| AI 가 찾는 범위 | 일상 단어(앱 사전의 익숙한 단어)만 — 떨어지면 AI 가 진다 | 앱 사전 전체 | 앱 사전 전체(서버 연결 시 인터넷 검색 추가) |
| 끝 글자 전략 | 사용자가 이을 단어가 많은 글자를 준다 | 무작위 | 이을 단어가 적은 글자로 압박한다 |
| 한방 단어 | 안 씀 | 안 씀 | 서버 연결 시에만: 사용자 답 5개 이후, 익숙한 단어로, 인터넷으로 정말 이을 단어가 없다고 확인한 것만(제출 APK 에서는 안 씀) |

주행 중에는 제한시간이 없으므로, 한방 단어 뒤 30초 동안 답이 없거나 힌트를 청하면 "이을 단어가 없는 것 같아요. 제가 이긴 걸로 할까요?"라고 묻는다.

### 단어 판정 — 서버 없이 동작

제출 APK 는 인터넷 없이 동작한다. 단어는 두 층이다.

- **AI 가 말하는 단어장**(약 3만 개, 뜻풀이 포함): 힌트·뜻 설명에도 쓴다.
- **판정 전용 목록**(257,596개, 표제어만): 사용자 답을 인정할 때만 쓴다. AI 는 이 단어를 말하지 않으므로 목록이 커도 AI 실력은 그대로다.

### 인터넷 단어 검색(후속 연동, 제출 APK 에서는 꺼짐)

`server/` 는 우리말샘 키를 앱에 넣지 않기 위한 중계 함수다. `-Pcarvoicegame.dictProxy=주소` 로 빌드하면 두 목록에 없는 말을 서버로 확인하고(최대 3초, 그동안 시계 멈춤), 어려움의 한방 단어가 켜진다. PC 로컬 서버로 동작을 시험했고 공개 서버에는 배포하지 않았다.

### 브랜드 단어 허용 모드(데모)

끝말잇기에서 "브랜드 단어 허용(데모)"을 고르면, 판을 시작할 때 고정한 데모 브랜드 단어(`core/src/main/resources/data/demo_campaigns.json`)를 사람과 AI 모두 쓸 수 있다. AI 는 사용자 유효 답변 4회 이후, 한 판 최대 1회, 이전 노출 후 10분이 지났을 때만 브랜드 단어를 **다른 단어와 똑같이 끝말잇기 흐름 안에서** 말한다(따로 안내하지 않음). 사용자가 "그게 뭐야?", "그런 단어가 있어?"라고 물으면 승인된 짧은 소개만 읽고 같은 차례·같은 남은 시간으로 돌아가며, "광고 꺼 줘"라고 하면 바로 멈춘다. 광고 여부는 판정·포인트에 영향을 주지 않는다.

AI 단어 선택은 사용자 답을 확률 가중 평균으로 보는 2단계 탐색(AI → 사용자 → AI)으로 광고 단어가 나올 기회를 찾는다(`core/.../ad/AdPlanner.kt`, 설계: `docs/AD_WORD_STEERING_DESIGN.md`). **실제 광고 제휴가 아닌 시연용**이며 수치는 측정 전 기본안이다. 노출 기록은 기기 안에만 남는다.

기록·포인트는 기기 안에만 저장되며 앱을 지우거나 데이터를 초기화하면 사라진다. AI 단어장은 자체 작성한 일반 명사 699개와 우리말샘에서 받은 명사 28,902개다. 그중 965개는 누구나 아는 일상 단어로 직접 골라 쉬움 난이도에서 쓴다(두 목록 모두에 없는 말은 "단어장에 없다"고 안내한다).

### 단어 데이터 출처·라이선스

`core/src/main/resources/data/words_opendict.tsv`(표제어·뜻풀이)와 `words_accept.txt`(표제어)는 국립국어원 우리말샘(https://opendict.korean.go.kr)에서 `tools/fetch-opendict.mjs`, `tools/fetch-opendict-accept.mjs`로 받은 것이며, **이 두 파일만** 원자료와 같은 [CC BY-SA 2.0 KR](https://creativecommons.org/licenses/by-sa/2.0/kr/) 라이선스를 따른다. 앱 홈·보관함 화면에도 출처를 표시한다. 다시 받으려면 우리말샘 오픈 API 키를 `OPENDICT_KEY` 환경 변수나 `~/.config/carvoicegame/opendict.key`에 넣고 `node tools/fetch-opendict.mjs`를 실행한다(키는 저장소에 넣지 않는다).

## APK

릴리스 APK는 팀명·앱 이름이 정해지면 `apk/` 폴더에 공식 파일명으로 둡니다. 서버 주소 없이 빌드합니다(`./gradlew :app:assembleRelease`).
