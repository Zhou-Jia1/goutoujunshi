package com.shengjidaguai.goutoujunshi.importing

import com.shengjidaguai.goutoujunshi.domain.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
const val MAX_IMPORT_ROWS = 2000

data class ImportRow(val name: String = "", val text: String, val sentAt: String? = null, val speaker: Speaker? = null,
    val id: String = java.util.UUID.randomUUID().toString(), val imageId: String? = null,
    val side: com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide? = null, val ocrConfidence: Float? = null,
    val excluded: Boolean = false, val reviewed: Boolean = false, val exclusionOverride: Boolean? = null)
data class ParsedImport(val rows: List<ImportRow>, val unknownJson: Boolean = false)

/** Strict UTF-8 and bounded streams: no file copies, charset guessing or silent replacement. */
object ChatImportParser {
    fun read(stream: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size, MAX_IMPORT_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_IMPORT_BYTES) { "IMPORT_TOO_LARGE" }
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_IMPORT_BYTES) { "IMPORT_TOO_LARGE" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }
    fun parse(raw: String, checkCancelled: () -> Unit = {}): ParsedImport {
        checkCancelled()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "IMPORT_TOO_LARGE" }
        val text = raw.removePrefix("\uFEFF").trim()
        require(text.isNotBlank()) { "IMPORT_EMPTY" }
        if (text.startsWith("{") || text.startsWith("[")) {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            val obj = root as? JsonObject
            if ((obj?.get("format") as? JsonPrimitive)?.takeIf { it.isString }?.content != "junshi-chat")
                return ParsedImport(listOf(ImportRow(text = text)), true)
            val version = obj["version"]
            require(version is JsonPrimitive && !version.isString && version.intOrNull != null) { "IMPORT_FORMAT" }
            if (version.intOrNull != 1) return ParsedImport(listOf(ImportRow(text = text)), true)
            val messages = obj["messages"] as? JsonArray ?: error("IMPORT_FORMAT")
            require(messages.size in 1..MAX_IMPORT_ROWS) { "IMPORT_TOO_LARGE" }
            return ParsedImport(messages.map { item ->
                checkCancelled()
                val m = item as? JsonObject ?: error("IMPORT_FORMAT")
                ImportRow(optionalString(m, "name").orEmpty(),
                    (m["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: error("IMPORT_FORMAT"),
                    optionalString(m, "sentAt"))
            })
        }
        val rows = mutableListOf<ImportRow>()
        val header = Regex("^(?:(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)\\s+)?([^:：\\n]{1,80})[:：]\\s?(.*)$")
        val timed = Regex("^(.{1,80}?)\\s+(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)$")
        var current: ImportRow? = null
        val body = StringBuilder()
        var messageCount = 0
        fun finish() {
            current?.let { row -> if (body.isNotBlank()) rows += row.copy(text = body.toString()) }
            body.setLength(0)
        }
        fun start(row: ImportRow) {
            finish()
            require(++messageCount <= MAX_IMPORT_ROWS) { "IMPORT_TOO_LARGE" }
            current = row
            body.append(row.text)
        }
        text.lineSequence().forEach { line ->
            checkCancelled()
            val timestamp = timed.matchEntire(line)
            val match = header.matchEntire(line)
            when {
                timestamp != null -> start(ImportRow(timestamp.groupValues[1], "", timestamp.groupValues[2]))
                match != null -> start(ImportRow(match.groupValues[2].trim(), match.groupValues[3], match.groupValues[1].ifBlank { null }))
                current != null -> {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(line)
                }
                line.isNotBlank() -> start(ImportRow(text = line))
            }
        }
        finish()
        return ParsedImport(rows)
    }
    private fun optionalString(obj: JsonObject, key: String): String? {
        val value = obj[key] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "IMPORT_FORMAT" }
        return value.content
    }
    /** Content identity excludes random IDs, source and timestamp of importing. */
    fun hash(rows: List<ImportRow>): String {
        val canonical = buildJsonArray { rows.forEach { row -> add(buildJsonObject {
            put("name", row.name); put("text", row.text); put("at", row.sentAt); put("speaker", row.speaker?.name)
        }) } }.toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
