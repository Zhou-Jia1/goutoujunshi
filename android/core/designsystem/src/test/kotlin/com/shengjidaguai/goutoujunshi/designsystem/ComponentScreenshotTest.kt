package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose captures; record baselines with recordRoborazziDebug, review them, then verify.
 * No baselines are checked in until the Android/Robolectric runtime actually renders them.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentScreenshotTest(private val component: String, private val mode: String) {
    @get:Rule val compose = createComposeRule()

    @Test fun captureComponent() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (mode == "font260") 2f else 1f)) {
                val dark = mode == "dark"
                val calm = mode == "calm"
                val font = if (mode == "font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard
                when (component) {
                    "ContactSwitcher" -> PreviewContactSwitcher(dark, calm, font)
                    "ModelStatusPill" -> PreviewModelStatusPill(dark, calm, font)
                    "UploadHeroButton" -> PreviewUploadHeroButton(dark, calm, font)
                    "QuickSceneChip" -> PreviewQuickSceneChip(dark, calm, font)
                    "ImportPreviewBubble" -> PreviewImportPreviewBubble(dark, calm, font)
                    "SpeakerConfirmBar" -> PreviewSpeakerConfirmBar(dark, calm, font)
                    "RedactionNotice" -> PreviewRedactionNotice(dark, calm, font)
                    "UserMessageBubble" -> PreviewUserMessageBubble(dark, calm, font)
                    "ImportCard" -> PreviewImportCard(dark, calm, font)
                    "CounselAnswerCard" -> PreviewCounselAnswerCard(dark, calm, font)
                    "SceneChips" -> PreviewSceneChips(dark, calm, font)
                    "VerdictLine" -> PreviewVerdictLine(dark, calm, font)
                    "ReplySuggestionCard" -> PreviewReplySuggestionCard(dark, calm, font)
                    "StyleBalanceSlider" -> PreviewStyleBalanceSlider(dark, calm, font)
                    "AnswerActionBar" -> PreviewAnswerActionBar(dark, calm, font)
                    "AlternativesRow" -> PreviewAlternativesRow(dark, calm, font)
                    "CollapsibleSection" -> PreviewCollapsibleSection(dark, calm, font)
                    "EvidenceChip" -> PreviewEvidenceChip(dark, calm, font)
                    "TipCard" -> PreviewTipCard(dark, calm, font)
                    "HabitNotice" -> PreviewHabitNotice(dark, calm, font)
                    "FollowUpQuestionChips" -> PreviewFollowUpQuestionChips(dark, calm, font)
                    "ThinkingIndicator" -> PreviewThinkingIndicator(dark, calm, font)
                    "MemoryChangeNotice" -> PreviewMemoryChangeNotice(dark, calm, font)
                    "SafetyCard" -> PreviewSafetyCard(dark, calm, font)
                    "CalmBanner" -> PreviewCalmBanner(dark, calm, font)
                    "BreathingPrompt" -> PreviewBreathingPrompt(dark, calm, font)
                    "EmotionSlider" -> PreviewEmotionSlider(dark, calm, font)
                    "Composer" -> PreviewComposer(dark, calm, font)
                    "ContactCard" -> PreviewContactCard(dark, calm, font)
                    "TimelineItem" -> PreviewTimelineItem(dark, calm, font)
                    "MemoryEntryRow" -> PreviewMemoryEntryRow(dark, calm, font)
                    "StyleProfileCard" -> PreviewStyleProfileCard(dark, calm, font)
                    "StyleQuizOption" -> PreviewStyleQuizOption(dark, calm, font)
                    "TipBookItem" -> PreviewTipBookItem(dark, calm, font)
                    "PracticeTurn" -> PreviewPracticeTurn(dark, calm, font)
                    "FloatingBall" -> PreviewFloatingBall(dark, calm, font)
                    "FloatingAnswerPanel" -> PreviewFloatingAnswerPanel(dark, calm, font)
                    "FloatingCaptureError" -> PreviewFloatingCaptureError(dark, calm, font)
                    "PermissionStepCard" -> PreviewPermissionStepCard(dark, calm, font)
                    "JunshiTopBar" -> PreviewJunshiTopBar(dark, calm, font)
                    "PrimaryButton" -> PreviewPrimaryButton(dark, calm, font)
                    "SecondaryButton" -> PreviewSecondaryButton(dark, calm, font)
                    "ConsentCard" -> PreviewConsentCard(dark, calm, font)
                    "EmptyState" -> PreviewEmptyState(dark, calm, font)
                    "ErrorState" -> PreviewErrorState(dark, calm, font)
                    "DangerConfirmDialog" -> PreviewDangerConfirmDialog(dark, calm, font)
                    "DemoBadge" -> PreviewDemoBadge(dark, calm, font)
                    "BodyText" -> PreviewBodyText(dark, calm, font)
                    "FormTextField" -> PreviewFormTextField(dark, calm, font)
                    "ChoiceField" -> PreviewChoiceField(dark, calm, font)
                    "ContentDialog" -> PreviewContentDialog(dark, calm, font)

                }
            }
        }
        val target = if (component in setOf("DangerConfirmDialog", "ContentDialog")) compose.onNode(isDialog()) else compose.onRoot()
        target.captureRoboImage("src/test/screenshots/${component}_${mode}.png")
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_{1}")
        fun cases(): List<Array<String>> = listOf(
            "ContactSwitcher",
            "ModelStatusPill",
            "UploadHeroButton",
            "QuickSceneChip",
            "ImportPreviewBubble",
            "SpeakerConfirmBar",
            "RedactionNotice",
            "UserMessageBubble",
            "ImportCard",
            "CounselAnswerCard",
            "SceneChips",
            "VerdictLine",
            "ReplySuggestionCard",
            "StyleBalanceSlider",
            "AnswerActionBar",
            "AlternativesRow",
            "CollapsibleSection",
            "EvidenceChip",
            "TipCard",
            "HabitNotice",
            "FollowUpQuestionChips",
            "ThinkingIndicator",
            "MemoryChangeNotice",
            "SafetyCard",
            "CalmBanner",
            "BreathingPrompt",
            "EmotionSlider",
            "Composer",
            "ContactCard",
            "TimelineItem",
            "MemoryEntryRow",
            "StyleProfileCard",
            "StyleQuizOption",
            "TipBookItem",
            "PracticeTurn",
            "FloatingBall",
            "FloatingAnswerPanel",
            "FloatingCaptureError",
            "PermissionStepCard",
            "JunshiTopBar",
            "PrimaryButton",
            "SecondaryButton",
            "ConsentCard",
            "EmptyState",
            "ErrorState",
            "DangerConfirmDialog",
            "DemoBadge",
            "BodyText", "FormTextField", "ChoiceField", "ContentDialog",
        ).flatMap { component -> listOf("light", "dark", "calm", "font260").map { arrayOf(component, it) } }
    }
}
