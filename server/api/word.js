// 말게임 단어 검색 중계 함수(Vercel). 앱에 우리말샘 API 키를 넣지 않기 위해 키는 서버 환경 변수에만 둔다.
//
// GET /api/word?q=출발&mode=exact   → 그 단어가 명사로 있으면 [{word, level, meaning}]
// GET /api/word?q=출&mode=start     → 그 글자로 시작하는 명사(인기순, 최대 30개)
//
// 출처: 국립국어원 우리말샘(https://opendict.korean.go.kr), CC BY-SA 2.0 KR.
// 차 안 가족 게임이므로 비속어·남을 낮잡는 말은 돌려주지 않는다(tools/fetch-opendict.mjs 와 같은 기준).

const BAD_WORD_PARTS = ["좆", "씹", "병신", "지랄", "개새", "창녀", "니미", "불알", "보지", "자지"];
const BAD_WORDS = new Set(["자위", "성기", "간통", "눈엣가시", "화대", "섹스어필", "섹시녀", "섹시미", "섹기"]);
const BAD_MEANING_PARTS = [
  "낮잡아", "속되게", "비속", "얕잡아", "욕으로", "생식기", "성교", "음란",
  "매춘", "매음", "성매매", "창기", "창녀", "창가(", "유곽", "몸을 파", "강간", "간음", "음탕",
  "성욕", "성적 ", "성적인", "성적(", "성기(", "성기 ", "성행위", "애무", "정액(",
];
const HANGUL = /^[가-힣]+$/;
const MEANING_MAX = 60;
const MAX_RESULTS = 30;

const isUnsuitable = (word, meaning) =>
  BAD_WORDS.has(word) || BAD_WORD_PARTS.some((p) => word.includes(p)) || BAD_MEANING_PARTS.some((p) => meaning.includes(p));

function shortMeaning(def) {
  let s = String(def).replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
  s = s.split(/(?<=[.。])\s/)[0].replace(/[.。]$/, "");
  if (s.length > MEANING_MAX) s = s.slice(0, MEANING_MAX).replace(/\s\S*$/, "") + "…";
  return s;
}

/** 우리말샘 응답을 앱이 쓰는 모양으로 줄인다. 순위로 난이도(1~3)를 매긴다. */
export function toEntries(items, { exactWord } = {}) {
  const out = [];
  const seen = new Set();
  for (const item of items) {
    const word = String(item.word ?? "").replace(/[-^]/g, "");
    if (word.length < 2 || !HANGUL.test(word) || seen.has(word)) continue;
    if (exactWord && word !== exactWord) continue;
    const senses = Array.isArray(item.sense) ? item.sense : [item.sense].filter(Boolean);
    const noun = senses.find((x) => x.pos === "명사" && x.definition);
    if (!noun) continue;
    const meaning = shortMeaning(noun.definition);
    if (!meaning || meaning.includes(word) || isUnsuitable(word, meaning)) continue;
    const rank = out.length;
    out.push({ word, level: rank < 10 ? 1 : rank < 20 ? 2 : 3, meaning });
    seen.add(word);
    if (out.length >= MAX_RESULTS) break;
  }
  return out;
}

export default async function handler(req, res) {
  if (req.method !== "GET") return res.status(405).json({ error: "GET only" });
  const q = String(req.query.q ?? "");
  const mode = req.query.mode === "start" ? "start" : "exact";
  if (!HANGUL.test(q) || q.length > 10 || (mode === "start" && q.length !== 1)) {
    return res.status(400).json({ error: "q: 완성형 한글(시작 글자 검색은 한 글자)" });
  }
  const key = process.env.OPENDICT_KEY;
  if (!key) return res.status(500).json({ error: "server not configured" });

  const params = new URLSearchParams({
    key, q, req_type: "json", advanced: "y", target: "1", method: mode,
    num: "10", type1: "word",
    // 한 글자 동음이의어(병·말 등)가 앞 10개를 채우지 않게 두 글자 이상만. letter_e 가 같이 있어야 적용된다.
    // 인기순 정렬(sort=popular)은 3초 넘게 걸려 쓰지 않는다(가나다순).
    letter_s: "2", letter_e: "10",
  });
  try {
    const upstream = await fetch(`https://opendict.korean.go.kr/api/search?${params}`, { signal: AbortSignal.timeout(4000) });
    const text = await upstream.text();
    let channel;
    try {
      channel = JSON.parse(text).channel;
    } catch {
      return res.status(502).json({ error: "upstream" }); // 키 오류 등은 XML 로 온다
    }
    // 오류 응답·모양이 다른 응답을 "이을 단어 0개"로 착각해 한방으로 확정·캐시하지 않는다.
    const total = Number(channel?.total);
    if (!upstream.ok || !channel || !Number.isFinite(total) || total < 0) {
      return res.status(502).json({ error: "upstream" });
    }
    // 같은 질문은 하루 동안 CDN 에 저장해 우리말샘 하루 한도를 아낀다.
    res.setHeader("Cache-Control", "public, s-maxage=86400, stale-while-revalidate=604800");
    // anyWords: 거르기 전 두 글자 이상 항목의 전체 수. 앱은 이것이 0일 때만 "이을 단어 없음(한방)"으로 본다 —
    // 앞 10개에 쓸 만한 명사가 없을 뿐인 글자를 한방으로 잘못 쓰지 않도록 보수적으로 판단한다.
    const anyWords = total;
    return res.status(200).json({ entries: toEntries(channel.item ?? [], { exactWord: mode === "exact" ? q : undefined }), anyWords });
  } catch {
    return res.status(504).json({ error: "timeout" });
  }
}
