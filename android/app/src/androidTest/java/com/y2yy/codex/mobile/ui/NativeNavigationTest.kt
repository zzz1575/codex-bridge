package com.y2yy.codex.mobile.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only live smoke test: visits native pages without sending or modifying server data. */
@RunWith(AndroidJUnit4::class)
class NativeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun autoConnectsAndNavigatesNativePages() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("已连接", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithContentDescription("返回会话列表").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("返回会话列表").performClick()
        }

        compose.onNodeWithText("搜索会话或项目").assertIsDisplayed()
        compose.onNode(hasText("任务") and hasClickAction()).performClick()
        compose.onNodeWithText("需要处理").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("正在执行").performScrollTo().assertIsDisplayed()

        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithText("Y2YY Codex").assertIsDisplayed()
        compose.onNodeWithText("深色模式").performScrollTo().assertIsDisplayed()
        val toggle = compose.onNode(isToggleable())
        val original = toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        try {
            toggle.performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] != original
            }
        } finally {
            if (toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] != original) {
                toggle.performClick()
                compose.waitUntil(timeoutMillis = 5_000) {
                    toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == original
                }
            }
        }

        compose.onNodeWithText("聊天字号").performScrollTo().assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertExists()
        compose.onNode(hasText("会话") and hasClickAction()).performClick()
        compose.onNodeWithText("搜索会话或项目").assertIsDisplayed()
    }
}
