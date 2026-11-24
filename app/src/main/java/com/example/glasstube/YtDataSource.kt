package com.example.glasstube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads YouTube media the way YouTube's own clients do (based on NewPipe's YoutubeHttpDataSource):
 *  - `videoplayback` URLs get the byte range as a `range` URL parameter instead of a header and a
 *    request counter (`rn`), and are fetched with a small POST body,
 *  - the user agent matches the client the stream URL was made for.
 * Without these YouTube throttles or refuses (HTTP 403) the requests.
 */
@OptIn(UnstableApi::class)
class YtDataSource : BaseDataSource(true) {

    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var dataSpec: DataSpec? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L
    private var requestNumber = 0L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        val position = dataSpec.position
        val length = dataSpec.length
        val playback = dataSpec.uri.path?.startsWith("/videoplayback") == true
        var url = dataSpec.uri.toString()
        if (playback) {
            if (!url.contains("&rn=")) {
                url += "&rn=$requestNumber"
                requestNumber++
            }
            if (!(position == 0L && length == C.LENGTH_UNSET.toLong())) {
                url += "&range=$position-" + if (length != C.LENGTH_UNSET.toLong()) (position + length - 1).toString() else ""
            }
        }

        var target = URL(url)
        var redirects = 0
        var conn: HttpURLConnection
        val code: Int
        try {
            while (true) {
                conn = connect(target, playback, position, length)
                val c = conn.responseCode
                if (c in 300..399 && c != 304) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location == null || ++redirects > MAX_REDIRECTS) {
                        throw HttpDataSource.HttpDataSourceException(
                            "Too many redirects", dataSpec,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            HttpDataSource.HttpDataSourceException.TYPE_OPEN
                        )
                    }
                    target = URL(target, location)
                    continue
                }
                code = c
                break
            }
        } catch (e: HttpDataSource.HttpDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        connection = conn

        if (code !in 200..299) {
            val headers = headerMap(conn)
            val body = try {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            } catch (e: IOException) {
                ByteArray(0)
            }
            closeConnection()
            throw HttpDataSource.InvalidResponseCodeException(
                code, conn.responseMessage, null, headers, dataSpec, body
            )
        }

        // With a Range header a server may ignore the range and send everything (200): skip ahead.
        val skip = if (!playback && code == 200 && position != 0L) position else 0L
        val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        bytesToRead = when {
            length != C.LENGTH_UNSET.toLong() -> length
            contentLength >= 0 -> contentLength - skip
            else -> C.LENGTH_UNSET.toLong()
        }

        try {
            val stream = conn.inputStream
            input = stream
            if (skip > 0) skipFully(stream, skip)
        } catch (e: IOException) {
            closeConnection()
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    private fun connect(url: URL, playback: Boolean, position: Long, length: Long): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = false
        val s = url.toString()
        val agent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(s)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NetDownloader.USER_AGENT
        }
        conn.setRequestProperty("User-Agent", agent)
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (YoutubeParsingHelper.isWebStreamingUrl(s)) {
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.setRequestProperty("Referer", "https://www.youtube.com")
            conn.setRequestProperty("Sec-Fetch-Dest", "empty")
            conn.setRequestProperty("Sec-Fetch-Mode", "cors")
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site")
        }
        if (playback) {
            // YouTube's clients fetch media with POST
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(POST_BODY.size)
            conn.connect()
            conn.outputStream.use { it.write(POST_BODY) }
        } else {
            if (!(position == 0L && length == C.LENGTH_UNSET.toLong())) {
                val end = if (length != C.LENGTH_UNSET.toLong()) (position + length - 1).toString() else ""
                conn.setRequestProperty("Range", "bytes=$position-$end")
            }
            conn.connect()
        }
        return conn
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val stream = input ?: return C.RESULT_END_OF_INPUT
        var toRead = length
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            toRead = minOf(toRead.toLong(), remaining).toInt()
        }
        val n = try {
            stream.read(buffer, offset, toRead)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, dataSpec ?: DataSpec(Uri.EMPTY), HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesRead += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = connection?.url?.let { Uri.parse(it.toString()) } ?: dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        connection?.let { headerMap(it) } ?: emptyMap()

    override fun close() {
        try {
            input?.close()
        } catch (e: IOException) {
            // nothing to do
        }
        input = null
        closeConnection()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeConnection() {
        connection?.disconnect()
        connection = null
    }

    private fun headerMap(conn: HttpURLConnection): Map<String, List<String>> =
        conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }

    private fun skipFully(stream: InputStream, count: Long) {
        val scratch = ByteArray(16 * 1024)
        var left = count
        while (left > 0) {
            val n = stream.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n == -1) throw IOException("Unexpected end of stream while skipping")
            left -= n
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = YtDataSource()
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val MAX_REDIRECTS = 5
        private val POST_BODY = byteArrayOf(0x78, 0)
    }
}
