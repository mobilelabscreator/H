package com.example.core.web

import com.example.data.settings.SearchProvider
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Serializable
data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
)

class WebSearchClient(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun search(query: String, maxResults: Int = 6): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        val settings = settingsRepository.getSnapshot()
        when (settings.searchProvider) {
            SearchProvider.DUCKDUCKGO -> searchDuckDuckGoHtml(cleanQuery, maxResults)
            SearchProvider.TAVILY -> {
                if (settings.searchApiKey.isBlank()) {
                    searchDuckDuckGoHtml(cleanQuery, maxResults)
                } else {
                    searchTavily(cleanQuery, settings.searchApiKey, maxResults)
                }
            }
            SearchProvider.SERPER -> {
                if (settings.searchApiKey.isBlank()) {
                    searchDuckDuckGoHtml(cleanQuery, maxResults)
                } else {
                    searchSerper(cleanQuery, settings.searchApiKey, maxResults)
                }
            }
            SearchProvider.BRAVE -> {
                if (settings.searchApiKey.isBlank()) {
                    searchDuckDuckGoHtml(cleanQuery, maxResults)
                } else {
                    searchBrave(cleanQuery, settings.searchApiKey, maxResults)
                }
            }
        }
    }

    private fun searchDuckDuckGoHtml(query: String, maxResults: Int): List<SearchResultItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encoded")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("DuckDuckGo search returned HTTP ${resp.code}")
            }
            val html = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(html)
            val results = mutableListOf<SearchResultItem>()
            for (el in doc.select("div.result, div.results_links")) {
                val link = el.selectFirst("a.result__a") ?: continue
                val title = link.text().trim()
                val rawHref = link.attr("href")
                val resolvedUrl = resolveDuckDuckGoUrl(rawHref)
                val snippet = el.selectFirst("a.result__snippet, div.result__snippet")?.text()?.trim().orEmpty()
                if (title.isNotBlank() && resolvedUrl.startsWith("http")) {
                    results.add(SearchResultItem(title = title, url = resolvedUrl, snippet = snippet))
                    if (results.size >= maxResults) break
                }
            }
            return results
        }
    }

    private fun resolveDuckDuckGoUrl(href: String): String {
        if (href.contains("uddg=")) {
            val part = href.substringAfter("uddg=").substringBefore("&")
            return runCatching { URLDecoder.decode(part, StandardCharsets.UTF_8.name()) }.getOrDefault(href)
        }
        return if (href.startsWith("//")) "https:$href" else href
    }

    private fun searchTavily(query: String, apiKey: String, maxResults: Int): List<SearchResultItem> {
        val payload = buildJsonObject {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", maxResults)
            put("include_answer", false)
        }
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Tavily HTTP ${resp.code}: ${body.take(300)}")
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["results"]?.jsonArray?.mapNotNull { item ->
                val r = item.jsonObject
                val title = r["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = r["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val snippet = r["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (url.startsWith("http")) SearchResultItem(title, url, snippet) else null
            }.orEmpty()
        }
    }

    private fun searchSerper(query: String, apiKey: String, maxResults: Int): List<SearchResultItem> {
        val payload = buildJsonObject {
            put("q", query)
            put("num", maxResults)
        }
        val req = Request.Builder()
            .url("https://google.serper.dev/search")
            .header("X-API-KEY", apiKey)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Serper HTTP ${resp.code}: ${body.take(300)}")
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["organic"]?.jsonArray?.mapNotNull { item ->
                val r = item.jsonObject
                val title = r["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = r["link"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val snippet = r["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (url.startsWith("http")) SearchResultItem(title, url, snippet) else null
            }.orEmpty()
        }
    }

    private fun searchBrave(query: String, apiKey: String, maxResults: Int): List<SearchResultItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$maxResults")
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Brave HTTP ${resp.code}: ${body.take(300)}")
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["web"]?.jsonObject?.get("results")?.jsonArray?.mapNotNull { item ->
                val r = item.jsonObject
                val title = r["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = r["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val snippet = r["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (url.startsWith("http")) SearchResultItem(title, url, snippet) else null
            }.orEmpty()
        }
    }
}
