# Pleos SDK 확인 기록

확인한 것만 적는다. 확인하지 않은 항목은 "미확인"으로 남긴다.

## 환경 (2026-10-31)

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 시스템 이미지 (`system-images;android-34;pleos_car_emulator;x86_64`, rev 2) |
| AVD | 기기 `Pleos_Connect_v2_0`(2560×1440, 160dpi), RAM 4096MB, Cold boot |
| 음성 서비스 | `ai.pleos.playground.caas` 4.2.10-coral-aosp (TTS·STT 서비스 제공) |
| SDK | `ai.pleos.playground:TextToSpeech:3.3.1`, `ai.pleos.playground:SpeechToText:3.3.1` (Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`) |
| SDK 요구 서비스 최소 버전 | caas 4.1.29 (AAR 매니페스트 메타데이터) |

## 결과

| 기능 | 결과 | 근거 | 비고 |
|---|---|---|---|
| 안드로이드 표준 TextToSpeech | **사용 불가** | 표준 TTS 엔진 없음. caas 의 `TTS_SERVICE` 는 Pleos SDK 전용 서비스라 표준 API 로 연결되지 않음 | 일반 폰에서는 표준 TTS 로 동작 |
| Pleos TTS (ON_DEVICE) | **PASS** | 앱 등록 없이 초기화 성공, 한국어(화자 seoyoon) 발화, onStart/onDone 수신 | `speak(text, true)` 필요 — 두 번째 인자가 false 면 이어질 텍스트를 기다리며 onDone 이 오지 않음 |
| Pleos TTS 말하기 속도 | 미지원 | 공개 API 에 속도 설정 없음 | 앱은 "지원하지 않아요"로 안내 |
| Pleos STT (ON_DEVICE) | 초기화·청취 시작 PASS / 인식 결과 **미확인** | 초기화 성공, request() 후 상단 마이크 표시 켜짐 | 에뮬레이터 마이크 입력으로 실제 한국어 인식은 아직 시험 못 함 |
| 리스너 등록 순서 | 확인 | initialize 성공 전에 addEventListener/addListener 를 부르면 SDK 내부 lateinit 예외로 앱이 죽음 | 성공 콜백 안에서 등록 |
| 권한 | 확인 | `pleos.car.permission.STT_SERVICE`, `TTS_SERVICE` 모두 protection=normal (매니페스트 선언만으로 허용) | |
| 오디오 포커스 | 미확인 | — | 내비 안내 끼어들기는 시연 패널 모의 버튼으로만 확인 |
| 주행 규제(CarUxRestrictions) | 미확인 | — | 차량 상태는 모의 입력 |

## 앱 동작 확인 (Pleos 에뮬레이터, 화면 버튼 사용)

끝말잇기 쉬움: 첫 단어 안내 → 10초 제한 → 시간 초과 시 "힌트를 들을까요?" → 네 → 힌트 안내 후 새 10초 → 다시 초과 → 아니요 → 패배 안내 → 결과 화면(보상 없음). 음성 입력 대신 화면 버튼으로 답했다.

## 남은 확인

1. 에뮬레이터에 PC 마이크를 연결해 Pleos STT 한국어 인식 결과(문자열·지연·오인식)를 확인.
2. 실제 오디오 포커스 손실(내비 안내) 시 동작.
3. HYBRID/SERVER 모드는 앱 등록(자격 증명)이 필요해 시험하지 않음.

## 에뮬레이터 음성 잡음 (2026-10-31)

Pleos TTS 음성에 지지직 잡음이 들림. `dumpsys media.audio_flinger` 의 FastMixer `underruns` 가 소리를 낼 때만 늘어난다(대기 15초 동안 742→742 변화 없음, 문장 하나 재생 중 +248).
에뮬레이터 CPU 4→8코어, RAM 4→8GB 로 올려도 같은 수준이라, 앱이나 Pleos 음성 데이터가 아닌 **PC 에뮬레이터의 오디오 출력 경로** 문제로 판단한다. 실차·개발 키트에서 다시 확인할 것.
