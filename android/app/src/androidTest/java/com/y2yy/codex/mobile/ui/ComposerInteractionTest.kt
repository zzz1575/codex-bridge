package com.y2yy.codex.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real native IME and local callback fixtures only; no messages or images reach a server. */
@RunWith(AndroidJUnit4::class)
class ComposerInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var host: View
    private lateinit var activity: Activity
    private var clearFocus: () -> Unit = {}
    private var originalHardwareImeSetting: String? = null

    @Before fun enableRealSoftKeyboard() {
        // An emulator may expose a hardware keyboard. Keep the actual IME available,
        // restoring this device setting after every test instead of faking IME insets.
        originalHardwareImeSetting = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
    }

    @After fun restoreKeyboard() {
        if (::host.isInitialized) compose.runOnIdle {
            clearFocus()
            WindowCompat.getInsetsController(activity.window, host).hide(WindowInsetsCompat.Type.ime())
        }
        val original = originalHardwareImeSetting
        if (original != null && original.matches(Regex("[0-9]+"))) {
            shell("settings put secure show_ime_with_hard_keyboard $original")
        } else {
            shell("settings delete secure show_ime_with_hard_keyboard")
        }
    }

    @Test fun runningComposerDefaultsToFollowUpAndCanStillQueueManually() {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        render(running = true, onSend = { text, mode -> sent += text to mode })
        val input = compose.onNodeWithTag("composer-input")
        input.performClick().performTextInput("  补充本轮要求  ")
        compose.onNodeWithContentDescription("发送跟进").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("补充本轮要求" to "steer"), sent.toList()) }

        compose.onNodeWithText("跟进").performClick()
        compose.onNodeWithText("排队").performClick()
        compose.onNodeWithContentDescription("排队发送").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("补充本轮要求" to "steer", "补充本轮要求" to "queue"), sent.toList())
        }
    }

    @Test fun idleComposerAlsoAsksServerToSteerBeforeDecidingToStartATurn() {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        render(running = false, onSend = { text, mode -> sent += text to mode })
        compose.onNodeWithTag("composer-input").performClick().performTextInput("  合成的空闲状态消息  ")
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("合成的空闲状态消息" to "steer"), sent.toList()) }
    }

    @Test fun imagePreviewDismissesRealKeyboardAndDoesNotRestoreItOrLoseDraft() {
        render(image = redImageDataUrl())
        waitForRedImage()
        val draft = "查看图片前保留的草稿"
        val input = compose.onNodeWithTag("composer-input")
        input.performClick().performTextInput(draft)
        waitForIme(visible = true)

        compose.onNodeWithContentDescription("消息图片").performClick()
        compose.onNodeWithContentDescription("关闭图片预览").assertIsDisplayed()
        waitForIme(visible = false)
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
        input.assertIsDisplayed().assertIsNotFocused().assertTextEquals(draft)
        assertKeyboardStaysHidden()

        input.performClick()
        waitForIme(visible = true)
        input.assertTextEquals(draft)
    }

    @Test fun cancellingGalleryLeavesRealKeyboardHiddenAndDraftUntouched() {
        val registry = GalleryRegistry()
        val attachments = AtomicInteger()
        render(registry = registry, onAttach = { attachments.addAndGet(it) })
        val draft = "选择相册前的草稿"
        val input = compose.onNodeWithTag("composer-input")
        input.performClick().performTextInput(draft)
        waitForIme(visible = true)

        compose.onNodeWithContentDescription("从相册添加图片").performClick()
        compose.waitUntil(5_000) { registry.launchedRequest.get() != -1 }
        waitForIme(visible = false)
        input.assertIsNotFocused().assertTextEquals(draft)
        compose.runOnIdle {
            // Exercise the real ActivityResult callback, without depending on a
            // device vendor's external PhotoPicker or touching personal media.
            assertTrue(registry.dispatchResult(registry.launchedRequest.get(), Activity.RESULT_CANCELED, null))
        }
        input.assertIsNotFocused().assertTextEquals(draft)
        assertKeyboardStaysHidden()
        assertEquals("Cancel must not submit an attachment", 0, attachments.get())

        input.performClick()
        waitForIme(visible = true)
        input.assertTextEquals(draft)
    }

    private fun render(
        running: Boolean = false,
        image: String? = null,
        registry: GalleryRegistry = GalleryRegistry(),
        onSend: (String, String) -> Unit = { _, _ -> },
        onAttach: (Int) -> Unit = {}
    ) {
        val session = Session("composer-interaction-fixture", "输入交互回归")
        val state = AppUiState(
            loading = false, connected = true, connectionText = "已连接", selected = session,
            sessions = listOf(session), running = running, darkTheme = true,
            messages = image?.let { listOf(ChatMessage("fixture-image", "user", "合成图片", images = listOf(it))) }.orEmpty()
        )
        compose.setContent {
            val context = LocalContext.current
            val view = LocalView.current
            val focus = LocalFocusManager.current
            SideEffect {
                host = view
                activity = context.activity()
                clearFocus = { focus.clearFocus(force = true) }
            }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                NativeApp(state, NativeActions(
                    onRefresh = {}, onSelect = {}, onBack = {}, onSend = onSend, onStop = {},
                    onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = { onAttach(it.size) },
                    onRemoveAttachment = {}, onOlder = {}, onClearError = {}, onTheme = {},
                    onClearCache = {}, onTool = {}
                ))
            }
        }
        compose.runOnIdle {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.waitForIdle()
    }

    private fun waitForIme(visible: Boolean) {
        compose.waitUntil(10_000) { imeVisible() == visible }
        assertEquals("Actual window IME visibility", visible, imeVisible())
    }

    private fun imeVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = ViewCompat.getRootWindowInsets(host)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun assertKeyboardStaysHidden() {
        val deadline = SystemClock.uptimeMillis() + 1_000
        while (SystemClock.uptimeMillis() < deadline) {
            assertFalse("Keyboard must stay hidden until the user taps the input again", imeVisible())
            SystemClock.sleep(50)
        }
    }

    private fun waitForRedImage() {
        compose.waitUntil(15_000) {
            if (compose.onAllNodesWithContentDescription("消息图片").fetchSemanticsNodes().isEmpty()) false
            else {
                val pixels = compose.onNodeWithContentDescription("消息图片").captureToImage().toPixelMap()
                pixels.width > 0 && pixels.height > 0 && pixels[pixels.width / 2, pixels.height / 2].let {
                    it.red > .8f && it.green < .2f && it.blue < .2f && it.alpha > .9f
                }
            }
        }
    }

    private fun redImageDataUrl(): String {
        // Wide synthetic image remains tappable above a real software keyboard.
        val bitmap = Bitmap.createBitmap(240, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.RED) }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private tailrec fun Context.activity(): Activity = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> error("Compose test host must be an Activity")
    }

    private class GalleryRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        val launchedRequest = AtomicInteger(-1)
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) { launchedRequest.set(requestCode) }
    }
}
