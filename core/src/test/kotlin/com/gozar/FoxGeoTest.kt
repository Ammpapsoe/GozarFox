package com.gozar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxGeoTest {
    @Test
    fun iranianDomainsAndAddressesAreDirect() {
        assertTrue(FoxGeo.direct("aparat.com"))
        assertTrue(FoxGeo.direct("www.aparat.com"))
        assertTrue(FoxGeo.direct("digikala.com"))
        assertTrue(FoxGeo.direct("api.digikala.com"))
        assertTrue(FoxGeo.direct("varzesh3.com"))
        assertTrue(FoxGeo.direct("shad.ir"))
        assertTrue(FoxGeo.direct("sub.shad.ir"))
        assertTrue(FoxGeo.direct("2.57.3.1"))
    }

    @Test
    fun foreignDomainsAndAddressesUseTheTunnel() {
        assertFalse(FoxGeo.direct("google.com"))
        assertFalse(FoxGeo.direct("www.google.com"))
        assertFalse(FoxGeo.direct("youtube.com"))
        assertFalse(FoxGeo.direct("youtu.be"))
        assertFalse(FoxGeo.direct("notaparat.com"))
        assertFalse(FoxGeo.direct("aparat.com.evil.example"))
        assertFalse(FoxGeo.direct("8.8.8.8"))
    }

    @Test
    fun invalidUpdateKeepsTheWorkingList() {
        assertFalse(FoxGeo.update("# comment\nbad_domain\n"))
        assertTrue(FoxGeo.direct("aparat.com"))
        assertFalse(FoxGeo.update(""))
        assertTrue(FoxGeo.direct("digikala.com"))
    }

    @Test
    fun routeUsesGeoBeforeLanSetting() {
        FoxRoute.apply(false, "")
        assertTrue(FoxRoute.direct("aparat.com"))
        assertTrue(FoxRoute.direct("digikala.com"))
        assertFalse(FoxRoute.direct("google.com"))
        assertFalse(FoxRoute.direct("youtube.com"))
    }
}
