// 사용자 답 판정 전용 단어 목록을 만든다(빌드 전에 PC에서 실행). AI 는 이 목록의 단어를 말하지 않는다.
// 서버 없이도 "구더기·타악기"처럼 흔한 말이 사전에 없다고 거절되지 않게 하는 것이 목적이다.
//
// 사용: node tools/fetch-opendict-accept.mjs [--cache 경로]
//   키: 환경 변수 OPENDICT_KEY 또는 ~/.config/carvoicegame/opendict.key (저장소에 넣지 않는다)
//   결과: core/src/main/resources/data/words_accept.txt (유니코드 순 정렬 — 앱이 이진 탐색한다)
//   중간에 끊겨도 --cache 파일에서 이어 받는다.
//
// 출처: 국립국어원 우리말샘(https://opendict.korean.go.kr), CC BY-SA 2.0 KR.
// 이 파일로 만든 단어 데이터도 같은 라이선스로 배포해야 한다.
//
// ⚠ type4=general 을 주면 타악기·가자미·컴퓨터가 빠진다. type3=general 만 쓴다.

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DATA = join(ROOT, "core/src/main/resources/data");
const OUT = join(DATA, "words_accept.txt");
const BUNDLED = ["sample_words.tsv", "words_more_1.tsv", "words_more_2.tsv", "words_more_3.tsv", "words_opendict.tsv"];

const WORKERS = 3;
const PAGE = 100;
const MAX_LETTERS = 4;
const TIMEOUT_MS = 20000;
const MAX_ROUNDS = 4; // 새 끝 글자가 더 안 나올 때까지(보통 2~3바퀴)

const cacheArg = process.argv.indexOf("--cache");
const CACHE = cacheArg > 0 ? process.argv[cacheArg + 1] : join(tmpdir(), "carvoicegame-accept-cache.json");

function readKey() {
  if (process.env.OPENDICT_KEY) return process.env.OPENDICT_KEY.trim();
  const file = join(homedir(), ".config", "carvoicegame", "opendict.key");
  if (existsSync(file)) return readFileSync(file, "utf8").trim();
  console.error("키 없음: OPENDICT_KEY 환경 변수나 ~/.config/carvoicegame/opendict.key 에 넣어 주세요.");
  process.exit(1);
}

const isSyllable = (c) => c >= "가" && c <= "힣";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 두음법칙: tools/fetch-opendict.mjs 와 같다.
const BASE = 0xac00;
function dueum(c) {
  const i = c.charCodeAt(0) - BASE;
  const cho = Math.floor(i / 588), jung = Math.floor((i % 588) / 28), jong = i % 28;
  const compose = (x) => String.fromCharCode(BASE + x * 588 + jung * 28 + jong);
  const NIEUN = 2, RIEUL = 5, IEUNG = 11;
  if (cho === NIEUN && [6, 12, 17, 20].includes(jung)) return compose(IEUNG);
  if (cho === RIEUL && [2, 6, 7, 12, 17, 20].includes(jung)) return compose(IEUNG);
  if (cho === RIEUL) return compose(NIEUN);
  return null;
}

// tools/fetch-opendict.mjs·server/api/word.js·DictionaryDataTest 와 같은 기준으로 유지한다.
const BAD_WORD_PARTS = ["좆", "씹", "병신", "지랄", "개새", "창녀", "니미", "불알", "보지", "자지"];
const BAD_WORDS = new Set(["자위", "성기", "간통", "눈엣가시", "화대", "섹스어필", "섹시녀", "섹시미", "섹기"]);
const BAD_MEANING_PARTS = [
  "낮잡아", "속되게", "비속", "얕잡아", "욕으로", "생식기", "성교", "음란",
  "매춘", "매음", "성매매", "창기", "창녀", "창가(", "유곽", "몸을 파", "강간", "간음", "음탕",
  "성욕", "성적 ", "성적인", "성적(", "성기(", "성기 ", "성행위", "애무", "정액(",
];
const NOT_STANDARD = /방언|북한|옛말/;

/** 뜻 하나라도 부적절하면 뺀다(뜻풀이를 앱에 넣지 않으므로 여기서만 걸러낼 수 있다). */
function keep(word, nouns) {
  if (BAD_WORDS.has(word) || BAD_WORD_PARTS.some((p) => word.includes(p))) return false;
  if (nouns.some((s) => BAD_MEANING_PARTS.some((p) => String(s.definition).includes(p)))) return false;
  return nouns.some((s) => !NOT_STANDARD.test(String(s.type ?? "")));
}

