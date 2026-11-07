package com.gozar

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FoxUpdateJournalConcurrencyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun twoJournalInstancesDoNotLoseUpdatesAcrossFiftyRaces() {
        val path = File(folder.root, "journal/update.json")
        val first = FoxUpdateJournal(path)
        val second = FoxUpdateJournal(path)
        repeat(50) { round ->
            first.recordUpdate("1.12.0", "1.11.0", "")
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val workers = listOf(first, second).map { journal ->
                thread(isDaemon = true, name = "journal-race-$round") {
                    ready.countDown()
                    if (start.await(5, TimeUnit.SECONDS)) repeat(3) { journal.beginLaunch("1.12.0") }
                }
            }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
            } finally {
                start.countDown()
                workers.forEach { it.join(10_000) }
            }
            assertFalse(workers.any { it.isAlive })
            assertEquals(6, first.state().attempts)
            assertEquals("1.11.0", second.rollbackTarget("1.12.0"))
        }
    }
}
