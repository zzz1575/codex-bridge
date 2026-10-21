package com.y2yy.codex.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ModelOption
import com.y2yy.codex.mobile.model.ReasoningOption
import com.y2yy.codex.mobile.model.ServiceTierOption
import com.y2yy.codex.mobile.model.Session
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native controls and synthetic callbacks only. No preference, model or server mutation. */
@RunWith(AndroidJUnit4::class)
class ServiceTierUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun speedOnlySavePreservesModelAndEffortAndShowsAcceptedSpeed() {
        val ui = mutableStateOf(fixture())
        val chosen = AtomicReference<Triple<String, String, String>>()
        compose.setContent {
            NativeApp(ui.value, actions { model, effort, tier ->
                chosen.set(Triple(model, effort, tier))
                ui.value = ui.value.copy(savingModelSettings = true)
            })
        }
        assertHeaderSpeed("标准")
        compose.onNodeWithContentDescription("选择模型和思考强度").performClick()
        assertSetting("model-selector", "合成速度 Alpha")
        assertSetting("effort-selector", "ultra")
        compose.onNodeWithTag("service-tier-standard").assertIsSelected()
        compose.onNodeWithTag("service-tier-priority").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("快速模式会增加额度消耗").assertIsDisplayed()
        assertSetting("model-selector", "合成速度 Alpha")
        assertSetting("effort-selector", "ultra")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(Triple(ALPHA, "ultra", "priority"), chosen.get()) }
        compose.onNodeWithTag("service-tier-priority").assertIsNotEnabled()
        compose.onNodeWithText("正在保存").assertIsNotEnabled()

        compose.runOnIdle {
            ui.value = ui.value.copy(savingModelSettings = false, composerServiceTier = "priority", preferredServiceTier = "priority")
        }
        compose.onNodeWithTag("service-tier-selector").assertDoesNotExist()
        assertHeaderSpeed("快速")
        // A newly selected session gets its actual tier from state, rather than the
        // previous dialog's transient choice. Data-layer tests cover persistence.
        compose.runOnIdle { ui.value = ui.value.copy(selected = Session("new-speed-fixture", "另一个合成会话", model = ALPHA)) }
        compose.onNodeWithContentDescription("选择模型和思考强度").performClick()
        compose.onNodeWithTag("service-tier-priority").assertIsSelected()
        assertSetting("effort-selector", "ultra")
    }

    @Test fun fastRequiresAdvertisedCapabilityAndModelChangeFallsBackToStandard() {
        val chosen = AtomicReference<Triple<String, String, String>>()
        compose.setContent {
            NativeApp(fixture().copy(composerServiceTier = "priority"), actions { model, effort, tier -> chosen.set(Triple(model, effort, tier)) })
        }
        compose.onNodeWithContentDescription("选择模型和思考强度").performClick()
        compose.onNodeWithTag("service-tier-priority").assertIsSelected()
        compose.onNodeWithTag("model-selector").performClick()
        compose.onNodeWithText("合成速度 Beta").performClick()
        compose.onNodeWithTag("service-tier-standard").performScrollTo().assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag("service-tier-priority").assertIsNotEnabled()
        compose.onNodeWithText("当前模型暂不提供快速模式").assertIsDisplayed()
        assertSetting("effort-selector", "ultra")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(Triple(BETA, "ultra", "standard"), chosen.get()) }
    }

    @Test fun disconnectedReadOnlyAndRejectedSaveDoNotChangeAcceptedSpeedOrLoseSelection() {
        val ui = mutableStateOf(fixture().copy(connected = false))
        val calls = AtomicInteger()
        compose.setContent {
            NativeApp(ui.value, actions { _, _, _ ->
                calls.incrementAndGet()
                ui.value = ui.value.copy(savingModelSettings = true, modelSettingsError = null)
            })
        }
        compose.onNodeWithContentDescription("选择模型和思考强度").performClick()
        compose.onNodeWithTag("service-tier-priority").performScrollTo().performClick()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(connected = true, readOnly = true) }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls.get()); ui.value = ui.value.copy(readOnly = false) }
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.onNodeWithText("正在保存").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(savingModelSettings = false, modelSettingsError = "合成速度设置失败") }
        compose.onNodeWithText("合成速度设置失败").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("service-tier-priority").performScrollTo().assertIsSelected()
        assertHeaderSpeed("标准")
        assertSetting("model-selector", "合成速度 Alpha")
        assertSetting("effort-selector", "ultra")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, calls.get()) }
    }

    @Test fun unresolvedTierStaysUnselectedUntilUserExplicitlyChoosesSpeed() {
        val chosen = AtomicReference<Triple<String, String, String>>()
        compose.setContent {
            NativeApp(fixture().copy(composerServiceTier = "unknown"), actions { model, effort, tier -> chosen.set(Triple(model, effort, tier)) })
        }
        assertHeaderSpeed("未确认")
        compose.onNodeWithContentDescription("选择模型和思考强度").performClick()
        compose.onNodeWithTag("service-tier-standard").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("service-tier-priority").assertIsNotSelected()
        compose.onNodeWithText("当前响应速度未确认，请明确选择后保存").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("service-tier-standard").performClick()
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(Triple(ALPHA, "ultra", "standard"), chosen.get()) }
    }

    @Test fun existingSessionWithUnknownModelNeverSilentlySelectsFirstModel() {
        val chosen = AtomicReference<Triple<String, String, String>>()
        compose.setContent {
            NativeApp(fixture().copy(selected = Session("unknown-model-fixture", "模型未确认"), composerModel = ""), actions { model, effort, tier -> chosen.set(Triple(model, effort, tier)) })
        }
        compose.onNodeWithContentDescription("选择模型和思考强度").performClick()
        assertSetting("model-selector", "请选择模型")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("model-selector").performClick()
        compose.onNodeWithText("合成速度 Alpha").performClick()
        assertSetting("model-selector", "合成速度 Alpha")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(Triple(ALPHA, "ultra", "standard"), chosen.get()) }
    }

    private fun assertHeaderSpeed(value: String) {
        compose.onNodeWithTag("composer-settings-button").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "响应速度：$value")
        )
    }

    private fun assertSetting(tag: String, value: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }

    private fun fixture() = AppUiState(
        loading = false, connected = true, connectionText = "已连接",
        selected = Session("speed-ui-fixture", "速度设置回归", model = ALPHA),
        composerModel = ALPHA, composerEffort = "ultra", composerServiceTier = "standard",
        modelOptions = listOf(
            ModelOption(ALPHA, "合成速度 Alpha", defaultEffort = "high", efforts = listOf(ReasoningOption("high"), ReasoningOption("ultra")), serviceTiers = listOf(ServiceTierOption("standard"), ServiceTierOption("priority"))),
            ModelOption(BETA, "合成速度 Beta", defaultEffort = "high", efforts = listOf(ReasoningOption("high"), ReasoningOption("ultra")), serviceTiers = emptyList())
        )
    )

    private fun actions(onSave: (String, String, String) -> Unit) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {},
        onSaveComposerSettings = onSave
    )

    private companion object {
        const val ALPHA = "fixture-speed-alpha"
        const val BETA = "fixture-speed-beta"
    }
}