async function page(key, syllable, start) {
  const params = new URLSearchParams({
    key, q: syllable, req_type: "json", num: String(PAGE), start: String(start),
    advanced: "y", target: "1", method: "start", pos: "1",
    type1: "word", type3: "general", letter_s: "2", letter_e: String(MAX_LETTERS),
  });
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(`https://opendict.korean.go.kr/api/search?${params}`, { signal: AbortSignal.timeout(TIMEOUT_MS) });
      const text = await res.text();
      const channel = JSON.parse(text).channel; // 키 오류 등은 XML 로 와서 여기서 실패한다
      if (!res.ok || !channel) throw new Error(`응답 이상 ${res.status}`);
      return { total: Number(channel.total) || 0, items: channel.item ?? [] };
    } catch (e) {
      if (attempt >= 4) throw new Error(`요청 실패 (${syllable} ${start}쪽): ${e.name}`);
      await sleep(1500 * attempt);
    }
  }
}

/** 한 글자로 시작하는 단어를 끝까지 받는다. 실패하면 던져서 이 글자를 완료로 치지 않는다. */
async function harvest(key, syllable, words) {
  for (let start = 1; ; start++) {
    const { total, items } = await page(key, syllable, start);
    for (const item of items) {
      const word = String(item.word ?? "").replace(/[-^ ]/g, "");
      if (word.length < 2 || word.length > MAX_LETTERS || ![...word].every(isSyllable)) continue;
      const senses = Array.isArray(item.sense) ? item.sense : [item.sense].filter(Boolean);
      const nouns = senses.filter((s) => s.pos === "명사" && s.definition);
      if (nouns.length > 0 && keep(word, nouns)) words.add(word);
    }
    // 서버가 가끔 중간 쪽을 비워 준다. 끝으로 치면 그 글자 뒷부분이 통째로 빠지므로(구식~구힐 878개 누락 사례) 실패로 처리한다.
    if (items.length === 0 && start * PAGE < total) throw new Error(`빈 쪽 (${syllable} ${start}쪽 / 전체 ${total})`);
    if (start * PAGE >= total) return;
  }
}

function bundledWords() {
  return BUNDLED.flatMap((f) => readFileSync(join(DATA, f), "utf8").split(/\r?\n/))
    .map((l) => l.trim()).filter((l) => l && !l.startsWith("#")).map((l) => l.split("\t")[0].trim());
}

function headsOf(words) {
  const set = new Set();
  for (const w of words) for (const c of [w[0], w[w.length - 1]]) {
    if (!isSyllable(c)) continue;
    set.add(c);
    const d = dueum(c);
    if (d) set.add(d);
  }
  return set;
}

async function main() {
  const key = readKey();
  const bundled = bundledWords();
  const cache = existsSync(CACHE) ? JSON.parse(readFileSync(CACHE, "utf8")) : { done: [], words: [] };
  const done = new Set(cache.done);
  const words = new Set(cache.words);
  const failed = new Set();
  const save = () => writeFileSync(CACHE, JSON.stringify({ done: [...done], words: [...words] }));

  for (let round = 1; round <= MAX_ROUNDS; round++) {
    const queue = [...headsOf([...bundled, ...words])].filter((c) => !done.has(c) && !failed.has(c)).sort();
    console.log(`${round}바퀴: 글자 ${queue.length}개, 누적 ${words.size}단어`);
    if (queue.length === 0) break;
    const total = queue.length;
    let finished = 0;
    await Promise.all(Array.from({ length: WORKERS }, async () => {
      for (let c = queue.shift(); c; c = queue.shift()) {
        try {
          await harvest(key, c, words);
          done.add(c);
        } catch (e) {
          failed.add(c);
          console.error(e.message);
        }
        if (++finished % 25 === 0) {
          save();
          console.log(`${round}바퀴 ${finished}/${total}, 누적 ${words.size}단어`);
        }
      }
    }));
    save();
  }

  if (failed.size > 0) {
    console.error(`실패한 글자 ${failed.size}개: ${[...failed].join("")} — 다시 실행하면 이어 받습니다. 파일은 쓰지 않았습니다.`);
    process.exit(1);
  }
  const own = new Set(bundled);
  const sorted = [...words].filter((w) => !own.has(w)).sort(); // 기본 정렬 = UTF-16 순, Kotlin String.compareTo 와 같다
  const header = [
    "# 출처: 국립국어원 우리말샘(https://opendict.korean.go.kr), CC BY-SA 2.0 KR — 이 파일은 같은 라이선스로 배포한다.",
    `# tools/fetch-opendict-accept.mjs 로 ${new Date().toISOString().slice(0, 10)} 생성. 표제어만. 사용자 답 판정 전용(AI 는 말하지 않음). 정렬 유지 필수.`,
  ];
  writeFileSync(OUT, [...header, ...sorted, ""].join("\n"), "utf8");
  console.log(`저장: ${OUT} (${sorted.length}단어, 글자 ${done.size}개)`);
}

main();
