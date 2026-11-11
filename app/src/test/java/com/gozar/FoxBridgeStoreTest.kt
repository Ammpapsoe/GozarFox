package com.gozar

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxBridgeStoreTest {
    private lateinit var context: Context
    private lateinit var originalFetcher: (String, Int) -> ByteArray?
    private lateinit var originalVerifier: (String, String) -> List<FoxBridge.Candidate>?
    private val bridge = "obfs4 8.8.8.8:443 ${"A".repeat(40)} cert=${"A".repeat(70)} iat-mode=0"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        originalFetcher = FoxBridgeStore.fetcher
        originalVerifier = FoxBridgeStore.verifier
        FoxBridgeStore.clear()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        FoxBridgeStore.fetcher = originalFetcher
        FoxBridgeStore.verifier = originalVerifier
        FoxBridgeStore.clear()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun manualBridgeExistsOnlyInMemory() {
        FoxBridgeStore.manual = FoxBridge.manual(bridge)!!.line
        assertEquals(bridge, FoxBridgeStore.manualCandidate()?.line)
        assertTrue(context.getSharedPreferences("fox", Context.MODE_PRIVATE).all.isEmpty())
        FoxBridgeStore.clear()
        assertTrue(FoxBridgeStore.manual.isEmpty())
    }

    @Test
    fun verifiedFeedIsNotWrittenToDisk() {
        val requested = mutableListOf<String>()
        FoxBridgeStore.fetcher = { url, _ ->
            requested += url
            if (url.endsWith(".sig")) "signature".toByteArray() else "json".toByteArray()
        }
        FoxBridgeStore.verifier = { json, signature ->
            assertEquals("json", json)
            assertEquals("signature", signature)
            listOfNotNull(FoxBridge.parse(bridge))
        }
        val before = context.filesDir.parentFile?.list()?.toSet().orEmpty()
        assertEquals(listOf(bridge), FoxBridgeStore.automatic(context).map { it.line })
        val after = context.filesDir.parentFile?.list()?.toSet().orEmpty()
        assertEquals(2, requested.size)
        assertTrue(context.getSharedPreferences("fox", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(before, after)
    }

    @Test
    fun invalidOrUnavailableSignatureReturnsNoCandidates() {
        FoxBridgeStore.fetcher = { _, _ -> null }
        assertTrue(FoxBridgeStore.automatic(context).isEmpty())
        FoxBridgeStore.fetcher = { _, _ -> "invalid".toByteArray() }
        FoxBridgeStore.verifier = { _, _ -> null }
        assertTrue(FoxBridgeStore.automatic(context).isEmpty())
    }
}
