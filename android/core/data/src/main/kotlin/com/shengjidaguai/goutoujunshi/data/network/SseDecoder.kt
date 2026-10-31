package com.shengjidaguai.goutoujunshi.data.network

import java.io.EOFException
import okio.Buffer
import okio.BufferedSource

/** SSE accepts LF, CRLF and lone CR. Do not wait for a byte after CR before dispatching. */
internal class SseLineReader(private val source: BufferedSource, private val maxBytes: Long) {
    private var afterCr = false
    fun readLine(): String {
        val buffer = Buffer()
        while (true) {
            if (source.exhausted()) throw EOFException()
            val next = source.readByte().toInt() and 0xff
            if (afterCr) {
                afterCr = false
                if (next == 10) continue
            }
            when (next) {
                13 -> { afterCr = true; return buffer.readUtf8() }
                10 -> return buffer.readUtf8()
                else -> {
                    require(buffer.size < maxBytes) { "SSE line exceeds limit" }
                    buffer.writeByte(next)
                }
            }
        }
    }
}

/** SSE framing independent of TCP chunks. UTF-8 decoding is performed by Okio's buffered source. */
internal class SseDecoder {
    private val data = StringBuilder()
    private var hasData = false
    private var firstLine = true
    fun feedLine(raw: String): String? {
        val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
        firstLine = false
        if (line.isEmpty()) {
            val event = if (hasData) data.toString() else null
            data.clear(); hasData = false
            return event
        }
        if (line.startsWith(':')) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        if (field != "data") return null
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        if (hasData) data.append('\n')
        require(data.length + value.length <= 262_144) { "SSE event exceeds limit" }
        data.append(value); hasData = true
        return null
    }
}
