package com.example.core.web

import android.util.Base64
import com.example.data.settings.SettingsRepository
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.URI

data class WebDocument(
    val url: String,
    val title: String,
    val contentType: String,
    val textContent: String,
    val outboundLinks: List<String>,
)

class WebContentReader(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val userAgent = "Mozilla/5.0 (Linux; Android 14; AgentLab) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36"

    suspend fun readUrl(rawUrl: String): WebDocument = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()

        // 1. Specialized YouTube transcript + metadata reader
        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            return@withContext readYouTubeUrl(url)
        }

        // 2. Specialized GitHub repository & README reader
        if (host == "github.com" || host == "www.github.com") {
            val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
            if (segments.size == 2) {
                return@withContext readGitHubRepo(segments[0], segments[1], url)
            }
            // Convert github.com/.../blob/... to raw.githubusercontent.com for clean file/README reading
            if (segments.size >= 5 && segments[2] == "blob") {
                val rawGitHubUrl = "https://raw.githubusercontent.com/${segments[0]}/${segments[1]}/${segments[3]}/${segments.drop(4).joinToString("/")}"
                return@withContext readPlainOrHtmlOrPdf(rawGitHubUrl, originalUrl = url)
            }
        }

        // 3. General HTTP/PDF/HTML/Markdown reader
        readPlainOrHtmlOrPdf(url, originalUrl = url)
    }

    private suspend fun readGitHubRepo(owner: String, repo: String, originalUrl: String): WebDocument {
        val cleanRepo = repo.removeSuffix(".git")
        val token = settingsRepository.getSnapshot().githubToken

        fun gitHubReq(apiUrl: String): Request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AgentLab-Android")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        val sb = StringBuilder()
        var title = "$owner/$cleanRepo"
        val outbound = mutableListOf<String>()

        // Fetch repo metadata
        runCatching {
            httpClient.newCall(gitHubReq("https://api.github.com/repos/$owner/$cleanRepo")).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    val fullName = obj["full_name"]?.jsonPrimitive?.contentOrNull ?: title
                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val lang = obj["language"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val stars = obj["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0
                    val homepage = obj["homepage"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    title = "GitHub: $fullName"
                    sb.appendLine("# $fullName")
                    if (desc.isNotBlank()) sb.appendLine("Description: $desc")
                    if (lang.isNotBlank()) sb.appendLine("Primary Language: $lang | Stars: $stars")
                    if (homepage.startsWith("http")) {
                        sb.appendLine("Homepage: $homepage")
                        outbound.add(homepage)
                    }
                    sb.appendLine()
                }
            }
        }

        // Fetch README
        var readmeLoaded = false
        runCatching {
            httpClient.newCall(gitHubReq("https://api.github.com/repos/$owner/$cleanRepo/readme")).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    val encoded = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", "")
                    if (encoded.isNotBlank()) {
                        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                        sb.appendLine("## README")
                        sb.appendLine(decoded)
                        readmeLoaded = true
                    }
                }
            }
        }

        // Fetch top-level file listing for architecture context
        runCatching {
            httpClient.newCall(gitHubReq("https://api.github.com/repos/$owner/$cleanRepo/contents")).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonArray
                    sb.appendLine("\n## Repository Files")
                    for (item in arr.take(30)) {
                        val o = item.jsonObject
                        val name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val htmlUrl = o["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        sb.appendLine("- [$type] $name")
                        if ((name.endsWith(".md", true) || name == "docs") && htmlUrl.startsWith("http")) {
                            outbound.add(htmlUrl)
                        }
                    }
                }
            }
        }

        if (!readmeLoaded && sb.isBlank()) {
            return readPlainOrHtmlOrPdf(originalUrl, originalUrl)
        }

        return WebDocument(
            url = originalUrl,
            title = title,
            contentType = "github/repo",
            textContent = sb.toString().take(30_000),
            outboundLinks = outbound.distinct().take(15),
        )
    }

    private fun readYouTubeUrl(url: String): WebDocument {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("YouTube HTTP ${resp.code}")
            }
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().ifBlank { "YouTube Video" }
            val description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()

            val transcriptText = extractYouTubeTranscript(html)
            val sb = StringBuilder()
            sb.appendLine("# $title")
            if (description.isNotBlank()) {
                sb.appendLine("\n## Video Description\n$description")
            }
            if (transcriptText.isNotBlank()) {
                sb.appendLine("\n## Transcript\n$transcriptText")
            } else {
                sb.appendLine("\n(No public captions/transcript track was available for this video.)")
            }

            return WebDocument(
                url = url,
                title = title,
                contentType = "youtube/transcript",
                textContent = sb.toString().take(30_000),
                outboundLinks = emptyList(),
            )
        }
    }

    private fun extractYouTubeTranscript(html: String): String {
        val marker = "\"captionTracks\":"
        val idx = html.indexOf(marker)
        if (idx < 0) return ""
        val after = html.substring(idx + marker.length)
        val startBracket = after.indexOf('[')
        if (startBracket < 0) return ""
        var depth = 0
        var endBracket = -1
        for (i in startBracket until after.length) {
            when (after[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        endBracket = i
                        break
                    }
                }
            }
        }
        if (endBracket <= startBracket) return ""
        val arrayJson = after.substring(startBracket, endBracket + 1)
        val tracks = runCatching { json.parseToJsonElement(arrayJson).jsonArray }.getOrNull() ?: return ""
        val chosenTrack = tracks.firstOrNull {
            it.jsonObject["languageCode"]?.jsonPrimitive?.contentOrNull?.startsWith("en") == true
        } ?: tracks.firstOrNull() ?: return ""

        val baseUrl = chosenTrack.jsonObject["baseUrl"]?.jsonPrimitive?.contentOrNull
            ?.replace("\\u0026", "&") ?: return ""
        val captionReq = Request.Builder().url(baseUrl).header("User-Agent", userAgent).build()
        return runCatching {
            httpClient.newCall(captionReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val xml = resp.body?.string().orEmpty()
                val capDoc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                capDoc.select("text").joinToString(" ") { it.text() }
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
        }.getOrDefault("")
    }

    private fun readPlainOrHtmlOrPdf(url: String, originalUrl: String): WebDocument {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} fetching $url")
            }
            val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
            val body = resp.body ?: throw IllegalStateException("Empty body from $url")

            if (contentType.contains("application/pdf") || url.lowercase().substringBefore('?').endsWith(".pdf")) {
                val bytes = body.bytes()
                if (bytes.size > 15 * 1024 * 1024) {
                    throw IllegalStateException("PDF exceeds 15 MB safety limit")
                }
                val pdfText = extractPdfText(bytes)
                val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "Document.pdf" }
                return WebDocument(
                    url = originalUrl,
                    title = fileName,
                    contentType = "application/pdf",
                    textContent = pdfText.take(35_000),
                    outboundLinks = emptyList(),
                )
            }

            val rawText = body.string()
            if (contentType.contains("text/plain") || contentType.contains("text/markdown") || contentType.contains("application/json")) {
                return WebDocument(
                    url = originalUrl,
                    title = url.substringAfterLast('/').ifBlank { url },
                    contentType = contentType.ifBlank { "text/plain" },
                    textContent = rawText.take(35_000),
                    outboundLinks = extractUrlsFromText(rawText),
                )
            }

            val doc = Jsoup.parse(rawText, url)
            val title = doc.title().ifBlank {
                doc.selectFirst("h1")?.text().orEmpty().ifBlank { url }
            }
            val links = doc.select("a[href]")
                .mapNotNull { it.absUrl("href").takeIf { href -> href.startsWith("http://") || href.startsWith("https://") } }
                .filterNot { it.contains("#") && it.substringBefore("#") == url }
                .distinct()
                .take(25)

            doc.select("script, style, nav, footer, header, aside, noscript, iframe, svg").remove()
            val mainEl = doc.selectFirst("article, main, [role=main], .markdown-body, .content") ?: doc.body()
            val extractedText = mainEl?.select("h1, h2, h3, h4, p, li, pre, code, blockquote, td")
                ?.joinToString("\n") { it.text().trim() }
                ?.takeIf { it.length > 200 }
                ?: mainEl?.text().orEmpty()

            val cleaned = extractedText
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
                .take(35_000)

            return WebDocument(
                url = originalUrl,
                title = title,
                contentType = "text/html",
                textContent = cleaned,
                outboundLinks = links,
            )
        }
    }

    private fun extractPdfText(bytes: ByteArray): String {
        ByteArrayInputStream(bytes).use { stream ->
            PDDocument.load(stream).use { document ->
                if (document.isEncrypted) {
                    throw IllegalStateException("PDF is password-protected/encrypted")
                }
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = minOf(document.numberOfPages, 40)
                return stripper.getText(document).trim()
            }
        }
    }

    private fun extractUrlsFromText(text: String): List<String> =
        Regex("""https?://[^\s\)\]>"]+""")
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ';') }
            .distinct()
            .take(15)
            .toList()

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
