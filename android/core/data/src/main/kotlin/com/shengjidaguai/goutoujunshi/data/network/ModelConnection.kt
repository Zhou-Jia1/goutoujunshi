package com.shengjidaguai.goutoujunshi.data.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shengjidaguai.goutoujunshi.data.SecretVault
import com.shengjidaguai.goutoujunshi.domain.AnalysisProvider
import com.shengjidaguai.goutoujunshi.domain.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ModelConnection(val baseUrl: String, val model: String, val providerLabel: String) {
    fun completionUrl(allowLocalhostHttpForTests: Boolean = false): HttpUrl {
        require('@' !in baseUrl.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')) { "模型地址不能包含凭据" }
        val url = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("模型地址格式无效")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "模型地址不能包含凭据、查询参数或片段" }
        require(url.isHttps || (allowLocalhostHttpForTests && url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1"))) { "模型地址必须使用 HTTPS" }
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "模型名称无效" }
        val path = url.encodedPath.trimEnd('/')
        return if (path.endsWith("/chat/completions")) url.newBuilder().encodedPath(path).build()
        else url.newBuilder().encodedPath("$path/chat/completions").build()
    }
}

/** The application supplies its existing DataStore. Only non-secret connection fields are stored. */
class ConnectionStore(private val preferences: DataStore<Preferences>) {
    private val base = stringPreferencesKey("model_base_url")
    private val model = stringPreferencesKey("model_name")
    private val label = stringPreferencesKey("model_provider_label")
    val connection: Flow<ModelConnection?> = preferences.data.map { p ->
        p[base]?.let { ModelConnection(it, p[model].orEmpty(), p[label].orEmpty()) }
    }
    suspend fun save(connection: ModelConnection) {
        connection.completionUrl()
        preferences.edit { it[base] = connection.baseUrl; it[model] = connection.model; it[label] = connection.providerLabel }
    }
}

fun interface CredentialSource { suspend fun read(): ByteArray? }

/** A provider can read only the exact record revision and origin it was constructed for. */
class VaultCredentialSource(private val records: ModelRecordStorage, private val expected: SavedModelSettings) : CredentialSource {
    constructor(vault: SecretVault, expected: SavedModelSettings) : this(VaultModelRecordStorage(vault), expected)
    override suspend fun read(): ByteArray? {
        if (expected.revision.isBlank()) return null
        val record = try { records.readRecord() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        if (record.saved != expected || !sameCredentialOrigin(record.saved.connection(), expected.connection())) return null
        return record.key?.toByteArray()
    }
}

/** Reserved contract: no gateway implementation is shipped. */
interface GatewayProvider : AnalysisProvider { override val kind: ProviderKind get() = ProviderKind.GATEWAY }

data class GatewayConfiguration(val baseUrl: String, val model: String)

/** Editable protocol examples; choosing a provider remains an explicit user decision. */
object ModelPresets {
    val openAi = ModelConnection("https://api.openai.com/v1", "gpt-6-luna", "OpenAI")
    val deepSeek = ModelConnection("https://api.deepseek.com", "deepseek-flash", "DeepSeek")
    val qwen = ModelConnection("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "通义千问")
}
