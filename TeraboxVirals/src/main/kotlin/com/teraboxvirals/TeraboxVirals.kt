package com.teraboxvirals

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink

class TeraboxVirals : MainAPI() {
    override var mainUrl = "https://www.teraboxvirals.com"
    override var name = "TeraboxVirals"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest Posts",
        "search/label/Viral" to "Viral"
    )

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m4v", "wmv")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    private suspend fun extractTeraboxLink(doc: org.jsoup.nodes.Document): String? {
        var tbLink = doc.select(".dlbutton a").firstOrNull {
            val href = it.attr("href")
            href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)
        }?.attr("href") ?: doc.select(".dlbutton a").firstOrNull()?.attr("href")

        if (tbLink == null) {
            tbLink = doc.select("a").firstOrNull {
                val href = it.attr("href")
                href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)
            }?.attr("href")
        }

        if (tbLink?.contains("downloadkatsini.com") == true) {
            try {
                tbLink = app.get(tbLink).document.selectFirst("a[href*=terabox], a[href*=1024tera]")?.attr("href")
            } catch (_: Exception) {}
        }
        return tbLink
    }

    private suspend fun extractMediafireLink(doc: org.jsoup.nodes.Document): String? {
        val directMf = doc.select("a").firstOrNull {
            it.attr("href").contains("mediafire.com", ignoreCase = true)
        }?.attr("href")
        if (directMf != null) return directMf

        val downloadPageUrl = doc.select("a").firstOrNull {
            it.attr("href").contains("downloadkatsini.com", ignoreCase = true)
        }?.attr("href") ?: return null

        return try {
            val downloadDoc = app.get(downloadPageUrl).document
            downloadDoc.select("a").firstOrNull {
                it.attr("href").contains("mediafire.com", ignoreCase = true)
            }?.attr("href")
        } catch (_: Exception) { null }
    }

    private fun extractSurl(tbLink: String): String {
        val raw = if (tbLink.contains("surl=")) {
            tbLink.substringAfter("surl=").substringBefore("&")
        } else {
            tbLink.substringAfter("/s/").substringBefore("?")
        }
        return if (raw.startsWith("1")) raw.substring(1) else raw
    }

    private fun getApiDomain(tbLink: String): String {
        return if (tbLink.contains("1024tera")) "www.1024tera.com" else "www.terabox.com"
    }

    private fun parseThumbFromChunk(chunk: String): String? {
        val thumbs = Regex("\"thumbs\":\\s*\\{(.*?)\\}").find(chunk)?.groupValues?.get(1) ?: return null
        return Regex("\"url3\":\\s*\"(.*?)\"").find(thumbs)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex("\"url2\":\\s*\"(.*?)\"").find(thumbs)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex("\"url1\":\\s*\"(.*?)\"").find(thumbs)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex("\"icon\":\\s*\"(.*?)\"").find(thumbs)?.groupValues?.get(1)?.replace("\\/", "/")
    }

    private suspend fun callListApi(surl: String, apiDomain: String, dirPath: String, headers: Map<String, String>): String? {
        val root = if (dirPath == "/") "1" else "0"
        val apiUrl = "https://$apiDomain/share/list?app_id=250528&web=1&channel=dubox&clienttype=0&shorturl=$surl&dir=${java.net.URLEncoder.encode(dirPath, "UTF-8")}&root=$root"
        for (attempt in 1..3) {
            try {
                val res = app.get(apiUrl, headers = headers).text
                if (res.contains("need verify")) continue
                return res
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val start = (page - 1) * 20 + 1
        val url = if (request.data.isEmpty()) {
            if (page == 1) mainUrl else "$mainUrl/search?max-results=20&start=$start"
        } else {
            if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?max-results=20&start=$start"
        }

        val document = app.get(url).document
        val articles = document.select("article.blog-post")
        val home = articles.mapNotNull { element ->
            val linkElement = element.selectFirst(".post-filter-link") ?: element.selectFirst(".entry-title a") ?: return@mapNotNull null
            val title = element.selectFirst(".entry-title a")?.text() ?: element.selectFirst("img")?.attr("alt") ?: "Video"
            val href = linkElement.attr("href")
            val poster = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() && !it.contains("blank.gif") && !it.contains("pixel.gif") }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document
        return document.select("article.blog-post").mapNotNull { element ->
            val linkElement = element.selectFirst(".post-filter-link") ?: element.selectFirst(".entry-title a") ?: return@mapNotNull null
            val title = element.selectFirst(".entry-title a")?.text() ?: element.selectFirst("img")?.attr("alt") ?: "Video"
            val href = linkElement.attr("href")
            val poster = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() && !it.contains("blank.gif") }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst(".entry-title")?.text()?.trim()
            ?: "Video"
        val tbLink = extractTeraboxLink(document)
        val mediafireLink = extractMediafireLink(document)
        val isTerabox = !tbLink.isNullOrEmpty() && (tbLink.contains("terabox", ignoreCase = true) || tbLink.contains("1024tera", ignoreCase = true))

        var poster: String? = null
        val metaDesc = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags = document.select("a[rel=tag]").map { it.text() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { document.select("a.label-link").map { it.text() }.filter { it.isNotEmpty() }.distinct() }
            .ifEmpty {
                document.select("a[href*=/search/label/]").map { it.text() }.filter { it.isNotEmpty() && it.length < 30 }.distinct()
            }

        val dateStr = document.selectFirst(".post-date.published")?.attr("datetime")
            ?: document.selectFirst("time.published")?.attr("datetime")
            ?: document.selectFirst(".published")?.attr("datetime")
        val year = dateStr?.substringBefore("-")?.toIntOrNull()

        val displayDate = document.selectFirst(".post-date.published")?.text()?.trim()
            ?: document.selectFirst("time.published")?.text()?.trim()
            ?: document.selectFirst(".published")?.text()?.trim()

        val fullPlot = buildString {
            if (!displayDate.isNullOrEmpty()) append("Released: $displayDate\n\n")
            if (!metaDesc.isNullOrEmpty()) append(metaDesc)
        }.trim().ifEmpty { null }

        val episodes = mutableListOf<Episode>()
        if (isTerabox) {
            val surl = extractSurl(tbLink!!)
            val apiDomain = getApiDomain(tbLink)
            var initialUk: String? = null
            var initialShareid: String? = null

            // Consistent session for this load
            val browserId = buildString {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                repeat(48) { append(chars.random()) }
                append("=")
            }
            val headers = mapOf(
                "accept" to "application/json, text/plain, */*",
                "accept-language" to "en-US,en;q=0.9",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "referer" to "https://$apiDomain/",
                "cookie" to "browserid=$browserId; lang=en"
            )

            suspend fun scanForVideos(dirPath: String, depth: Int = 0) {
                if (depth > 5) return
                val json = callListApi(surl, apiDomain, dirPath, headers) ?: return

                if (initialUk == null) {
                    initialUk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)?.takeIf { it != "0" }
                }
                val responseShareId = Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)
                    ?: Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)
                if (responseShareId != null) initialShareid = responseShareId

                val items = json.split("\"fs_id\":")
                items.drop(1).forEach { chunk ->
                    val fsid = Regex("^\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1)
                    val isDir = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: "0"
                    val filename = Regex("\"server_filename\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1) ?: return@forEach
                    val itemPath = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                    val bestThumb = parseThumbFromChunk(chunk)

                    if (isDir == "1") {
                        if (!itemPath.isNullOrEmpty() && itemPath != dirPath && itemPath.count { it == '/' } < 10) {
                            scanForVideos(itemPath, depth + 1)
                        }
                    } else {
                        val ext = filename.substringAfterLast(".", "").lowercase()
                        if (videoExtensions.contains(ext)) {
                            val teraboxUrl = "https://$apiDomain/sharing/link?surl=$surl&uk=${initialUk ?: ""}&shareid=${initialShareid ?: ""}&fsid=${fsid ?: ""}"
                            val episodeData = if (mediafireLink != null) {
                                "MF|$mediafireLink|$filename|$teraboxUrl"
                            } else {
                                teraboxUrl
                            }
                            if (poster == null && bestThumb != null) poster = bestThumb
                            episodes.add(
                                newEpisode(episodeData) {
                                    this.name = filename
                                    this.episode = episodes.size + 1
                                    this.posterUrl = bestThumb ?: poster
                                }
                            )
                        } else if (imageExtensions.contains(ext) && poster == null) {
                            // Check if in a 'Foto' folder, OR just use as poster if we have none yet
                            if (dirPath.contains("Foto", ignoreCase = true) || bestThumb != null) {
                                val thumb = parseThumbFromChunk(chunk)
                                if (thumb != null) poster = thumb
                            }
                        }
                    }
                }
            }

            try { scanForVideos("/") } catch (_: Exception) {}
        }

        if (episodes.isEmpty() && mediafireLink != null) {
            val directUrl = getMediafireDirectUrl(mediafireLink)
            if (directUrl != null) {
                val zipEntries = readZipCentralDirectory(directUrl)
                zipEntries?.forEach { entry ->
                    val ext = entry.fileName.substringAfterLast(".", "").lowercase()
                    if (videoExtensions.contains(ext) && !entry.fileName.startsWith("__MACOSX") && !entry.fileName.startsWith(".")) {
                        val filename = entry.fileName.substringAfterLast("/")
                        val episodeData = "MF|$mediafireLink|$filename|${tbLink ?: url}"
                        episodes.add(
                            newEpisode(episodeData) {
                                this.name = filename
                                this.episode = episodes.size + 1
                                this.posterUrl = poster // Use main post image
                            }
                        )
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            val episodeData = if (mediafireLink != null) {
                "MF|$mediafireLink|$title|${tbLink ?: url}"
            } else {
                tbLink ?: url
            }
            episodes.add(
                newEpisode(episodeData) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = fullPlot
            this.tags = tags
            this.year = year
        }
    }

    private suspend fun getMediafireDirectUrl(mediafirePageUrl: String): String? {
        try {
            val pageHtml = app.get(mediafirePageUrl, headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )).text
            return Regex("href=\"(https?://download[^\"]+)\"").find(pageHtml)?.groupValues?.get(1)
                ?: Regex("(https?://download\\d+\\.mediafire\\.com/[^\"'\\s<>]+)").find(pageHtml)?.groupValues?.get(1)
        } catch (_: Exception) {}
        return null
    }

    private data class ZipEntry(
        val fileName: String,
        val compressionMethod: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long
    )

    private suspend fun readZipCentralDirectory(directUrl: String): List<ZipEntry>? {
        try {
            val headRes = app.head(directUrl, headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            ))
            val contentLength = headRes.size ?: headRes.headers["content-length"]?.toLongOrNull() ?: return null
            val tailStart = maxOf(0L, contentLength - 65536)

            val tailRes = app.get(directUrl, headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Range" to "bytes=$tailStart-${contentLength - 1}"
            ))
            val tailBytes = tailRes.body.bytes()

            var eocdOffset = -1
            for (i in tailBytes.size - 22 downTo 0) {
                if (tailBytes[i] == 0x50.toByte() && tailBytes[i + 1] == 0x4B.toByte() &&
                    tailBytes[i + 2] == 0x05.toByte() && tailBytes[i + 3] == 0x06.toByte()) {
                    eocdOffset = i
                    break
                }
            }
            if (eocdOffset < 0) return null

            val totalEntries = readUint16LE(tailBytes, eocdOffset + 10)
            val centralDirSize = readUint32LE(tailBytes, eocdOffset + 12)
            val centralDirOffset = readUint32LE(tailBytes, eocdOffset + 16)

            val cdStartInTail = (centralDirOffset - tailStart).toInt()
            val cdBytes: ByteArray
            if (cdStartInTail >= 0 && cdStartInTail + centralDirSize.toInt() <= tailBytes.size) {
                cdBytes = tailBytes.copyOfRange(cdStartInTail, cdStartInTail + centralDirSize.toInt())
            } else {
                val cdRes = app.get(directUrl, headers = mapOf(
                    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Range" to "bytes=$centralDirOffset-${centralDirOffset + centralDirSize - 1}"
                ))
                cdBytes = cdRes.body.bytes()
            }

            val entries = mutableListOf<ZipEntry>()
            var pos = 0
            for (i in 0 until totalEntries) {
                if (pos + 46 > cdBytes.size) break
                val sig = readUint32LE(cdBytes, pos)
                if (sig != 0x02014B50L) break

                val compressionMethod = readUint16LE(cdBytes, pos + 10)
                val compressedSize = readUint32LE(cdBytes, pos + 20)
                val uncompressedSize = readUint32LE(cdBytes, pos + 24)
                val nameLength = readUint16LE(cdBytes, pos + 28)
                val extraLength = readUint16LE(cdBytes, pos + 30)
                val commentLength = readUint16LE(cdBytes, pos + 32)
                val localHeaderOffset = readUint32LE(cdBytes, pos + 42)

                val fileName = String(cdBytes, pos + 46, nameLength, Charsets.UTF_8)
                entries.add(ZipEntry(fileName, compressionMethod, compressedSize, uncompressedSize, localHeaderOffset))
                pos += 46 + nameLength + extraLength + commentLength
            }
            return entries
        } catch (_: Exception) {}
        return null
    }

    private fun readUint16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUint32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private suspend fun downloadZipEntry(directUrl: String, entry: ZipEntry): java.io.File? {
        try {
            val localHeaderRes = app.get(directUrl, headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Range" to "bytes=${entry.localHeaderOffset}-${entry.localHeaderOffset + 29}"
            ))
            val headerBytes = localHeaderRes.body.bytes()
            if (headerBytes.size < 30) return null

            val nameLen = readUint16LE(headerBytes, 26)
            val extraLen = readUint16LE(headerBytes, 28)
            val dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
            val dataEnd = dataOffset + entry.compressedSize - 1

            val dataRes = app.get(directUrl, headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Range" to "bytes=$dataOffset-$dataEnd"
            ))
            val compressedData = dataRes.body.bytes()

            val cacheDir = java.io.File(
                com.lagradost.cloudstream3.AcraApplication.context?.cacheDir ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp"),
                "terabox_cache"
            )
            cacheDir.mkdirs()
            cleanOldCache(cacheDir, 2 * 60 * 60 * 1000L)

            val safeFileName = entry.fileName.replace(Regex("[^a-zA-Z0-9._()-]"), "_")
            val outFile = java.io.File(cacheDir, safeFileName)

            if (entry.compressionMethod == 0) {
                outFile.writeBytes(compressedData)
            } else {
                val inflater = java.util.zip.Inflater(true)
                inflater.setInput(compressedData)
                val buffer = ByteArray(8192)
                outFile.outputStream().use { fos ->
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        if (count > 0) fos.write(buffer, 0, count)
                    }
                }
                inflater.end()
            }
            return outFile
        } catch (_: Exception) {}
        return null
    }

    private fun cleanOldCache(cacheDir: java.io.File, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        cacheDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }
    }

    private fun normalizeFilename(name: String): String {
        return name.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val cleanData = data.removePrefix("$mainUrl/").removePrefix(mainUrl)
        android.util.Log.d("TeraboxVirals", "loadLinks cleanData=$cleanData")
        if (cleanData.startsWith("MF|")) {
            val parts = cleanData.split("|", limit = 4)
            if (parts.size >= 4) {
                val mediafirePageUrl = parts[1]
                val targetFileName = parts[2]
                val teraboxFallbackUrl = parts[3]

                android.util.Log.d("TeraboxVirals", "MF flow: mf=$mediafirePageUrl target=$targetFileName tb=$teraboxFallbackUrl")
                tryMediafireZip(mediafirePageUrl, targetFileName, callback)
                loadTeraboxLinks(teraboxFallbackUrl, subtitleCallback, callback)
                return true
            }
        }

        return loadTeraboxLinks(cleanData, subtitleCallback, callback)
    }

    private var currentServer: SimpleWebServer? = null

    private suspend fun tryMediafireZip(mediafirePageUrl: String, targetFileName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val directUrl = getMediafireDirectUrl(mediafirePageUrl)
            android.util.Log.d("TeraboxVirals", "MF directUrl=$directUrl")
            if (directUrl == null) return false
            val entries = readZipCentralDirectory(directUrl)
            android.util.Log.d("TeraboxVirals", "MF entries=${entries?.size}")
            if (entries == null) return false

            val videoEntries = entries.filter { entry ->
                val ext = entry.fileName.substringAfterLast(".", "").lowercase()
                videoExtensions.contains(ext) && !entry.fileName.startsWith("__MACOSX") && !entry.fileName.startsWith(".")
            }
            if (videoEntries.isEmpty()) return false

            val normalizedTarget = normalizeFilename(targetFileName)
            val targetEntry = videoEntries.firstOrNull {
                normalizeFilename(it.fileName) == normalizedTarget
            } ?: videoEntries.firstOrNull {
                normalizeFilename(it.fileName).contains(normalizedTarget) || normalizedTarget.contains(normalizeFilename(it.fileName))
            } ?: videoEntries.firstOrNull {
                val targetBase = normalizeFilename(targetFileName.substringBeforeLast("."))
                val entryBase = normalizeFilename(it.fileName.substringBeforeLast("."))
                targetBase.contains(entryBase) || entryBase.contains(targetBase)
            } ?: videoEntries.first()

            val cachedFile = downloadZipEntry(directUrl, targetEntry)
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                // Stop previous server if running
                currentServer?.stopServer()
                
                // Start new server
                val server = SimpleWebServer(cachedFile)
                server.start()
                currentServer = server
                
                // Give it a moment to bind
                for (i in 0..10) {
                    if (server.port > 0) break
                    kotlinx.coroutines.delay(50)
                }

                if (server.port > 0) {
                    val localUrl = "http://127.0.0.1:${server.port}/video.mp4"
                    android.util.Log.d("TeraboxVirals", "Serving at $localUrl")
                    callback.invoke(
                        newExtractorLink("MediaFire", "MediaFire", localUrl, INFER_TYPE)
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TeraboxVirals", "MF error: ${e.message}", e)
        }
        return false
    }

    class SimpleWebServer(private val file: java.io.File) : Thread() {
        private var serverSocket: java.net.ServerSocket? = null
        var port = 0

        override fun run() {
            try {
                serverSocket = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
                port = serverSocket?.localPort ?: 0
                while (!isInterrupted) {
                    val socket = serverSocket?.accept() ?: break
                    handle(socket)
                }
            } catch (e: Exception) {
                android.util.Log.e("TeraboxVirals", "Server error: ${e.message}", e)
            }
        }

        private fun handle(socket: java.net.Socket) {
            kotlin.concurrent.thread {
                try {
                    socket.use {
                        val input = it.getInputStream()
                        val out = it.getOutputStream()
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
                        val requestLine = reader.readLine()
                        if (requestLine == null) return@use

                        var rangeStart: Long = 0
                        var rangeEnd: Long = file.length() - 1

                        var line = reader.readLine()
                        while (line != null && line.isNotEmpty()) {
                            if (line.startsWith("Range:", true)) {
                                val rangeVal = line.substringAfter("bytes=").trim()
                                val parts = rangeVal.split("-")
                                rangeStart = parts[0].toLongOrNull() ?: 0
                                if (parts.size > 1 && parts[1].isNotEmpty()) {
                                    rangeEnd = parts[1].toLongOrNull() ?: (file.length() - 1)
                                }
                            }
                            line = reader.readLine()
                        }

                        if (rangeEnd >= file.length()) rangeEnd = file.length() - 1
                        val contentLength = rangeEnd - rangeStart + 1

                        val headers = StringBuilder()
                        headers.append("HTTP/1.1 206 Partial Content\r\n")
                        headers.append("Content-Type: video/mp4\r\n")
                        headers.append("Content-Range: bytes $rangeStart-$rangeEnd/${file.length()}\r\n")
                        headers.append("Content-Length: $contentLength\r\n")
                        headers.append("Accept-Ranges: bytes\r\n")
                        headers.append("Connection: close\r\n")
                        headers.append("\r\n")

                        out.write(headers.toString().toByteArray())

                        val buffer = ByteArray(8192)
                        file.inputStream().use { fileIn ->
                            fileIn.skip(rangeStart)
                            var bytesLeft = contentLength
                            while (bytesLeft > 0) {
                                val read = fileIn.read(buffer, 0, minOf(buffer.size.toLong(), bytesLeft).toInt())
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                bytesLeft -= read
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Broken pipe is expected when player closes connection
                }
            }
        }

        fun stopServer() {
            interrupt()
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    private suspend fun loadTeraboxLinks(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val isTeraboxUrl = data.contains("terabox", ignoreCase = true) || data.contains("1024tera", ignoreCase = true)
        if (!data.startsWith("http") || !isTeraboxUrl) {
            if (data.startsWith("http")) {
                try {
                    val document = app.get(data).document
                    document.select("a").forEach { link ->
                        val href = link.attr("href")
                        if (href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)) {
                            loadExtractor(href, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
            return true
        }

        val domain = if (data.contains("1024tera")) "www.1024tera.com" else "www.terabox.com"
        val surl = extractSurl(data)
        if (surl.isEmpty()) {
            try { loadExtractor(data, subtitleCallback, callback) } catch (_: Exception) {}
            return true
        }

        val uri = try { java.net.URI(data) } catch (_: Exception) { null }
        val queryParams = uri?.query?.split("&")?.associate {
            val kv = it.split("=", limit = 2)
            kv[0] to (kv.getOrElse(1) { "" })
        } ?: emptyMap()
        var fsid = queryParams["fsid"] ?: ""
        var uk = queryParams["uk"] ?: ""
        var shareid = queryParams["shareid"] ?: ""

        try {
            val browserId = buildString {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                repeat(48) { append(chars.random()) }
                append("=")
            }

            val browserHeaders = mapOf(
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "accept-language" to "en-US,en;q=0.9",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "sec-fetch-dest" to "document",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "none",
                "sec-fetch-user" to "?1",
                "upgrade-insecure-requests" to "1",
                "cookie" to "browserid=$browserId; lang=en"
            )

            var sharePageResponse: com.lagradost.nicehttp.NiceResponse? = null
            val sharePageUrl = "https://$domain/sharing/link?surl=$surl"
            for (attempt in 1..3) {
                val res = app.get(sharePageUrl, headers = browserHeaders)
                if (res.text.contains("need verify")) continue
                sharePageResponse = res
                break
            }
            if (sharePageResponse == null) sharePageResponse = app.get(sharePageUrl, headers = browserHeaders)

            val pageRes = sharePageResponse.text
            val responseCookies = sharePageResponse.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }
                .map { it.second.substringBefore(";") }
            val cookieMap = mutableMapOf("browserid" to browserId, "lang" to "en")
            responseCookies.forEach { cookie ->
                val kv = cookie.split("=", limit = 2)
                if (kv.size == 2) cookieMap[kv[0].trim()] = kv[1].trim()
            }

            val jsToken = Regex("fn%28%22(.*?)%22%29").find(pageRes)?.groupValues?.get(1)
                ?: Regex("fn\\(\"(.*?)\"\\)").find(pageRes)?.groupValues?.get(1)
                ?: Regex("\"jsToken\":\\s*\"(.*?)\"").find(pageRes)?.groupValues?.get(1)

            if (jsToken != null) cookieMap["ndus"] = jsToken

            val randsk = cookieMap["TSID"]?.let { java.net.URLEncoder.encode(it, "UTF-8") }
                ?: cookieMap["randsk"]?.let { java.net.URLEncoder.encode(it, "UTF-8") }
                ?: Regex("\"randsk\":\\s*\"(.*?)\"").find(pageRes)?.groupValues?.get(1)
                ?: ""

            if (uk.isEmpty()) uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1) ?: ""
            if (shareid.isEmpty()) {
                shareid = Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1) ?: ""
            }

            var sign = Regex("\"sign\":\\s*\"([a-f0-9]+)\"").find(pageRes)?.groupValues?.get(1)
            var timestamp = Regex("\"timestamp\":\\s*(\\d+)").find(pageRes)?.groupValues?.get(1)

            val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val headers = mapOf(
                "accept" to "application/json, text/plain, */*",
                "accept-language" to "en-US,en;q=0.9",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "referer" to "https://$domain/sharing/link?surl=$surl",
                "cookie" to cookieString
            )

            if (fsid.isEmpty()) {
                val listBaseParams = "app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}&shorturl=$surl&uk=$uk&shareid=$shareid"
                val rootListUrl = "https://$domain/share/list?$listBaseParams&dir=%2F&root=1"
                for (attempt in 1..3) {
                    try {
                        val listRes = app.get(rootListUrl, headers = headers).text
                        if (listRes.contains("need verify")) continue
                        if (uk.isEmpty()) uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(listRes)?.groupValues?.get(1) ?: ""
                        if (shareid.isEmpty()) {
                            shareid = Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(listRes)?.groupValues?.get(1)
                                ?: Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(listRes)?.groupValues?.get(1) ?: ""
                        }

                        suspend fun findFirstVideo(json: String, depth: Int = 0): String? {
                            if (depth > 3) return null
                            val items = json.split("\"fs_id\":")
                            for (chunk in items.drop(1)) {
                                val itemFsid = Regex("^\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: continue
                                val isDir = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: "0"
                                val filename = Regex("\"server_filename\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1) ?: continue
                                if (isDir == "1") {
                                    val itemPath = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
                                    val subListUrl = "https://$domain/share/list?$listBaseParams&dir=${java.net.URLEncoder.encode(itemPath, "UTF-8")}&root=0&randsk=$randsk"
                                    val subRes = app.get(subListUrl, headers = headers).text
                                    if (!subRes.contains("need verify")) {
                                        val found = findFirstVideo(subRes, depth + 1)
                                        if (found != null) return found
                                    }
                                } else {
                                    val ext = filename.substringAfterLast(".", "").lowercase()
                                    if (videoExtensions.contains(ext)) return itemFsid
                                }
                            }
                            return null
                        }

                        val foundFsid = findFirstVideo(listRes)
                        if (foundFsid != null) {
                            fsid = foundFsid
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            if (fsid.isEmpty()) {
                try { loadExtractor("https://$domain/s/1$surl", "https://$domain/", subtitleCallback, callback) } catch (_: Exception) {}
                return true
            }

            if (sign == null || timestamp == null) {
                val infoUrl = "https://$domain/api/shorturlinfo?app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}&shorturl=1$surl&root=1"
                for (attempt in 1..3) {
                    try {
                        val infoRes = app.get(infoUrl, headers = headers).text
                        if (infoRes.contains("need verify")) continue
                        if (sign == null) sign = Regex("\"sign\":\"(.*?)\"").find(infoRes)?.groupValues?.get(1)
                        if (timestamp == null) timestamp = Regex("\"timestamp\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                        if (uk.isEmpty()) uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(infoRes)?.groupValues?.get(1) ?: ""
                        if (shareid.isEmpty()) shareid = Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(infoRes)?.groupValues?.get(1) ?: ""
                        if (sign != null && timestamp != null) break
                    } catch (_: Exception) {}
                }
            }

            if (sign != null && timestamp != null) {
                val streamTypes = listOf("M3U8_AUTO_480", "M3U8_AUTO_720", "M3U8_AUTO_240", "M3U8_FLV_264_480")
                for (streamType in streamTypes) {
                    val streamUrl = "https://$domain/share/streaming?uk=$uk&shareid=$shareid&type=$streamType&fid=$fsid&sign=$sign&timestamp=$timestamp&app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}"
                    try {
                        val streamRes = app.get(streamUrl, headers = headers).text
                        if (streamRes.contains("need verify") || streamRes.contains("\"errno\":400141")) continue
                        if (streamRes.contains("\"errno\"") && !streamRes.contains("\"errno\":0")) continue

                        if (streamRes.trimStart().startsWith("#EXTM3U")) {
                            callback.invoke(
                                newExtractorLink("Terabox", "Terabox", streamUrl, ExtractorLinkType.M3U8) {
                                    this.headers = headers
                                }
                            )
                            return true
                        }

                        val m3u8Link = Regex("\"lurl\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            ?: Regex("\"mlink\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            ?: Regex("(https?://[^\"]+\\.m3u8[^\"]*)").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (m3u8Link != null) {
                            callback.invoke(
                                newExtractorLink("Terabox", "Terabox", m3u8Link, ExtractorLinkType.M3U8) {
                                    this.headers = headers
                                }
                            )
                            return true
                        }
                    } catch (_: Exception) {}
                }
            }

            try { Terabox().getUrl("https://$domain/s/1$surl", "https://$domain/", subtitleCallback, callback) } catch (_: Exception) {}
        } catch (_: Exception) {
            try { loadExtractor(data, subtitleCallback, callback) } catch (_: Exception) {}
        }

        return true
    }
}
