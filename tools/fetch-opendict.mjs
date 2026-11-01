// 우리말샘 오픈 API로 끝말잇기·초성 대결용 명사 단어장을 만든다(빌드 전에 PC에서 한 번 실행).
// 앱에는 결과 TSV만 들어가고 API 키는 들어가지 않는다.
//
// 사용: node tools/fetch-opendict.mjs
//   키: 환경 변수 OPENDICT_KEY 또는 ~/.config/carvoicegame/opendict.key (저장소에 넣지 않는다)
//   결과: core/src/main/resources/data/words_opendict.tsv
//
// 출처: 국립국어원 우리말샘(https://opendict.korean.go.kr), CC BY-SA 2.0 KR.
// 이 파일로 만든 단어 데이터도 같은 라이선스로 배포해야 한다.

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DATA = join(ROOT, "core/src/main/resources/data");
const OUT = join(DATA, "words_opendict.tsv");
const HAND_WRITTEN = ["sample_words.tsv", "words_more_1.tsv", "words_more_2.tsv", "words_more_3.tsv"];

const PER_SYLLABLE = 60; // 시작 음절마다 인기순 상위 몇 개까지 받을지
const MEANING_MAX = 60; // 힌트로 읽으므로 짧게
const DELAY_MS = 150;
const TIMEOUT_MS = 15000;

function readKey() {
  if (process.env.OPENDICT_KEY) return process.env.OPENDICT_KEY.trim();
  const file = join(homedir(), ".config", "carvoicegame", "opendict.key");
  if (existsSync(file)) return readFileSync(file, "utf8").trim();
  console.error("키 없음: OPENDICT_KEY 환경 변수나 ~/.config/carvoicegame/opendict.key 에 넣어 주세요.");
  process.exit(1);
}

const isSyllable = (c) => c >= "가" && c <= "힣";

function readTsvWords(file) {
  return readFileSync(join(DATA, file), "utf8")
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith("#"))
    .map((l) => l.split("\t")[0].trim());
}

// 두음법칙: 녀·뇨·뉴·니→여·요·유·이, 랴·려·례·료·류·리→야·여·예·요·유·이, 라·래·로·뢰·루·르→나·내·노·뇌·누·느
const BASE = 0xac00;
function decompose(c) {
  const i = c.charCodeAt(0) - BASE;
  return [Math.floor(i / 588), Math.floor((i % 588) / 28), i % 28];
}
const compose = (cho, jung, jong) => String.fromCharCode(BASE + cho * 588 + jung * 28 + jong);
function dueum(c) {
  const [cho, jung, jong] = decompose(c);
  const NIEUN = 2, RIEUL = 5, IEUNG = 11;
  const yVowels = [2, 6, 7, 12, 17, 20]; // ㅑ ㅕ ㅖ ㅛ ㅠ ㅣ
  if (cho === NIEUN && [6, 12, 17, 20].includes(jung)) return compose(IEUNG, jung, jong);
  if (cho === RIEUL && yVowels.includes(jung)) return compose(IEUNG, jung, jong);
  if (cho === RIEUL) return compose(NIEUN, jung, jong);
  return null;
}

// 단어장에 나오는 첫 글자·끝 글자(+두음 변형)를 시작 음절로 삼는다.
function syllablesToQuery(words) {
  const set = new Set();
  for (const w of words) {
    for (const c of [w[0], w[w.length - 1]]) {
      if (!isSyllable(c)) continue;
      set.add(c);
      const d = dueum(c);
      if (d) set.add(d);
    }
  }
  return [...set].sort();
}

function shortMeaning(def) {
  let s = def.replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
  s = s.split(/(?<=[.。])\s/)[0].replace(/[.。]$/, "");
  if (s.length > MEANING_MAX) s = s.slice(0, MEANING_MAX).replace(/\s\S*$/, "") + "…";
  return s;
}

async function search(key, syllable) {
  const params = new URLSearchParams({
    key, q: syllable, req_type: "json", num: "100", sort: "popular",
    advanced: "y", target: "1", method: "start",
    type1: "word", type3: "general", type4: "general",
    letter_s: "2", letter_e: "4",
  });
  // 응답 없는 요청에서 무한정 멈추지 않도록 시간 제한을 두고, 한 번만 다시 시도한다.
  let res;
  for (let attempt = 1; ; attempt++) {
    try {
      res = await fetch(`https://opendict.korean.go.kr/api/search?${params}`, { signal: AbortSignal.timeout(TIMEOUT_MS) });
      break;
    } catch (e) {
      if (attempt >= 2) throw new Error(`요청 실패 (${syllable}): ${e.name}`);
      await sleep(1000);
    }
  }
  if (!res.ok) throw new Error(`HTTP ${res.status} (${syllable})`);
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    // 키 오류 등은 req_type 과 관계없이 XML 로 온다.
    const code = text.match(/<error_code>(.*?)<\/error_code>/)?.[1];
    const message = text.match(/<message>(.*?)<\/message>/)?.[1];
    throw new Error(code ? `API 오류 ${code} ${message} (${syllable})` : `알 수 없는 응답 (${syllable}): ${text.slice(0, 200)}`);
  }
  if (json.error) throw new Error(`API 오류 (${syllable}): ${JSON.stringify(json.error)}`);
  return json.channel?.item ?? [];
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const key = readKey();
  const handWritten = new Set(HAND_WRITTEN.flatMap(readTsvWords));
  const syllables = syllablesToQuery([...handWritten]);
  console.log(`시작 음절 ${syllables.length}개 조회`);

  const out = new Map();
  let calls = 0;
  for (const s of syllables) {
    let items;
    try {
      items = await search(key, s);
    } catch (e) {
      console.error(String(e.message ?? e));
      if (calls === 0) process.exit(1); // 첫 호출부터 실패하면 키·주소 문제
      continue;
    }
    calls++;
    let rank = 0;
    for (const item of items) {
      if (rank >= PER_SYLLABLE) break;
      const word = String(item.word ?? "").replace(/[-^]/g, "");
      if (word.length < 2 || ![...word].every(isSyllable)) continue;
      if (handWritten.has(word) || out.has(word)) continue;
      const senses = Array.isArray(item.sense) ? item.sense : [item.sense].filter(Boolean);
      const noun = senses.find((x) => x.pos === "명사" && x.definition);
      if (!noun) continue;
      const meaning = shortMeaning(noun.definition);
      if (!meaning || meaning.includes(word)) continue;
      const level = rank < 15 ? 1 : rank < 35 ? 2 : 3;
      out.set(word, { level, meaning });
      rank++;
    }
    process.stdout.write(`\r${calls}/${syllables.length} 음절, ${out.size}단어`);
    await sleep(DELAY_MS);
  }
  console.log();

  const header = [
    "# 출처: 국립국어원 우리말샘(https://opendict.korean.go.kr), CC BY-SA 2.0 KR — 이 파일은 같은 라이선스로 배포한다.",
    `# tools/fetch-opendict.mjs 로 ${new Date().toISOString().slice(0, 10)} 생성. 표제어·명사 뜻풀이 첫 문장(짧게 줄임), 난이도는 인기순 순위로 정함.`,
  ];
  const lines = [...out.entries()]
    .sort(([a], [b]) => a.localeCompare(b, "ko"))
    .map(([w, { level, meaning }]) => `${w}\t${level}\t${meaning}`);
  writeFileSync(OUT, [...header, ...lines, ""].join("\n"), "utf8");
  console.log(`저장: ${OUT} (${lines.length}단어, API 호출 ${calls}번)`);
}

main();
