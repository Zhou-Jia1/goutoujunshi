package com.shengjidaguai.goutoujunshi.data.network

import com.shengjidaguai.goutoujunshi.data.SecretVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable data class SavedModelSettings(val live: Boolean = false, val baseUrl: String = "", val model: String = "", val label: String = "", val revision: String = "") {
    fun connection() = ModelConnection(baseUrl, model, label)
}
data class ModelSettingsSnapshot(val saved: SavedModelSettings = SavedModelSettings(), val maskedKey: String? = null)

interface ModelSettingsRepository {
    suspend fun load(): ModelSettingsSnapshot
    suspend fun save(settings: SavedModelSettings, newKey: String?, removeKey: Boolean): ModelSettingsSnapshot
    suspend fun clear()
}

/** Bytes cross this boundary only in RAM; production writes must be authenticated ciphertext. */
interface ModelRecordStorage {
    suspend fun read(): ByteArray?
    suspend fun write(record: ByteArray)
    suspend fun clear()
}

/** Shares the existing application vault and its full-clear lifecycle. */
class VaultModelRecordStorage(private val vault: SecretVault) : ModelRecordStorage {
    override suspend fun read() = vault.read("model_settings")
    override suspend fun write(record: ByteArray) = vault.write("model_settings", record)
    override suspend fun clear() { vault.remove("model_settings"); vault.remove("model_api_key") }
}

@Serializable internal data class ModelSettingsRecord(val saved: SavedModelSettings = SavedModelSettings(), val key: String? = null) {
    fun snapshot() = ModelSettingsSnapshot(saved, key?.let { if (it.length >= 8) "••••" + it.takeLast(4) else "••••" })
    override fun toString() = "ModelSettingsRecord(redacted)"
}
internal val modelRecordJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal suspend fun ModelRecordStorage.readRecord(): ModelSettingsRecord = read()?.let { bytes ->
    try { modelRecordJson.decodeFromString(ModelSettingsRecord.serializer(), bytes.toString(Charsets.UTF_8)) }
    finally { bytes.fill(0) }
} ?: ModelSettingsRecord()

/** Settings, key and an unpredictable revision form ONE encrypted authenticated record. */
class VaultModelSettingsRepository(private val records: ModelRecordStorage) : ModelSettingsRepository {
    constructor(vault: SecretVault) : this(VaultModelRecordStorage(vault))
    override suspend fun load() = records.readRecord().snapshot()
    override suspend fun save(settings: SavedModelSettings, newKey: String?, removeKey: Boolean): ModelSettingsSnapshot {
        settings.connection().completionUrl()
        val old = records.readRecord()
        val key = when {
            removeKey -> null
            !newKey.isNullOrBlank() -> newKey
            sameCredentialOrigin(old.saved.connection(), settings.connection()) -> old.key
            else -> null
        }
        val record = ModelSettingsRecord(settings.copy(revision = UUID.randomUUID().toString()), key)
        val bytes = modelRecordJson.encodeToString(ModelSettingsRecord.serializer(), record).toByteArray()
        try { records.write(bytes) } finally { bytes.fill(0) }
        // A commit may succeed but its acknowledgement/readback may fail. Credential readers
        // independently verify revision, so a retained old provider cannot read this new key.
        return load()
    }
    override suspend fun clear() = records.clear()
}

fun ModelConnection.safeDestination(): String = completionUrl().let { "${it.scheme}://${it.host}:${it.port} · $model" }
fun sameCredentialOrigin(previous: ModelConnection, next: ModelConnection): Boolean = runCatching {
    val a = previous.completionUrl(); val b = next.completionUrl()
    a.scheme == b.scheme && a.host == b.host && a.port == b.port
}.getOrDefault(false)
