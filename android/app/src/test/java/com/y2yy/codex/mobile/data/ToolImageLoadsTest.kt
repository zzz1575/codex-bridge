package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.ToolCall
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class ToolImageLoadsTest {
    private val host = ChatMessage(
        "message-a", "assistant", "Fixture explanation",
        images = listOf("https://example.com/fixture.png"),
        tools = listOf(
            ToolCall("image-a", "fixture.render", "completed", hasImage = true),
            ToolCall("image-b", "fixture.render", "completed", hasImage = true)
        ),
        turnId = "turn-a"
    )

    @Test fun oneSuccessfulImageCannotHideAnotherFailedImageOrRemoveSuccessfulContent() {
        val result = withToolImageLoads(host, mapOf(
            "image-a" to ToolImageLoadStatus.LOADED,
            "image-b" to ToolImageLoadStatus.FAILED
        ))
        assertFalse(result.imagePending)
        assertEquals("部分图片加载失败，请重试", result.imageError)
        assertEquals(host.images, result.images)
        assertEquals(host.tools, result.tools)
        assertEquals(host.text, result.text)
        assertEquals(host.turnId, result.turnId)
    }

    @Test fun parallelPendingAndFailedRequestsShowBothStatesUntilTheirOwnRequestsFinish() {
        val pending = withToolImageLoads(host, mapOf(
            "image-a" to ToolImageLoadStatus.LOADING,
            "image-b" to ToolImageLoadStatus.FAILED
        ))
        assertTrue(pending.imagePending)
        assertNotNull(pending.imageError)
        val retrying = withToolImageLoads(pending, mapOf(
            "image-a" to ToolImageLoadStatus.LOADED,
            "image-b" to ToolImageLoadStatus.LOADING
        ))
        assertTrue(retrying.imagePending)
        assertNull(retrying.imageError)
        val completed = withToolImageLoads(retrying, mapOf(
            "image-a" to ToolImageLoadStatus.LOADED,
            "image-b" to ToolImageLoadStatus.LOADED
        ))
        assertFalse(completed.imagePending)
        assertNull(completed.imageError)
        assertEquals(host.images, completed.images)
    }

    @Test fun reconciledToolKeepsSdkAliasRequestStateAndNeverDependsOnToolOrder() {
        val merged = host.copy(tools = listOf(
            host.tools.first().copy(id = "history-image", aliases = setOf("sdk-image")),
            host.tools.last()
        ))
        val loads = mapOf("sdk-image" to ToolImageLoadStatus.FAILED, "image-b" to ToolImageLoadStatus.LOADING)
        val result = withToolImageLoads(merged, loads)
        assertTrue(result.imagePending)
        assertNotNull(result.imageError)
        assertEquals(result, withToolImageLoads(merged.copy(tools = merged.tools.reversed()), loads).copy(tools = merged.tools))
    }

    @Test fun unrelatedRequestCompletionCannotChangeContextImageOrAnotherHostsError() {
        val contextImage = ChatMessage("user-image", "user", "", imagePending = true)
        val loadedUnrelated = mapOf("another-message-tool" to ToolImageLoadStatus.LOADED)
        assertSame(contextImage, withToolImageLoads(contextImage, loadedUnrelated))
        val failedHost = host.copy(imagePending = false, imageError = "Existing fixture failure")
        assertSame(failedHost, withToolImageLoads(failedHost, loadedUnrelated))
        assertSame(host, withToolImageLoads(host, emptyMap()))
    }

    @Test fun emptyToolIdentitiesCannotAccidentallyClaimAnUnrelatedRequest() {
        val noIdentity = host.copy(tools = listOf(ToolCall("", "fixture", "completed", aliases = setOf(" "))), imagePending = true)
        assertSame(noIdentity, withToolImageLoads(noIdentity, mapOf("" to ToolImageLoadStatus.LOADED, " " to ToolImageLoadStatus.FAILED)))
    }

    @Test fun successfulRetryAfterDurableIdReconciliationClearsTheOldSdkFailure() {
        val loads = mutableMapOf("sdk-image" to ToolImageLoadStatus.FAILED, "image-b" to ToolImageLoadStatus.LOADED)
        val merged = host.copy(tools = listOf(
            host.tools.first().copy(id = "history-image", aliases = setOf("sdk-image")),
            host.tools.last()
        ))
        assertNotNull(withToolImageLoads(merged, loads).imageError)
        val identities = setOf("history-image", "sdk-image")
        markToolImageLoad(loads, identities, ToolImageLoadStatus.LOADING)
        val retrying = withToolImageLoads(merged, loads)
        assertTrue(retrying.imagePending)
        assertNull(retrying.imageError)
        markToolImageLoad(loads, identities, ToolImageLoadStatus.LOADED)
        val result = withToolImageLoads(retrying, loads)
        assertFalse(result.imagePending)
        assertNull(result.imageError)
        assertEquals(ToolImageLoadStatus.LOADED, loads["sdk-image"])
        assertEquals(ToolImageLoadStatus.LOADED, loads["history-image"])
        assertEquals(host.images, result.images)
    }

    @Test fun durablePrimaryAndSdkAliasShareTheExistingMutexButOtherRequestsStayIndependent() {
        val locks = mutableMapOf<String, Mutex>()
        val sdkLock = toolImageLoadLock(locks, setOf("session-1:sdk-image"))
        assertTrue(sdkLock.tryLock())
        try {
            val durableLock = toolImageLoadLock(locks, setOf("session-1:history-image", "session-1:sdk-image"))
            assertSame(sdkLock, durableLock)
            assertFalse(durableLock.tryLock())
            assertSame(sdkLock, toolImageLoadLock(locks, setOf("session-1:history-image")))
            assertNotSame(sdkLock, toolImageLoadLock(locks, setOf("session-1:other-image")))
            assertNotSame(sdkLock, toolImageLoadLock(locks, setOf("session-2:sdk-image")))
        } finally { sdkLock.unlock() }
    }
}
