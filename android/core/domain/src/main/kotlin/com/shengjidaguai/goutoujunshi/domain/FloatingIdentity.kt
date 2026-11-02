package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable

/** User-asserted context, never inferred from nickname or foreground-app permissions. */
@Serializable data class FloatingSourceContext(val app: String, val account: String, val version: Long = 1) {
    init { require(app.isNotBlank() && account.isNotBlank() && version > 0) }
}
/** Separate identity binding; NicknameMapping remains solely a redaction record. */
@Serializable data class FloatingIdentityBinding(
    val id: String, val contactId: String, val source: FloatingSourceContext,
    val observedNickname: String, val rightIsMe: Boolean,
    val contactRevision: Long, val revision: Long = 1,
) {
    init { require(id.isNotBlank() && contactId.isNotBlank() && revision > 0 && contactRevision >= 0) }
}
