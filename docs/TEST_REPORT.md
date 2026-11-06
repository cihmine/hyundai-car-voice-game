# 테스트 보고

- 날짜: 2026-11-04
- 환경: Windows 11, JDK 21(Android Studio jbr), AGP 8.13.0 · Kotlin 2.2.20 · Pleos Connect v2.1.0 에뮬레이터(API 34)
- 빌드: `:core:test`, `:app:assembleRelease`(서명·R8 축소, 서버 주소 없음)

| 사례 | 방법 | 결과 |
|---|---|---|
| 규칙 엔진·명령 해석·사전 데이터 | `:core:test` | PASS — 120개 전부 통과 |
| 축소 빌드 실행 | release 를 새로 설치해 실행 | PASS — Pleos TTS·STT 연결, 크래시 없음 |
| 저장·복구(축소 빌드) | 판 시작 → 일시정지 → `am force-stop` → 재실행 | PASS — "일시정지된 끝말잇기 판이 있어요" 표시 |
| 첫 판 시작(축소 빌드) | 터치 화면에서 시작 | PASS — 규칙 질문·AI 첫 단어·힌트 |
| 서버 없는 판정 | `DictionaryDataTest` | PASS |
| 판정 전용 단어가 AI 후보로 새지 않음 | `DictionaryDataTest` | PASS |
| 잘못 들린 말 구제(94→구사→부사, 자음·모음 하나 차이) | `WordGameTest` | PASS |
| 실제 음성 대화 | 헤드셋 마이크(2026-11-03~04) | PASS — 사용자 직접 시험, 이때 나온 인식 구멍을 고침 |
| 인터넷 검색 | PC 로컬 중계 서버 + debug 빌드 | PASS — 소고기·타악기·조폐·강화 인정 |

미검증: 실제 차량의 소음·스피커·마이크, 실제 주차/주행 신호, 브랜드 단어의 에뮬 노출.
