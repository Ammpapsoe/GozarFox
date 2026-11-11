package com.gozar

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateTrustTest {

    @Test
    fun onlyPinnedRepositoryReleasePagesAreAccepted() {
        assertTrue(UpdateChecker.trusted("https://github.com/Ammpapsoe/GozarFox/releases/tag/v1.10.2-desktop"))
        assertTrue(UpdateChecker.trusted("https://github.com/Ammpapsoe/GozarFox/releases/latest"))
    }

    @Test
    fun foreignHostsSchemesAndPathsAreRejected() {
        assertFalse(UpdateChecker.trusted("http://github.com/Ammpapsoe/GozarFox/releases/tag/v1"))
        assertFalse(UpdateChecker.trusted("https://evil.example.com/Ammpapsoe/GozarFox/releases/tag/v1"))
        assertFalse(UpdateChecker.trusted("https://github.com/Ammpapsoe/GozarFox/raw/main/payload.apk"))
        assertFalse(UpdateChecker.trusted("https://github.com/someone/Gozar/releases/tag/v1"))
        assertFalse(UpdateChecker.trusted("https://github.com@evil.example.com/Ammpapsoe/GozarFox/releases/tag/v1"))
        assertFalse(UpdateChecker.trusted("https://github.com:8443/Ammpapsoe/GozarFox/releases/tag/v1"))
        assertFalse(UpdateChecker.trusted("not a url"))
        assertFalse(UpdateChecker.trusted(""))
    }

    @Test
    fun versionComparisonStaysNumericAndBounded() {
        assertTrue(FoxUpdate.isNewer("1.10.2", "1.10.1"))
        assertTrue(FoxUpdate.isNewer("2.0", "1.99.99"))
        assertFalse(FoxUpdate.isNewer("1.10.1", "1.10.1"))
        assertFalse(FoxUpdate.isNewer("1.9.9", "1.10.1"))
    }
}
