# 단어 검색 중계 서버

앱이 우리말샘 오픈 API 키를 직접 갖지 않도록 하는 작은 Vercel 함수입니다(Pleos 보안 가이드: 키 하드코딩 금지).

| 요청 | 응답 |
|---|---|
| `GET /api/word?q=출발&mode=exact` | `{"entries":[{"word","level","meaning"}], "anyWords":n}` — 그 단어가 명사로 있으면 entries 에 담긴다 |
| `GET /api/word?q=출&mode=start` | 그 글자로 시작하는 명사(가나다순 앞 10개 중) |

`anyWords`는 거르기 전 두 글자 이상 항목 수입니다. 앱은 이것이 0일 때만 그 글자를 "이을 단어 없음(한방)"으로 봅니다. 인기순 정렬은 응답이 3초 넘게 걸려 쓰지 않습니다.

- 비속어·남을 낮잡는 말은 돌려주지 않습니다(`tools/fetch-opendict.mjs`와 같은 기준).
- 같은 요청은 CDN에 하루 저장해 우리말샘 하루 한도(5만 건)를 아낍니다.
- 응답 데이터 출처: 국립국어원 우리말샘, CC BY-SA 2.0 KR.

## 배포

```sh
cd server
npx vercel --prod                  # 처음엔 프로젝트 연결을 묻는다
npx vercel env add OPENDICT_KEY    # 우리말샘 키(저장소에 넣지 않는다)
```

앱은 빌드할 때 `-Pcarvoicegame.dictProxy=https://<배포 주소>`로 주소를 받습니다. 비워 두면 인터넷 검색 없이 앱 안 사전만 씁니다.
