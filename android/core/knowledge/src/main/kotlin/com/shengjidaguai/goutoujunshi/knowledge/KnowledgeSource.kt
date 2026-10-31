package com.shengjidaguai.goutoujunshi.knowledge

import android.content.res.AssetManager
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun interface KnowledgeSource { fun readBytes(path: String): ByteArray }

class AssetKnowledgeSource(private val assets: AssetManager) : KnowledgeSource {
    override fun readBytes(path: String): ByteArray {
        requireSafeAssetPath(path)
        return assets.open(path).use { it.readBytes() }
    }
}

internal fun requireSafeAssetPath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':'))
    require(path.split('/').none { it == ".." || it == "." || it.isBlank() })
}

internal fun String.lf() = replace("\r\n", "\n").replace("\r", "\n")
internal fun String.codePointLength() = codePointCount(0, length)
internal fun String.limitCodePoints(max: Int): String = if (codePointLength() <= max) this else substring(0, offsetByCodePoints(0, max))
internal fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

@Serializable internal data class ManifestEntry(val path: String, val sha256: String, val characters: Int)
@Serializable internal data class KnowledgeManifest(val version: Int, val knowledgeVersion: String, val entries: List<ManifestEntry>)

/** Reads only manifest-listed canonical documents; corrupt or missing knowledge fails closed. */
class KnowledgeLibrary(private val source: KnowledgeSource) {
    private val manifest = Json.decodeFromString<KnowledgeManifest>(source.readBytes("knowledge/knowledge-manifest.json").toString(Charsets.UTF_8))
    private val entries = manifest.entries.associateBy { it.path }
    val version: String get() = manifest.knowledgeVersion

    init {
        require(manifest.version == 1 && entries.size == manifest.entries.size)
        require(entries.containsKey("SKILL.md"))
        val fingerprint = manifest.entries.sortedBy { it.path }.joinToString("\n") { "${it.path}:${it.sha256}" }
        require(fingerprint.toByteArray(Charsets.UTF_8).sha256() == version) { "Knowledge manifest mismatch" }
    }

    fun document(path: String): String {
        requireSafeAssetPath(path)
        require(path == "SKILL.md" || path.startsWith("references/"))
        val entry = requireNotNull(entries[path]) { "Document not in canonical manifest" }
        val bytes = source.readBytes("knowledge/$path")
        require(bytes.sha256() == entry.sha256) { "Knowledge document integrity mismatch" }
        val text = bytes.toString(Charsets.UTF_8).lf()
        require(text.codePointLength() == entry.characters)
        return text
    }

    fun reference(path: String): com.shengjidaguai.goutoujunshi.domain.KnowledgeReference {
        document(path)
        val item = entries.getValue(path)
        return com.shengjidaguai.goutoujunshi.domain.KnowledgeReference(item.path, item.sha256, item.characters)
    }

    fun appAsset(path: String): String {
        requireSafeAssetPath(path)
        require(path.startsWith("app-prompt/"))
        return source.readBytes(path).toString(Charsets.UTF_8).lf()
    }
}
