package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://reidosembeds.com/api"
    private var channelsCache: MutableMap<String, Pair<String, String>> = mutableMapOf()
    private val blockedCategories = listOf("Adulto", "adulto", "ADULTO")

    // ✅ Cache local com TTL de 10 minutos
    private var cachedChannelsJson: JSONArray? = null
    private var cacheTimestamp: Long = 0
    private val cacheTtlMs = 10 * 60 * 1000L

    private fun safeJsonObject(text: String): JSONObject? {
        return try {
            if (!text.trimStart().startsWith("{")) null
            else JSONObject(text)
        } catch (e: Exception) { null }
    }

    // ✅ Retry com backoff exponencial
    private suspend fun getWithRetry(url: String, maxRetries: Int = 3): String? {
        repeat(maxRetries) { attempt ->
            val response = app.get(url)
            if (response.code == 429) {
                val waitMs = (2000L * (attempt + 1)) // 2s, 4s, 6s
                delay(waitMs)
                return@repeat
            }
            val text = response.text
            if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
                return text
            }
        }
        return null
    }

    // ✅ Busca todos os canais uma vez e cacheia
    private suspend fun getAllChannels(): JSONArray? {
        val now = System.currentTimeMillis()
        if (cachedChannelsJson != null && (now - cacheTimestamp) < cacheTtlMs) {
            return cachedChannelsJson
        }

        val text = getWithRetry("$apiUrl/channels") ?: return null
        val json = safeJsonObject(text) ?: return null
        val data = try { json.getJSONArray("data") } catch (e: Exception) { return null }

        cachedChannelsJson = data
        cacheTimestamp = now
        return data
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = mutableListOf<HomePageList>()

        // ✅ Uma única chamada — agrupa por categoria localmente
        val allChannelsArray = getAllChannels()
            ?: return newHomePageResponse(categories, hasNext = false)

        val categoryMap = mutableMapOf<String, MutableList<SearchResponse>>()
        val allChannels = mutableListOf<SearchResponse>()

        for (i in 0 until allChannelsArray.length()) {
            val channel = allChannelsArray.getJSONObject(i)
            val channelName = channel.getString("name")
            val slug = channel.optString("id", "")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.optString("logo_url", "")
            val category = channel.optString("category", "Outros")

            if (blockedCategories.contains(category)) continue

            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"

            if (slug.isNotEmpty()) channelsCache[slug] = Pair(channelName, posterUrl)

            val item = newLiveSearchResponse(channelName, embedUrl, TvType.Live) {
                this.posterUrl = posterUrl
            }

            allChannels.add(item)
            categoryMap.getOrPut(category) { mutableListOf() }.add(item)
        }

        categories.add(HomePageList("Todos", allChannels, isHorizontalImages = true))
        categoryMap.forEach { (catName, channels) ->
            if (channels.isNotEmpty()) {
                categories.add(HomePageList(catName, channels, isHorizontalImages = true))
            }
        }

        return newHomePageResponse(categories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        val channelData = channelsCache[slug]
        val title = channelData?.first ?: slug.replace("-", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val posterUrl = channelData?.second ?: ""
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = posterUrl
            this.plot = "Assista $title ao vivo!"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // ✅ Tenta buscar localmente no cache primeiro
        val cached = cachedChannelsJson
        if (cached != null) {
            val results = mutableListOf<SearchResponse>()
            val q = query.lowercase()
            for (i in 0 until cached.length()) {
                val channel = cached.getJSONObject(i)
                val channelName = channel.getString("name")
                if (!channelName.lowercase().contains(q)) continue
                val category = channel.optString("category", "")
                if (blockedCategories.contains(category)) continue
                val embedUrl = channel.getString("embed_url")
                var posterUrl = channel.optString("logo_url", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                results.add(newLiveSearchResponse(channelName, embedUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                })
            }
            if (results.isNotEmpty()) return results
        }

        // ✅ Fallback: API de pesquisa com retry
        val text = getWithRetry("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}") ?: return emptyList()
        val json = safeJsonObject(text) ?: return emptyList()
        val data = try { json.getJSONObject("data") } catch (e: Exception) { return emptyList() }

        val results = mutableListOf<SearchResponse>()

        val channelsArray = try { data.getJSONArray("channels") } catch (e: Exception) { JSONArray() }
        for (i in 0 until channelsArray.length()) {
            val channel = channelsArray.getJSONObject(i)
            val category = channel.optString("category", "")
            if (blockedCategories.contains(category)) continue
            val channelName = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            var posterUrl = channel.optString("logo_url", "")
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            results.add(newLiveSearchResponse(channelName, embedUrl, TvType.Live) { this.posterUrl = posterUrl })
        }

        val eventsArray = try { data.getJSONArray("events") } catch (e: Exception) { JSONArray() }
        for (i in 0 until eventsArray.length()) {
            val event = eventsArray.getJSONObject(i)
            val title = event.getString("title")
            val embeds = try { event.getJSONArray("embeds") } catch (e: Exception) { JSONArray() }
            if (embeds.length() == 0) continue
            var posterUrl = event.optString("poster", "")
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            val embedUrl = embeds.getJSONObject(0).getString("embed_url")
            results.add(newLiveSearchResponse(title, embedUrl, TvType.Live) { this.posterUrl = posterUrl })
        }

        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelHtml = app.get(data).text
        val iframePattern = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""")
        val iframeMatch = iframePattern.find(channelHtml) ?: return false
        val playerUrl = iframeMatch.groupValues[1].replace("&amp;", "&")

        val playerHtml = app.get(playerUrl, headers = mapOf("Referer" to data)).text
        val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val sourcesMatch = sourcesPattern.find(playerHtml) ?: return false

        val sourcesArray = try { JSONArray(sourcesMatch.groupValues[1]) } catch (e: Exception) { return false }

        for (i in 0 until sourcesArray.length()) {
            val source = sourcesArray.getJSONObject(i)
            val streamUrl = source.getString("src").replace("\\/", "/")
            val label = source.optString("label", "Source ${i + 1}")
            M3u8Helper.generateM3u8(
                "$name - $label", streamUrl, playerUrl,
                headers = mapOf(
                    "Referer" to playerUrl,
                    "Origin" to "https://v2.rde.lat",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )
            ).forEach(callback)
        }
        return true
    }
}
