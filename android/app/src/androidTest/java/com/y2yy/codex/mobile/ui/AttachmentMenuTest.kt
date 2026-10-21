package com.y2yy.codex.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.Session
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local native attachment controls only; never opens a real session or sends a message. */
@RunWith(AndroidJUnit4::class)
class AttachmentMenuTest {
    @get:Rule val compose = createComposeRule()
    private var clipboard: ClipboardManager? = null
    private var originalClip: ClipData? = null
    private var clipboardChanged = false

    @After fun restoreClipboard() {
        if (clipboardChanged) compose.runOnIdle {
            originalClip?.let { clipboard?.setPrimaryClip(it) } ?: clipboard?.clearPrimaryClip()
        }
    }

    @Test fun attachmentMenuMakesAllThreeSourcesVisible() {
        render()
        compose.onNodeWithContentDescription("添加图片或粘贴").performClick()
        compose.onNodeWithText("拍照").assertIsDisplayed()
        compose.onNodeWithText("相册/文件").assertIsDisplayed()
        compose.onNodeWithText("粘贴图片或文字").assertIsDisplayed()
    }

    @Test fun explicitClipboardPasteAppendsTextAndKeepsExistingDraft() {
        render()
        val input = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        input.performTextInput("已有草稿")
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            clipboard = context.getSystemService(ClipboardManager::class.java)
            originalClip = clipboard?.primaryClip
            clipboard?.setPrimaryClip(ClipData.newPlainText("local-test-fixture", "新增文字"))
            clipboardChanged = true
        }
        compose.onNodeWithContentDescription("添加图片或粘贴").performClick()
        compose.onNodeWithText("粘贴图片或文字").performClick()
        val expected = "已有草稿\n新增文字"
        compose.waitUntil(5_000) {
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == expected
        }
        input.assertTextEquals(expected)
    }

    private fun render() {
        val state = AppUiState(loading = false, connected = true, connectionText = "已连接", selected = Session("attachment-fixture", "附件入口测试"))
        compose.setContent { NativeApp(state, NativeActions(
            onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
            onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
            onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {}
        )) }
    }
}
