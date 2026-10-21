package com.y2yy.codex.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.PendingInput
import com.y2yy.codex.mobile.model.Session
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic callbacks only: these queue actions never reach a ViewModel or a server. */
@RunWith(AndroidJUnit4::class)
class PendingQueueUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyConfirmedNativeQueueCanBeWithdrawnAndReceiptsKeepTheirRealStatus() {
        val queued = queued()
        val ui = mutableStateOf(fixture().copy(pending = listOf(queued), pendingVerified = true, readOnly = true))
        val withdrawn = CopyOnWriteArrayList<String>()
        compose.setContent {
            NativeApp(ui.value, actions(onCancelPending = { id ->
                withdrawn += id
                ui.value = ui.value.copy(pendingDeletingIds = setOf(id))
            }))
        }
        compose.onNodeWithContentDescription("查看待处理消息").performClick()
        compose.onNodeWithTag("pending-status:${queued.id}").assertTextEquals("排队中")
        compose.onNodeWithContentDescription("撤回排队消息:${queued.id}").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("撤回排队消息:${queued.id}").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(queued.id), withdrawn.toList()) }

        val receipts = listOf(
            queued.copy(status = "accepted") to "已提交 · 等待历史同步",
            queued.copy(status = "dequeued") to "已提交 · 等待历史同步",
            queued.copy(status = "accepted", action = "steer") to "已跟进",
            queued.copy(status = "steered", action = "steer") to "已跟进",
            queued.copy(status = "applied") to "已同步到会话",
            queued.copy(status = "failed", error = "合成发送失败") to "发送失败"
        )
        receipts.forEach { (receipt, label) ->
            compose.runOnIdle { ui.value = ui.value.copy(pending = listOf(receipt), pendingDeletingIds = emptySet()) }
            compose.onNodeWithTag("pending-status:${queued.id}").assertTextEquals(label)
            compose.onNodeWithContentDescription("撤回排队消息:${queued.id}").assertDoesNotExist()
        }
        compose.onNodeWithTag("pending-warning:${queued.id}").assertTextEquals("合成发送失败")

        compose.runOnIdle {
            ui.value = ui.value.copy(pending = listOf(queued.copy(partial = true, startError = "已入队，但启动暂不可用")))
        }
        compose.onNodeWithTag("pending-status:${queued.id}").assertTextEquals("排队中")
        compose.onNodeWithTag("pending-warning:${queued.id}").assertTextEquals("已入队，但启动暂不可用")
        compose.onNodeWithContentDescription("撤回排队消息:${queued.id}").assertIsEnabled()
    }

    @Test fun uncertainSyncKeepsLastKnownEntryWithoutPretendingItWasHandedOff() {
        val queued = queued()
        val ui = mutableStateOf(fixture().copy(pending = listOf(queued), pendingVerified = true))
        val refreshes = AtomicInteger()
        compose.setContent { NativeApp(ui.value, actions(onRefresh = { refreshes.incrementAndGet() })) }
        compose.onNodeWithContentDescription("查看待处理消息").performClick()
        compose.onNodeWithContentDescription("撤回排队消息:${queued.id}").assertIsEnabled()

        compose.runOnIdle { ui.value = ui.value.copy(pendingVerified = false, pendingSyncError = "合成队列读取失败") }
        compose.onNodeWithText(queued.text).assertIsDisplayed()
        compose.onNodeWithTag("pending-status:${queued.id}").assertTextEquals("同步未确认")
        compose.onNodeWithText("队列状态同步未确认").assertIsDisplayed()
        compose.onNodeWithContentDescription("撤回排队消息:${queued.id}").assertDoesNotExist()
        compose.onNodeWithText("重新同步").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshes.get()) }

        compose.runOnIdle { ui.value = ui.value.copy(connected = false) }
        compose.onNodeWithText(queued.text).assertIsDisplayed()
        compose.onNodeWithText("重新同步").assertIsNotEnabled()
        compose.onNodeWithContentDescription("撤回排队消息:${queued.id}").assertDoesNotExist()

        // An empty projection with a read error is still unknown, not proof of an empty queue.
        compose.runOnIdle { ui.value = ui.value.copy(pending = emptyList()) }
        compose.onNodeWithTag("pending-sync-status").assertIsDisplayed()
    }

    @Test fun explicitQueueResetsOnlyAfterOwnAcceptanceAndEveryNewSessionDefaultsToFollowUp() {
        val ui = mutableStateOf(fixture().copy(running = true, activeTurnId = "queue-ui-turn"))
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        compose.setContent { NativeApp(ui.value, actions(onSend = { text, mode -> sent += text to mode })) }
        val input = compose.onNodeWithTag("composer-input")
        val draft = "保留这条合成排队草稿"
        input.performClick().performTextInput(draft)
        compose.onNodeWithContentDescription("发送跟进").assertIsEnabled()
        compose.onNodeWithText("跟进").performClick()
        compose.onNodeWithText("排队").performClick()
        compose.onNodeWithContentDescription("排队发送").performClick()
        compose.runOnIdle { assertEquals(listOf(draft to "queue"), sent.toList()) }

        // Neither an unacknowledged/failed submission nor another thread's receipt changes intent or draft.
        compose.runOnIdle { ui.value = ui.value.copy(sending = true) }
        compose.runOnIdle { ui.value = ui.value.copy(sending = false, error = "合成请求超时，提交未确认") }
        compose.onNodeWithContentDescription("排队发送").assertIsEnabled()
        input.assertTextEquals(draft)
        compose.runOnIdle {
            ui.value = ui.value.copy(error = null, lastSentId = "foreign-ack", lastSentThreadId = "another-thread", lastSentText = draft)
        }
        compose.onNodeWithContentDescription("排队发送").assertIsEnabled()
        input.assertTextEquals(draft)

        compose.runOnIdle {
            ui.value = ui.value.copy(lastSentId = "own-ack", lastSentThreadId = SESSION_ID, lastSentText = draft)
        }
        compose.onNodeWithContentDescription("发送跟进").assertIsNotEnabled()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        input.performClick().performTextInput("下一条默认跟进")
        compose.onNodeWithContentDescription("发送跟进").performClick()
        compose.runOnIdle { assertEquals("下一条默认跟进" to "steer", sent.last()) }

        compose.onNodeWithText("跟进").performClick()
        compose.onNodeWithText("排队").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(selected = Session("second-fixture", "另一个合成会话")) }
        compose.onNodeWithContentDescription("发送跟进").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(selected = Session(SESSION_ID, "队列状态回归")) }
        compose.onNodeWithContentDescription("发送跟进").assertIsEnabled()
        input.assertTextEquals("下一条默认跟进")
        compose.runOnIdle { ui.value = ui.value.copy(running = false, activeTurnId = null) }
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.runOnIdle { assertEquals("下一条默认跟进" to "steer", sent.last()) }
    }

    private fun queued() = PendingInput(
        "queue-ui-input", "尚未执行的合成任务", "queued", "queue",
        threadId = SESSION_ID, source = "app_server_queue", queuedSubmissionId = "queue-ui-submission"
    )

    private fun fixture() = AppUiState(
        loading = false, connected = true, connectionText = "已连接",
        selected = Session(SESSION_ID, "队列状态回归")
    )

    private fun actions(
        onRefresh: () -> Unit = {},
        onSend: (String, String) -> Unit = { _, _ -> },
        onCancelPending: (String) -> Unit = {}
    ) = NativeActions(
        onRefresh = onRefresh, onSelect = {}, onBack = {}, onSend = onSend, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {},
        onCancelPending = onCancelPending
    )

    private companion object { const val SESSION_ID = "queue-ui-fixture" }
}
