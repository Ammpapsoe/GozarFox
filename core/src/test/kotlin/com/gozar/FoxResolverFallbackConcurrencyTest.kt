package com.gozar

import org.junit.Test

/** Stress the concurrent endpoint-accounting fixtures that once lost updates. */
class FoxResolverFallbackConcurrencyTest {
    @Test
    fun fallbackAccountingRemainsCorrectAcrossFiftyRaces() {
        repeat(50) {
            val fixture = FoxLeakGuardTest()
            fixture.automaticResolverReportsFailureWhenEveryEndpointDies()
            fixture.automaticResolverSurvivesOneDeadEndpoint()
        }
    }
}
