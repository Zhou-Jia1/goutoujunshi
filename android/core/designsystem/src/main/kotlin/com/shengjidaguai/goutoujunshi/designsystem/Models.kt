package com.shengjidaguai.goutoujunshi.designsystem

enum class ModelStatus { DEMO, LIVE, NOT_CONFIGURED, OFFLINE }
enum class RelationGroup { WORK, INTIMATE, FAMILY, SOCIAL, MEDIATION, DEFAULT }
enum class Side { LEFT, RIGHT }
enum class Formality { LOW, MEDIUM, HIGH }
enum class Tone { NATURAL, GENTLE, DIRECT, FORMAL, WARM, CALM, HUMOROUS }
enum class EvidenceKind { FACT, GUESS, UNKNOWN }
enum class ThinkingStage { LISTENING, SORTING, PLANNING }
enum class FloatingBallState { IDLE, ANALYZING, NEED_AUTH, HINT }
enum class CaptureErrorKind { SECURE_SCREEN, NO_TEXT, AUTH_EXPIRED }
enum class AttachSource { SCREENSHOT, FILE, PASTE }
enum class Illustration { LISTENING, THINKING, CALM, SLEEPING, SHIELD, CONFUSED }
data class ContactChipModel(val id: String?, val label: String)
data class AttachmentModel(val id: String, val label: String)
data class ChatLineModel(val id: String, val text: String, val speaker: String, val me: Boolean, val lowConfidence: Boolean = false)
data class ImportSummaryModel(val label: String, val count: Int)
data class AltReplyModel(val label: String, val reply: String)
data class ContactCardModel(val id: String, val alias: String, val relationLabel: String)
data class TimelineItemModel(val id: String, val text: String, val sourceId: String? = null)
data class MemoryRowModel(val id: String, val value: String, val source: String)
data class TipBookItemModel(val name: String, val explanation: String)
data class UiError(val message: String)
data class AnswerUiState(
    val verdict: String = "", val reply: List<String> = emptyList(), val empathy: String = "", val details: String = "",
    val alternatives: List<AltReplyModel> = emptyList(), val isStreaming: Boolean = false, val calm: Boolean = false,
    val crisis: Boolean = false, val demo: Boolean = false, val relationLabel: String = "", val goalLabel: String = "",
    val group: RelationGroup = RelationGroup.DEFAULT, val formality: Formality = Formality.MEDIUM,
    val tipName: String = "", val tipExplanation: String = "", val styleNote: String? = null, val next: String = "",
)
data class AnswerActions(
    val onCopyOne: (Int) -> Unit = {}, val onCopyAll: () -> Unit = {}, val onChangeTone: (Tone) -> Unit = {},
    val onSent: () -> Unit = {}, val onEditThenSend: () -> Unit = {}, val onNotSent: () -> Unit = {},
    val onEditScene: () -> Unit = {}, val onToggleTip: () -> Unit = {}, val onAlternative: (AltReplyModel) -> Unit = {},
    val onDial: (String) -> Unit = {}, val onTrustedContact: () -> Unit = {}, val onHotlines: () -> Unit = {},
    val onDismissSafe: () -> Unit = {}, val onBreathe: () -> Unit = {},
)
