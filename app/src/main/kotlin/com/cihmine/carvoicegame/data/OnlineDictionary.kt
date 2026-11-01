package com.cihmine.carvoicegame.data

import com.cihmine.carvoicegame.BuildConfig
import com.cihmine.carvoicegame.core.word.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 중계 서버 응답. [anyWords] 는 거르기 전 두 글자 이상 항목 수(0이면 그 글자로 이을 단어가 없다). */
data class LookupResult(val entries: List<WordEntry>, val anyWords: Int)

/**
 * 우리말샘 검색(server/api/word.js 중계). 앱에는 API 키가 없다.
 * 실패(오프라인·시간 초과)는 null — 호출하는 쪽은 앱 안 사전만으로 진행하고, 한방 확인에는 쓰지 않는다.
 */
class OnlineDictionary(private val baseUrl: String) {

    suspend fun exact(word: String): LookupResult? = get(word, "exact")

    suspend fun startingWith(head: Char): LookupResult? = get(head.toString(), "start")

    private suspend fun get(q: String, mode: String): LookupResult? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/api/word?q=${URLEncoder.encode(q, "UTF-8")}&mode=$mode")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val array = json.getJSONArray("entries")
                val entries = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    WordEntry(o.getString("word"), o.optInt("level", 2), o.getString("meaning"), core = false)
                }
                LookupResult(entries, json.optInt("anyWords", entries.size))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        private const val TIMEOUT_MS = 2_500

        /** 빌드 때 -Pcarvoicegame.dictProxy=주소 로 넣는다. 비어 있으면 인터넷 검색을 쓰지 않는다. */
        fun fromBuildConfig(): OnlineDictionary? = BuildConfig.DICT_PROXY_URL.takeIf { it.isNotBlank() }?.let(::OnlineDictionary)
    }
}
