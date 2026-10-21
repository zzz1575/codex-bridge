package com.y2yy.codex.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect as PlatformRect
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/**
 * Isolated native layout regression: no MainActivity/ViewModel or server requests.
 * API 30+ injects normal/tall IME insets through the real Compose View. Older Android
 * uses the real IME: its compat listener requests fresh platform insets, which would
 * overwrite a synthetic dispatch. Both branches verify actual layout and draft recovery.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: View
    private lateinit var activity: Activity
    private var clearFocus: () -> Unit = {}
    private var baselineInsets: WindowInsetsCompat? = null
    private var density = 1f
    private var previousFont: Float? = null
    private var previousHardwareImeSetting: String? = null
    private val composeImeBottom = AtomicInteger()
    private var verifiedLayout = false
    private var lastGeometry = "not measured"

    private val preferences by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("native-ui", Context.MODE_PRIVATE)
    }

    @Before fun rememberPreferences() {
        previousFont = if (preferences.contains("chat-font-size")) preferences.getFloat("chat-font-size", 15f) else null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            previousHardwareImeSetting = shell("settings get secure show_ime_with_hard_keyboard").trim()
            shell("settings put secure show_ime_with_hard_keyboard 1")
        }
    }

    @After fun restorePreferencesAndInsets() {
        if (!verifiedLayout && ::host.isInitialized) recordFailure()
        if (::host.isInitialized) compose.runOnIdle {
            clearFocus()
            WindowCompat.getInsetsController(activity.window, host).hide(WindowInsetsCompat.Type.ime())
            baselineInsets?.let { ViewCompat.dispatchApplyWindowInsets(host, it) }
        }
        preferences.edit().apply {
            previousFont?.let { putFloat("chat-font-size", it) } ?: remove("chat-font-size")
        }.commit()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val previous = previousHardwareImeSetting
            if (previous != null && previous.matches(Regex("[0-9]+"))) {
                shell("settings put secure show_ime_with_hard_keyboard $previous")
            } else {
                shell("settings delete secure show_ime_with_hard_keyboard")
            }
        }
    }

    @Test fun composerStaysAboveKeyboardAndDraftSurvivesHide() {
        render(fontScale = 1f, chatFontSp = 15f)
        verifyKeyboardCycle(keyboardFraction = .38f)
        verifiedLayout = true
    }

    @Test fun largeTextKeepsComposerAndChatVisibleWithApiAppropriateKeyboard() {
        render(fontScale = 1.3f, chatFontSp = 21f)
        verifyKeyboardCycle(keyboardFraction = .45f)
        verifiedLayout = true
    }

    private fun render(fontScale: Float, chatFontSp: Float) {
        val testActivity = compose.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = testActivity
            // Match MainActivity's order: legacy WindowInsetsHolder must attach only
            // after edge-to-edge and adjustResize have configured the platform window.
            testActivity.enableEdgeToEdge()
            testActivity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        preferences.edit().putFloat("chat-font-size", chatFontSp).commit()
        val session = Session("keyboard-fixture", "键盘布局测试")
        val state = AppUiState(
            loading = false, connected = true, connectionText = "已连接", selected = session,
            sessions = listOf(session),
            messages = listOf(
                ChatMessage("question", "user", "请检查原生输入区域。"),
                ChatMessage("reply", "assistant", "**检查进度**\n\n- 聊天正常\n- 输入草稿应保留\n\n```kotlin\nval ready = true\n```"),
                ChatMessage("last", "assistant", "最后一条消息：键盘弹出后仍有聊天空间。")
            )
        )
        compose.setContent {
            val context = LocalContext.current
            val view = LocalView.current
            val currentDensity = LocalDensity.current
            val focusManager = LocalFocusManager.current
            val observedImeBottom = WindowInsets.ime.getBottom(currentDensity)
            SideEffect {
                host = view
                activity = context.activity()
                density = currentDensity.density
                clearFocus = { focusManager.clearFocus(force = true) }
                composeImeBottom.set(observedImeBottom)
            }
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, fontScale)) {
                Box(Modifier.fillMaxSize().testTag("keyboard-layout-root")) {
                    NativeApp(state, noOpActions())
                }
            }
        }
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) compose.runOnIdle {
            baselineInsets = WindowInsetsCompat.Builder(ViewCompat.getRootWindowInsets(host) ?: WindowInsetsCompat.CONSUMED)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false)
                .build()
            ViewCompat.dispatchApplyWindowInsets(host, baselineInsets!!)
        }
        compose.waitForIdle()
    }

    private fun verifyKeyboardCycle(keyboardFraction: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            verifyRealKeyboardCycle()
            return
        }
        val input = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        val send = compose.onNodeWithContentDescription("发送消息")
        val draft = "keyboard regression draft"
        input.assertIsDisplayed().performTextInput(draft)
        compose.runOnIdle {
            clearFocus()
            WindowCompat.getInsetsController(activity.window, host).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        setIme(0)

        val root = compose.onNodeWithTag("keyboard-layout-root").fetchSemanticsNode().boundsInRoot
        val originalSend = send.fetchSemanticsNode().boundsInRoot
        val originalInput = input.fetchSemanticsNode().boundsInRoot
        val keyboardPx = (root.height * keyboardFraction).roundToInt()

        setIme(keyboardPx)
        input.assertIsDisplayed().assertTextEquals(draft)
        send.assertIsDisplayed()
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        val sendBounds = send.fetchSemanticsNode().boundsInRoot
        val keyboardTop = root.bottom - keyboardPx
        assertContained("Input must not be clipped", inputBounds, root)
        assertContained("Send button must not be clipped", sendBounds, root)
        assertTrue("Send button must be above keyboard: $sendBounds / top=$keyboardTop", sendBounds.bottom <= keyboardTop + dp(2))
        assertTrue("IME inset must be consumed once; composer cannot float one keyboard-height too high",
            keyboardTop - sendBounds.bottom in -dp(2)..dp(64))
        val topBar = compose.onNodeWithContentDescription("返回会话列表").fetchSemanticsNode().boundsInRoot
        assertTrue("Chat must retain space between app bar and composer", inputBounds.top - topBar.bottom >= dp(72))
        assertTrue("Showing keyboard must move composer upward", sendBounds.bottom < originalSend.bottom - keyboardPx + dp(64))

        setIme(0)
        input.assertIsDisplayed().assertTextEquals(draft)
        send.assertIsDisplayed()
        val restoredSend = send.fetchSemanticsNode().boundsInRoot
        val restoredInput = input.fetchSemanticsNode().boundsInRoot
        assertEquals("Composer must return after keyboard hides", originalSend.bottom, restoredSend.bottom, dp(3))
        assertEquals("Input layout must recover after keyboard hides", originalInput.top, restoredInput.top, dp(3))
    }

    private fun setIme(bottomPx: Int) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Legacy Android must exercise its real IME" }
        compose.runOnIdle {
            val builder = WindowInsetsCompat.Builder(baselineInsets!!)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
            val insets = builder.build()
            if (bottomPx > 0) {
                val platform = requireNotNull(insets.toWindowInsets())
                val delivered = WindowInsetsCompat.toWindowInsetsCompat(platform, host)
                assertEquals("The platform dispatch must actually carry the requested IME height",
                    bottomPx, delivered.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            }
            ViewCompat.dispatchApplyWindowInsets(host, insets)
        }
        compose.waitUntil(5_000) { composeImeBottom.get() == bottomPx }
        compose.waitForIdle()
    }

    private fun verifyRealKeyboardCycle() {
        val input = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        val send = compose.onNodeWithContentDescription("发送消息")
        val draft = "legacy real keyboard regression draft"
        input.assertIsDisplayed().performClick().performTextInput(draft)
        waitForRealImeVisible()
        hideRealIme()
        val originalSend = send.fetchSemanticsNode().boundsInRoot
        val originalInput = input.fetchSemanticsNode().boundsInRoot
        var originalHostTop = 0
        compose.runOnIdle {
            val origin = IntArray(2)
            host.getLocationOnScreen(origin)
            originalHostTop = origin[1]
        }

        input.performClick()
        waitForRealImeVisible()
        val ime = realIme()
        assertTrue("The actual legacy software keyboard must be visible", ime.first)
        assertTrue("The real IME must occupy a meaningful on-screen area", ime.second >= dp(100))
        var hostTop = 0
        var keyboardTopOnScreen = 0
        compose.runOnIdle {
            val origin = IntArray(2)
            host.getLocationOnScreen(origin)
            hostTop = origin[1]
            val visible = PlatformRect()
            host.getWindowVisibleDisplayFrame(visible)
            keyboardTopOnScreen = visible.bottom
        }
        val keyboardTop = (keyboardTopOnScreen - hostTop).toFloat()
        val root = compose.onNodeWithTag("keyboard-layout-root").fetchSemanticsNode().boundsInRoot
        input.assertIsDisplayed().assertTextEquals(draft)
        send.assertIsDisplayed()
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        val sendBounds = send.fetchSemanticsNode().boundsInRoot
        lastGeometry = "sendRoot=$sendBounds, sendWindow=${send.fetchSemanticsNode().boundsInWindow}, " +
            "inputRoot=$inputBounds, inputWindow=${input.fetchSemanticsNode().boundsInWindow}, mainRoot=$root"
        assertContained("Real IME must not clip input", inputBounds, root)
        assertContained("Real IME must not clip send", sendBounds, root)
        assertTrue("Input must be above the actual keyboard in screen coordinates", inputBounds.bottom + hostTop <= keyboardTopOnScreen + dp(2))
        assertTrue("Send must be above the actual keyboard in screen coordinates", sendBounds.bottom + hostTop <= keyboardTopOnScreen + dp(2))
        assertTrue("Real IME must be consumed once", keyboardTop - sendBounds.bottom in -dp(2)..dp(64))
        val topBar = compose.onNodeWithContentDescription("返回会话列表").fetchSemanticsNode().boundsInRoot
        assertTrue("Real IME must leave chat space", inputBounds.top - topBar.bottom >= dp(72))
        assertTrue("A real keyboard must move the composer upward", sendBounds.bottom + hostTop < originalSend.bottom + originalHostTop - dp(100))

        hideRealIme()
        input.assertIsDisplayed().assertTextEquals(draft)
        send.assertIsDisplayed()
        assertEquals("Composer must recover after real IME hides", originalSend.bottom, send.fetchSemanticsNode().boundsInRoot.bottom, dp(3))
        assertEquals("Input must recover after real IME hides", originalInput.top, input.fetchSemanticsNode().boundsInRoot.top, dp(3))
    }

    private fun hideRealIme() {
        compose.runOnIdle {
            clearFocus()
            WindowCompat.getInsetsController(activity.window, host).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) { !realIme().first }
        compose.waitForIdle()
    }

    private fun waitForRealImeVisible() {
        try {
            compose.waitUntil(10_000) {
                val ime = realIme()
                ime.first && ime.second > 0
            }
        } catch (failure: Exception) {
            throw AssertionError("Real IME visibility timed out: ${keyboardDiagnostics()}", failure)
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("keyboardLayoutDiagnostics", keyboardDiagnostics())
        })
    }

    private fun keyboardDiagnostics(): String {
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val insets = ViewCompat.getRootWindowInsets(host)
            val visible = PlatformRect()
            host.getWindowVisibleDisplayFrame(visible)
            val hostScreen = IntArray(2)
            val hostWindow = IntArray(2)
            val rootScreen = IntArray(2)
            host.getLocationOnScreen(hostScreen)
            host.getLocationInWindow(hostWindow)
            host.rootView.getLocationOnScreen(rootScreen)
            result = "api=${Build.VERSION.SDK_INT}, platformVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}, " +
                "platformIme=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}, composeIme=${composeImeBottom.get()}, " +
                "visibleFrame=$visible, decorHeight=${activity.window.decorView.height}, hostHeight=${host.height}, rootHeight=${host.rootView.height}, " +
                "hostScreen=${hostScreen.contentToString()}, hostWindow=${hostWindow.contentToString()}, rootScreen=${rootScreen.contentToString()}, $lastGeometry"
        }
        return result
    }

    private fun recordFailure() {
        // Synthetic local fixture only; preserve the keyboard before cleanup hides it.
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val image = instrumentation.uiAutomation.takeScreenshot()
            val file = File(instrumentation.targetContext.cacheDir, "keyboard-layout-api${Build.VERSION.SDK_INT}-${System.currentTimeMillis()}.png")
            if (image != null) {
                try {
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    image.recycle()
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("keyboardLayoutFailure", keyboardDiagnostics())
                if (file.exists()) putString("keyboardLayoutScreenshot", file.absolutePath)
            })
        }
    }

    private fun realIme(): Pair<Boolean, Int> {
        var result = false to 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val insets = ViewCompat.getRootWindowInsets(host)
            result = (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) to
                (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
        }
        return result
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun assertContained(message: String, rect: Rect, root: Rect) {
        assertTrue(message, rect.height > 0 && rect.width > 0 && rect.top >= root.top - dp(1) && rect.bottom <= root.bottom + dp(1))
    }

    private fun dp(value: Int) = value * density

    private tailrec fun Context.activity(): Activity = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> error("Compose test host must be an Activity")
    }

    private fun noOpActions() = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {}
    )
}
