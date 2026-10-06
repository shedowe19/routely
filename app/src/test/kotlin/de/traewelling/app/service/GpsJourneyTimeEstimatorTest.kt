package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.routing.RoadRouteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

class GpsJourneyTimeEstimatorTest {
    private val base = Instant.parse("2026-10-05T18:00:00Z").toEpochMilli()
    private val route = listOf(
        stop("origin", 1, 0.0, null, base, origin = true),
        stop("middle", 2, 0.02, base + 120_000, base + 150_000),
        stop("destination", 3, 0.04, base + 270_000, base + 300_000, destination = true)
    )

    @Test
    fun directedGpsProgressMakesEarlyArrivalForecastWithoutChangingApiTimes() {
        val estimator = GpsJourneyTimeEstimator()
        val estimate = travel(estimator, offset = -120_000)!!
        assertEquals(base, estimate.stopTimes.first { it.stopKey == "middle" }.arrivalMillis)
        assertEquals(base + 150_000, estimate.stopTimes.first { it.stopKey == "destination" }.arrivalMillis)
        assertFalse(estimate.stopTimes.first().arrivalObserved)
        assertEquals(base + 120_000, route[1].effectiveArrivalMillis)
    }

    @Test
    fun directedGpsProgressMakesDelayedForecast() {
        val estimate = travel(GpsJourneyTimeEstimator(), offset = 180_000)!!
        assertEquals(base + 300_000, estimate.stopTimes.first().arrivalMillis)
        assertEquals(base + 450_000, estimate.stopTimes.last().arrivalMillis)
        assertFalse(estimate.stopTimes.last().arrivalObserved)
    }

    @Test
    fun singleFixAndSinglePairDoNotClaimATravelForecast() {
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, fractionFix(.25), progress()))
        assertNull(update(estimator, fractionFix(.30), progress()))
        assertNotNull(update(estimator, fractionFix(.35), progress()))
    }

    @Test
    fun threeQuickFixesStillNeedMeaningfulObservationTime() {
        val estimator = GpsJourneyTimeEstimator()
        for ((index, fraction) in listOf(.25, .30, .35).withIndex()) {
            assertNull(update(estimator, fractionFix(fraction).copy(timeMillis = base + 50_000 + index * 2_000), progress()))
        }
    }

    @Test
    fun oneSecondLocationUpdatesCanEstablishAndMaintainAGpsForecast() {
        val estimator = GpsJourneyTimeEstimator()
        for (second in 0..7) {
            val fix = fractionFix(.10 + second * .02).copy(timeMillis = base + 20_000 + second * 1_000)
            assertNull(update(estimator, fix, progress()))
        }
        for (second in 8..20) {
            val fix = fractionFix(.10 + second * .02).copy(timeMillis = base + 20_000 + second * 1_000)
            assertNotNull(update(estimator, fix, progress()))
        }
    }

    @Test
    fun veryFrequentLocationUpdatesRetainEnoughElapsedMovementEvidence() {
        val estimator = GpsJourneyTimeEstimator()
        for (tick in 0..39) {
            val fix = fractionFix(.10 + tick * .004).copy(timeMillis = base + 20_000 + tick * 200)
            assertNull(update(estimator, fix, progress()))
        }
        for (tick in 40..65) {
            val fix = fractionFix(.10 + tick * .004).copy(timeMillis = base + 20_000 + tick * 200)
            assertNotNull(update(estimator, fix, progress()))
        }
    }

    @Test
    fun establishedForecastSurvivesBrakingFixesWithoutRenewingItsExpiry() {
        val estimator = GpsJourneyTimeEstimator()
        val established = travel(estimator)!!
        var previous = established
        for (seconds in 3..51 step 3) {
            val fix = fractionFix(.35 + seconds * .0001).copy(timeMillis = established.updatedAtMillis + seconds * 1_000,
                speedMetersPerSecond = .5)
            val estimate = update(estimator, fix, progress())!!
            if (seconds >= 27) {
                assertEquals(previous, estimate)
                assertEquals(previous.validUntilMillis, estimate.validUntilMillis)
            }
            previous = estimate
        }
    }

    @Test
    fun acceptedAccuracyVariationDoesNotDiscardASupportedForecast() {
        val estimator = GpsJourneyTimeEstimator()
        val established = travel(estimator)!!
        val noisy = fractionFix(.36).copy(timeMillis = established.updatedAtMillis + 3_000, accuracyMeters = 70.0)
        assertEquals(established, update(estimator, noisy, progress()))
        val recovered = fractionFix(.40).copy(timeMillis = established.updatedAtMillis + 6_000)
        assertNotNull(update(estimator, recovered, progress()))
    }

    @Test
    fun normalThreeSecondFixesAndClockTicksNeverAlternateGpsWithApi() {
        val estimator = GpsJourneyTimeEstimator()
        var established = false
        for (second in 0..60 step 3) {
            val fix = fractionFix(.10 + second * .01).copy(timeMillis = base + 20_000 + second * 1_000)
            val estimate = update(estimator, fix, progress())
            if (estimate != null) established = true
            if (established) {
                assertNotNull(estimate)
                assertEquals(estimate, update(estimator, fix, progress(), now = fix.timeMillis + 1_000))
                assertEquals(estimate, update(estimator, fix, progress(), now = fix.timeMillis + 2_000))
            }
        }
        assertTrue(established)
    }

    @Test
    fun freshStationaryFixesCannotExtendAnUnsupportedMidSegmentForecastForever() {
        val estimator = GpsJourneyTimeEstimator()
        val established = travel(estimator)!!
        var lastSupported = established
        for (seconds in 5..50 step 5) {
            val fix = fractionFix(.35).copy(timeMillis = established.updatedAtMillis + seconds * 1_000,
                speedMetersPerSecond = 0.0)
            val estimate = update(estimator, fix, progress())!!
            if (seconds >= 25) assertEquals(lastSupported, estimate)
            lastSupported = estimate
        }
        val tooLate = fractionFix(.35).copy(timeMillis = lastSupported.validUntilMillis + 1,
            speedMetersPerSecond = 0.0)
        assertNull(update(estimator, tooLate, progress()))
    }

    @Test
    fun arrivalHandoverKeepsForecastUntilTheSecondSlowFixObservesArrival() {
        val estimator = GpsJourneyTimeEstimator()
        for (fraction in listOf(.85, .90, .95)) {
            update(estimator, fractionFix(fraction), progress())
        }
        val arriving = stationFix(1, base + 120_000)
        val first = update(estimator, arriving, progress(arrived = true))!!
        assertFalse(first.stopTimes.first { it.stopKey == "middle" }.arrivalObserved)
        val second = update(estimator, arriving.copy(timeMillis = arriving.timeMillis + 3_000), progress(arrived = true))!!
        assertTrue(second.stopTimes.first { it.stopKey == "middle" }.arrivalObserved)
        assertEquals(arriving.timeMillis, second.stopTimes.first { it.stopKey == "middle" }.arrivalMillis)
    }

    @Test
    fun arrivingJustBeyondStationCentroidKeepsGpsUntilSlowArrivalIsConfirmed() {
        val estimator = GpsJourneyTimeEstimator()
        for (fraction in listOf(.85, .90, .95)) update(estimator, fractionFix(fraction), progress())
        val arriving = stationFix(1, base + 120_000).copy(longitude = .0204)
        val first = update(estimator, arriving, progress(arrived = true))!!
        assertFalse(first.stopTimes.first { it.stopKey == "middle" }.arrivalObserved)
        val second = update(estimator, arriving.copy(timeMillis = arriving.timeMillis + 3_000), progress(arrived = true))!!
        assertTrue(second.stopTimes.first { it.stopKey == "middle" }.arrivalObserved)
        assertEquals(arriving.timeMillis, second.stopTimes.first { it.stopKey == "middle" }.arrivalMillis)
    }

    @Test
    fun arrivalOvershootWithUnknownSpeedKeepsGpsThroughRequiredDwell() {
        val estimator = GpsJourneyTimeEstimator()
        for (fraction in listOf(.85, .90, .95)) update(estimator, fractionFix(fraction), progress())
        val arriving = stationFix(1, base + 120_000).copy(longitude = .0204, speedMetersPerSecond = null)
        val first = update(estimator, arriving, progress(arrived = true))!!
        val secondFix = arriving.copy(timeMillis = arriving.timeMillis + 3_000)
        assertEquals(first, update(estimator, secondFix, progress(arrived = true)))
        assertEquals(first, update(estimator, secondFix, progress(arrived = true), now = arriving.timeMillis + 6_000))
        val confirmed = update(estimator, arriving.copy(timeMillis = arriving.timeMillis + 8_000), progress(arrived = true))!!
        assertTrue(confirmed.stopTimes.first { it.stopKey == "middle" }.arrivalObserved)
        assertEquals(arriving.timeMillis, confirmed.stopTimes.first { it.stopKey == "middle" }.arrivalMillis)
    }

    @Test
    fun arrivalZoneDoesNotPermitAnOffCorridorFixEvenWhenTheVisitIsArrived() {
        val estimator = GpsJourneyTimeEstimator()
        for (fraction in listOf(.85, .90, .95)) update(estimator, fractionFix(fraction), progress())
        val offCorridor = stationFix(1, base + 120_000).copy(latitude = 50.00095, speedMetersPerSecond = null)
        assertNull(update(estimator, offCorridor, progress(arrived = true)))
    }

    @Test
    fun arrivalOvershootDoesNotPermitBackwardMovement() {
        val estimator = GpsJourneyTimeEstimator()
        for (fraction in listOf(.85, .90, .95)) update(estimator, fractionFix(fraction), progress())
        val arriving = stationFix(1, base + 120_000).copy(longitude = .0208, speedMetersPerSecond = null)
        assertNotNull(update(estimator, arriving, progress(arrived = true)))
        val backwards = arriving.copy(longitude = .0192, timeMillis = arriving.timeMillis + 3_000)
        assertNull(update(estimator, backwards, progress(arrived = true)))
    }

    @Test
    fun nextLegHandoverKeepsGpsWhileItsOwnMovementWindowIsBuilt() {
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(1, base + 120_000), progress(arrived = true))
        val atStation = update(estimator, stationFix(1, base + 123_000), progress(arrived = true))!!
        for ((step, fraction) in listOf(.10, .13, .16, .19, .22).withIndex()) {
            val fix = fractionFix(fraction, index = 2).copy(timeMillis = base + 135_000 + step * 3_000)
            assertNotNull(update(estimator, fix, progress(2)))
        }
        assertEquals(base + 153_000, atStation.validUntilMillis)
    }

    @Test
    fun incompatibleBackwardFixStillInvalidatesABrieflyMaintainedForecastImmediately() {
        val estimator = GpsJourneyTimeEstimator()
        val established = travel(estimator)!!
        assertNotNull(update(estimator, fractionFix(.351).copy(timeMillis = established.updatedAtMillis + 3_000), progress()))
        assertNull(update(estimator, fractionFix(.30).copy(timeMillis = established.updatedAtMillis + 6_000), progress()))
    }

    @Test
    fun incompatibleOffRouteFixStillInvalidatesABrieflyMaintainedForecastImmediately() {
        val estimator = GpsJourneyTimeEstimator()
        val established = travel(estimator)!!
        assertNotNull(update(estimator, fractionFix(.351).copy(timeMillis = established.updatedAtMillis + 3_000), progress()))
        assertNull(update(estimator, fractionFix(.36).copy(timeMillis = established.updatedAtMillis + 6_000,
            latitude = 50.002), progress()))
    }

    @Test
    fun jitterWithoutDirectedProgressFallsBackToApi() {
        val estimator = GpsJourneyTimeEstimator()
        for (index in 0..5) {
            assertNull(update(estimator, fractionFix(.30 + (index % 2) * .001).copy(timeMillis = base + 50_000 + index * 5_000), progress()))
        }
    }

    @Test
    fun wrongDirectionClearsAnExistingForecast() {
        val estimator = GpsJourneyTimeEstimator()
        assertNotNull(travel(estimator))
        val backwards = fractionFix(.20).copy(timeMillis = base + 50_000)
        assertNull(update(estimator, backwards, progress()))
        assertNull(update(estimator, backwards, progress()))
    }

    @Test
    fun offCorridorFixInvalidatesForecast() {
        val estimator = GpsJourneyTimeEstimator()
        assertNotNull(travel(estimator))
        val offRoute = fractionFix(.40).copy(latitude = 50.01)
        assertNull(update(estimator, offRoute, progress()))
        assertNull(update(estimator, offRoute, progress()))
    }

    @Test
    fun stationaryArrivalKeepsFirstObservationWhileLateDwellPushesFutureTimes() {
        val estimator = GpsJourneyTimeEstimator()
        val arrival = base + 120_000
        assertNull(update(estimator, stationFix(1, arrival), progress(arrived = false)))
        var estimate = update(estimator, stationFix(1, arrival + 5_000), progress(arrived = true))!!
        assertEquals(arrival, estimate.stopTimes.first().arrivalMillis)
        for (seconds in 10..70 step 5) {
            estimate = update(estimator, stationFix(1, arrival + seconds * 1_000), progress(arrived = true))!!
        }
        val current = estimate.stopTimes.first { it.stopKey == "middle" }
        assertEquals(arrival, current.arrivalMillis)
        assertTrue(current.arrivalObserved)
        assertEquals(base + 190_000, current.departureMillis)
        assertFalse(current.departureObserved)
        assertEquals(base + 310_000, estimate.stopTimes.last().arrivalMillis)
    }

    @Test
    fun earlyStationaryArrivalDoesNotAssumeAnEarlyOnwardDeparture() {
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(1, base), progress(arrived = true))
        val estimate = update(estimator, stationFix(1, base + 5_000), progress(arrived = true))!!
        assertEquals(base, estimate.stopTimes.first().arrivalMillis)
        assertEquals(base + 150_000, estimate.stopTimes.first().departureMillis)
        assertEquals(base + 270_000, estimate.stopTimes.last().arrivalMillis)
    }

    @Test
    fun originWaitingBeforeDepartureDoesNotInventAnEarlyJourney() {
        val estimator = GpsJourneyTimeEstimator()
        val delayedApiRoute = route.toMutableList().apply {
            this[0] = this[0].copy(effectiveDepartureMillis = base + 300_000)
        }
        val first = stationFix(0, base - 300_000)
        assertNull(update(estimator, first, progress(0, arrived = true), route = delayedApiRoute))
        assertNull(update(estimator, first.copy(timeMillis = first.timeMillis + 5_000),
            progress(0, arrived = true), route = delayedApiRoute))
        assertEquals(base + 300_000, delayedApiRoute[0].effectiveDepartureMillis)
    }

    @Test
    fun originWithPlannedArrivalStillNeedsDepartureMovementBeforeForecasting() {
        val estimator = GpsJourneyTimeEstimator()
        val boardingRoute = route.toMutableList().apply {
            this[0] = this[0].copy(plannedArrivalMillis = base - 60_000,
                effectiveArrivalMillis = base - 60_000, effectiveDepartureMillis = base + 300_000)
        }
        val first = stationFix(0, base - 60_000)
        assertNull(update(estimator, first, progress(0, arrived = true), route = boardingRoute))
        val observedOnly = update(estimator, first.copy(timeMillis = first.timeMillis + 5_000),
            progress(0, arrived = true), route = boardingRoute)!!
        assertEquals(base - 60_000, observedOnly.stopTimes.single().arrivalMillis)
        assertTrue(observedOnly.stopTimes.single().arrivalObserved)
        assertNull(observedOnly.stopTimes.single().departureMillis)
        assertEquals(GpsTimeUnavailableReason.WAITING_AT_ORIGIN, estimator.unavailableReason())
        assertEquals(base + 300_000, boardingRoute[0].effectiveDepartureMillis)
    }

    @Test
    fun missingSpeedNeedsStableDwellBeforeRecordingArrival() {
        val estimator = GpsJourneyTimeEstimator()
        val first = stationFix(1, base + 120_000).copy(speedMetersPerSecond = null)
        assertNull(update(estimator, first, progress(arrived = true)))
        assertNull(update(estimator, first.copy(timeMillis = first.timeMillis + 5_000), progress(arrived = true)))
        val estimate = update(estimator, first.copy(timeMillis = first.timeMillis + 8_000), progress(arrived = true))!!
        assertEquals(first.timeMillis, estimate.stopTimes.first().arrivalMillis)
    }

    @Test
    fun fastRadiusPassIsNotAnObservedArrivalOrDeparture() {
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, stationFix(1, base + 120_000).copy(speedMetersPerSecond = 20.0), progress(arrived = true)))
        assertNull(update(estimator, stationFix(1, base + 125_000).copy(speedMetersPerSecond = 20.0), progress(arrived = true)))
        val estimate = travel(estimator, offset = 150_000, index = 2)!!
        assertFalse(estimate.stopTimes.any { it.stopKey == "middle" && it.departureObserved })
    }

    @Test
    fun supportedDepartureAfterObservedDwellGetsItsOwnObservation() {
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(0, base), progress(0, arrived = true))
        assertNull(update(estimator, stationFix(0, base + 5_000), progress(0, arrived = true)))
        val observedDeparture = update(estimator, fractionFix(.20).copy(timeMillis = base + 20_000), progress())!!
        assertEquals("origin", observedDeparture.stopTimes.single().stopKey)
        assertEquals(base + 20_000, observedDeparture.stopTimes.single().departureMillis)
        assertTrue(observedDeparture.stopTimes.single().departureObserved)
        assertEquals(GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT, estimator.unavailableReason())
        val stillObservedOnly = update(estimator, fractionFix(.25).copy(timeMillis = base + 26_000), progress())!!
        assertEquals("origin", stillObservedOnly.stopTimes.single().stopKey)
        val estimate = update(estimator, fractionFix(.30).copy(timeMillis = base + 32_000), progress())!!
        val origin = estimate.stopTimes.first { it.stopKey == "origin" }
        assertEquals(base + 20_000, origin.departureMillis)
        assertTrue(origin.departureObserved)
        assertNull(origin.arrivalMillis)
    }

    @Test
    fun departureObservationWorksBeforeFivePercentOfALongSegment() {
        val longRoute = route.toMutableList().apply {
            this[1] = this[1].copy(longitude = .2, plannedArrivalMillis = base + 600_000,
                plannedDepartureMillis = base + 630_000)
            this[2] = this[2].copy(longitude = .4, plannedArrivalMillis = base + 1_230_000,
                plannedDepartureMillis = base + 1_260_000)
        }
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(0, base), progress(0, arrived = true), route = longRoute)
        update(estimator, stationFix(0, base + 5_000), progress(0, arrived = true), route = longRoute)
        val observedOnly = update(estimator, fractionFix(.02, route = longRoute).copy(timeMillis = base + 20_000),
            progress(), route = longRoute)!!
        assertEquals("origin", observedOnly.stopTimes.single().stopKey)
        assertEquals(base + 20_000, observedOnly.stopTimes.single().departureMillis)
        assertTrue(observedOnly.stopTimes.single().departureObserved)
        var estimate: GpsJourneyTimes? = null
        for (fraction in listOf(.05, .06, .07)) {
            estimate = update(estimator, fractionFix(fraction, route = longRoute), progress(), route = longRoute)
        }
        val origin = estimate!!.stopTimes.first { it.stopKey == "origin" }
        assertEquals(base + 20_000, origin.departureMillis)
        assertTrue(origin.departureObserved)
    }

    @Test
    fun observedDepartureRemainsAvailableOnCurvedRoadWithoutAFutureForecast() {
        val longRoute = longRoute()
        val estimator = GpsJourneyTimeEstimator()
        observeDepartureBeforeForecast(estimator, longRoute)
        val offCorridor = fractionFix(.03, route = longRoute).copy(latitude = 50.002, timeMillis = base + 25_000)
        val actualOnly = update(estimator, offCorridor, progress(route = longRoute), route = longRoute)!!
        val origin = actualOnly.stopTimes.single()
        assertEquals("origin", origin.stopKey)
        assertEquals(base + 20_000, origin.departureMillis)
        assertTrue(origin.departureObserved)
        assertNull(origin.arrivalMillis)
        assertEquals(GpsTimeUnavailableReason.OUTSIDE_CORRIDOR, estimator.unavailableReason())
        assertEquals(actualOnly, update(estimator, offCorridor, progress(route = longRoute),
            now = offCorridor.timeMillis + 1_000, route = longRoute))
    }

    @Test
    fun observedArrivalSurvivesOffCorridorWithoutKeepingGuessedDeparture() {
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(1, base + 120_000), progress(arrived = true))
        assertNotNull(update(estimator, stationFix(1, base + 123_000), progress(arrived = true)))
        val offCorridor = fractionFix(.10, index = 2).copy(latitude = 50.002, timeMillis = base + 130_000)
        val actualOnly = update(estimator, offCorridor, progress(2))!!
        val middle = actualOnly.stopTimes.single()
        assertEquals("middle", middle.stopKey)
        assertEquals(base + 120_000, middle.arrivalMillis)
        assertTrue(middle.arrivalObserved)
        assertNull(middle.departureMillis)
        assertFalse(middle.departureObserved)
        assertEquals(GpsTimeUnavailableReason.OUTSIDE_CORRIDOR, estimator.unavailableReason())
    }

    @Test
    fun observedOnlySnapshotExpiresAndClockTicksDoNotRenewIt() {
        val longRoute = longRoute()
        val estimator = GpsJourneyTimeEstimator()
        val actualOnly = observeDepartureBeforeForecast(estimator, longRoute)
        val fix = fractionFix(.02, route = longRoute).copy(timeMillis = base + 20_000)
        assertEquals(base + 50_000, actualOnly.validUntilMillis)
        assertEquals(actualOnly, update(estimator, fix, progress(route = longRoute),
            now = actualOnly.validUntilMillis, route = longRoute))
        assertNull(update(estimator, fix, progress(route = longRoute),
            now = actualOnly.validUntilMillis + 1, route = longRoute))
        assertEquals(GpsTimeUnavailableReason.NO_FRESH_LOCATION, estimator.unavailableReason())
    }

    @Test
    fun expiredRetainedForecastDropsGuessesButKeepsFreshActualEventsOnClockTick() {
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(1, base + 120_000), progress(arrived = true))
        val forecast = update(estimator, stationFix(1, base + 123_000), progress(arrived = true))!!
        val departing = fractionFix(.10, index = 2).copy(timeMillis = base + 135_000)
        val merged = update(estimator, departing, progress(2))!!
        assertTrue(merged.stopTimes.first { it.stopKey == "middle" }.departureObserved)
        assertEquals(forecast.validUntilMillis, merged.validUntilMillis)
        val braking = fractionFix(.1001, index = 2).copy(timeMillis = base + 150_000, speedMetersPerSecond = 0.0)
        assertEquals(merged, update(estimator, braking, progress(2)))
        val actualOnly = update(estimator, braking, progress(2), now = base + 154_000)!!
        assertEquals(base + 150_000, actualOnly.updatedAtMillis)
        assertEquals(base + 180_000, actualOnly.validUntilMillis)
        assertEquals("middle", actualOnly.stopTimes.single().stopKey)
        assertTrue(actualOnly.stopTimes.single().arrivalObserved)
        assertTrue(actualOnly.stopTimes.single().departureObserved)
        assertEquals(GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT, estimator.unavailableReason())
        assertEquals(actualOnly, update(estimator, braking, progress(2), now = base + 175_000))
        assertNull(update(estimator, braking, progress(2), now = base + 180_001))
    }

    @Test
    fun observedEventsDisappearWhenGpsBecomesUnreliable() {
        val longRoute = longRoute()
        val fresh = fractionFix(.03, route = longRoute).copy(timeMillis = base + 25_000)
        val rejected = listOf(
            fresh.copy(accuracyMeters = 76.0) to fresh.timeMillis,
            fresh to fresh.timeMillis - 1,
            fresh to fresh.timeMillis + 30_001,
            fresh.copy(speedMetersPerSecond = 101.0) to fresh.timeMillis,
            fractionFix(.50, route = longRoute).copy(timeMillis = base + 21_000) to base + 21_000
        )
        for ((fix, now) in rejected) {
            val estimator = GpsJourneyTimeEstimator()
            observeDepartureBeforeForecast(estimator, longRoute)
            assertNull(update(estimator, fix, progress(route = longRoute), now, longRoute))
            assertNotNull(estimator.unavailableReason())
            val recovered = fresh.copy(timeMillis = base + 30_000)
            assertNull(update(estimator, recovered, progress(route = longRoute), route = longRoute))
        }
    }

    @Test
    fun physicalStopOrPlannedVisitEditDiscardsObservedEvents() {
        val longRoute = longRoute()
        val changedRoutes = listOf(
            longRoute.mapIndexed { index, stop -> if (index == 0) stop.copy(latitude = 50.001) else stop } to
                GpsTimeUnavailableReason.OUTSIDE_CORRIDOR,
            longRoute.mapIndexed { index, stop -> if (index == 0) stop.copy(plannedArrivalMillis = base - 60_000) else stop } to
                GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT
        )
        for ((changedRoute, expectedReason) in changedRoutes) {
            val estimator = GpsJourneyTimeEstimator()
            observeDepartureBeforeForecast(estimator, longRoute)
            val fresh = fractionFix(.03, route = longRoute).copy(timeMillis = base + 25_000)
            assertNull(update(estimator, fresh, progress(route = changedRoute), route = changedRoute))
            assertEquals(expectedReason, estimator.unavailableReason())
        }
    }

    @Test
    fun unavailableReasonDistinguishesMissingAccuracyVisitAndRoute() {
        val estimator = GpsJourneyTimeEstimator()
        val fix = fractionFix(.25)
        assertNull(estimator.update(route, progress(), TrackingSource.GPS, null, fix.timeMillis))
        assertEquals(GpsTimeUnavailableReason.NO_FRESH_LOCATION, estimator.unavailableReason())
        assertNull(update(estimator, fix.copy(accuracyMeters = 76.0), progress()))
        assertEquals(GpsTimeUnavailableReason.INACCURATE_LOCATION, estimator.unavailableReason())
        assertNull(update(estimator, fix, progress().copy(gpsEstablished = false)))
        assertEquals(GpsTimeUnavailableReason.VISIT_UNCONFIRMED, estimator.unavailableReason())
        val noCoordinates = route.map { it.copy(latitude = null) }
        assertNull(update(estimator, fix, progress(route = noCoordinates), route = noCoordinates))
        assertEquals(GpsTimeUnavailableReason.ROUTE_UNSUPPORTED, estimator.unavailableReason())
        assertNull(update(estimator, fix.copy(speedMetersPerSecond = 101.0), progress()))
        assertEquals(GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT, estimator.unavailableReason())
    }

    @Test
    fun forecastReasonTracksInsufficientEvidenceCorridorAndRecovery() {
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, fractionFix(.25), progress()))
        assertEquals(GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT, estimator.unavailableReason())
        assertNotNull(travel(estimator))
        assertNull(estimator.unavailableReason())
        val offCorridor = fractionFix(.36).copy(latitude = 50.002, timeMillis = base + 45_000)
        assertNull(update(estimator, offCorridor, progress()))
        assertEquals(GpsTimeUnavailableReason.OUTSIDE_CORRIDOR, estimator.unavailableReason())
        for (fraction in listOf(.40, .45, .50)) assertNull(update(estimator, fractionFix(fraction), progress()))
        assertNotNull(update(estimator, fractionFix(.55), progress()))
        assertNull(estimator.unavailableReason())
    }

    @Test
    fun curvedRoadForecastUsesPolylineDistanceInsteadOfStationChord() {
        val path = curvedPath()
        val shape = roadGeometry(listOf(path))
        val estimator = GpsJourneyTimeEstimator()
        var estimate: GpsJourneyTimes? = null
        for (longitude in listOf(.004, .006, .008)) {
            val fix = pathFix(path, RoutePoint(50.005, longitude))
            estimate = update(estimator, fix, progress(), segmentGeometries = listOf(shape), useRoadGeometry = true)
        }
        val gps = estimate!!
        assertTrue(kotlin.math.abs(gps.stopTimes.first { it.stopKey == "middle" }.arrivalMillis!! - (base + 180_000)) <= 2)
        assertNull(estimator.unavailableReason())
        val sameFix = pathFix(path, RoutePoint(50.005, .008))
        assertEquals(gps, update(estimator, sameFix, progress(), now = sameFix.timeMillis + 1_000,
            segmentGeometries = listOf(shape), useRoadGeometry = true))
        assertEquals(sameFix.timeMillis + 30_000, gps.validUntilMillis)
        assertNull(update(GpsJourneyTimeEstimator(), sameFix, progress()))
    }

    @Test
    fun publicMuelheimEssenJsonSupportsGpsCurveWithBothRoadAlternatives() {
        val from = RoutePoint(51.43175246, 6.88538831)
        val to = RoutePoint(51.45018831, 7.0101172)
        val json = javaClass.getResourceAsStream("/routing/muelheim-essen-osrm.json")!!.bufferedReader().use { it.readText() }
        val geometry = RoadRouteParser.parse(json, from, to, base)!!
        assertEquals(2, geometry.alternatives.size)
        val publicRoute = listOf(
            route[0].copy(latitude = from.latitude, longitude = from.longitude),
            route[1].copy(latitude = to.latitude, longitude = to.longitude, isDestination = true,
                plannedArrivalMillis = base + 1_380_000, plannedDepartureMillis = null,
                effectiveArrivalMillis = base + 1_380_000, effectiveDepartureMillis = null)
        )
        val shape = GpsSegmentGeometry(publicRoute[0].key, publicRoute[1].key, geometry)
        val path = listOf(from) + geometry.alternatives.first() + to
        val estimator = GpsJourneyTimeEstimator()
        var estimate: GpsJourneyTimes? = null
        for (index in listOf(50, 55, 60)) {
            val point = geometry.alternatives.first()[index]
            val fix = pathFix(path, point, travelMillis = 1_380_000, offset = 120_000)
            assertNull(update(GpsJourneyTimeEstimator(), fix, progress(route = publicRoute), route = publicRoute))
            estimate = update(estimator, fix, progress(route = publicRoute), route = publicRoute,
                segmentGeometries = listOf(shape), useRoadGeometry = true)
        }
        val forecast = estimate!!.stopTimes.single()
        assertEquals("middle", forecast.stopKey)
        assertTrue(kotlin.math.abs(forecast.arrivalMillis!! - (base + 1_500_000)) <= 1_000)
        assertFalse(forecast.arrivalObserved)
        assertNull(estimator.unavailableReason())
    }

    @Test
    fun crossingRoadBranchesCannotChooseAnArbitraryAlongRoutePosition() {
        val path = listOf(RoutePoint(50.0, .0), RoutePoint(50.004, .02),
            RoutePoint(50.004, .0), RoutePoint(50.0, .02))
        val shape = roadGeometry(listOf(path))
        val fix = LocationFix(50.002, .01, 10.0, base + 60_000, 12.0)
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, fix, progress(), segmentGeometries = listOf(shape), useRoadGeometry = true))
        assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, estimator.unavailableReason())
    }

    @Test
    fun wrongRoadAndBackwardRoadMovementDiscardTheForecast() {
        val path = curvedPath()
        val shape = roadGeometry(listOf(path))
        val estimator = GpsJourneyTimeEstimator()
        var last: LocationFix? = null
        for (longitude in listOf(.004, .006, .008)) {
            val fix = pathFix(path, RoutePoint(50.005, longitude))
            last = fix
            update(estimator, fix, progress(), segmentGeometries = listOf(shape), useRoadGeometry = true)
        }
        assertNull(estimator.unavailableReason())
        val lastFix = checkNotNull(last)
        val backwards = lastFix.copy(longitude = .004, timeMillis = lastFix.timeMillis + 8_000)
        assertNull(update(estimator, backwards, progress(), segmentGeometries = listOf(shape), useRoadGeometry = true))
        assertEquals(GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT, estimator.unavailableReason())
        val wrongRoad = roadGeometry(listOf(listOf(RoutePoint(50.0, .0), RoutePoint(50.0, .02))))
        val wrongEstimator = GpsJourneyTimeEstimator()
        assertNull(update(wrongEstimator, lastFix, progress(), segmentGeometries = listOf(wrongRoad), useRoadGeometry = true))
        assertEquals(GpsTimeUnavailableReason.OUTSIDE_CORRIDOR, wrongEstimator.unavailableReason())
    }

    @Test
    fun returningRoadLoopCannotTurnOriginDepartureIntoFarAlongRouteProgress() {
        val path = listOf(RoutePoint(50.0, .0), RoutePoint(49.99, .0),
            RoutePoint(49.99, .02), RoutePoint(50.0, .0001), RoutePoint(50.0, .02))
        val shape = roadGeometry(listOf(path))
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(0, base), progress(0, arrived = true), segmentGeometries = listOf(shape), useRoadGeometry = true)
        update(estimator, stationFix(0, base + 5_000), progress(0, arrived = true),
            segmentGeometries = listOf(shape), useRoadGeometry = true)
        val departing = LocationFix(50.0, .004, 10.0, base + 15_000, 12.0)
        assertNull(update(estimator, departing, progress(), segmentGeometries = listOf(shape), useRoadGeometry = true))
        assertEquals(GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT, estimator.unavailableReason())
    }

    @Test
    fun provenRoadAlternativeKeepsItsProgressWhenCandidatesRejoin() {
        val north = listOf(RoutePoint(50.0, .0), RoutePoint(50.005, .0),
            RoutePoint(50.005, .012), RoutePoint(50.0, .012), RoutePoint(50.0, .02))
        val south = listOf(RoutePoint(50.0, .0), RoutePoint(49.988, .0),
            RoutePoint(49.988, .012), RoutePoint(50.0, .012), RoutePoint(50.0, .02))
        val shape = roadGeometry(listOf(north, south))
        val estimator = GpsJourneyTimeEstimator()
        val points = listOf(RoutePoint(50.005, .004), RoutePoint(50.005, .006),
            RoutePoint(50.005, .008), RoutePoint(50.005, .010), RoutePoint(50.005, .012),
            RoutePoint(50.003, .012), RoutePoint(50.001, .012), RoutePoint(50.0, .012),
            RoutePoint(50.0, .014), RoutePoint(50.0, .016))
        for ((index, point) in points.withIndex()) {
            val fix = pathFix(north, point)
            val estimate = update(estimator, fix, progress(), segmentGeometries = listOf(shape), useRoadGeometry = true)
            if (index >= 2) {
                assertNotNull("Confirmed road must remain stable at $point", estimate)
                assertNull(estimator.unavailableReason())
            }
        }
        val onCommonRoad = pathFix(north, RoutePoint(50.0, .014))
        val unconfirmed = GpsJourneyTimeEstimator()
        assertNull(update(unconfirmed, onCommonRoad, progress(), segmentGeometries = listOf(shape), useRoadGeometry = true))
        assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, unconfirmed.unavailableReason())
    }

    @Test
    fun roadLegHandoverRetainsOriginalForecastExpiryAndObservedDeparture() {
        val first = roadGeometry(listOf(curvedPath()))
        val nextPath = listOf(RoutePoint(50.0, .02), RoutePoint(50.005, .02),
            RoutePoint(50.005, .04), RoutePoint(50.0, .04))
        val next = roadGeometry(listOf(nextPath), fromIndex = 1, toIndex = 2)
        val estimator = GpsJourneyTimeEstimator()
        update(estimator, stationFix(1, base + 120_000), progress(arrived = true),
            segmentGeometries = listOf(first, next), useRoadGeometry = true)
        val established = update(estimator, stationFix(1, base + 123_000), progress(arrived = true),
            segmentGeometries = listOf(first, next), useRoadGeometry = true)!!
        val departing = LocationFix(50.002, .02, 10.0, base + 135_000, 12.0)
        val handover = update(estimator, departing, progress(2), segmentGeometries = listOf(next), useRoadGeometry = true)!!
        assertEquals(established.updatedAtMillis, handover.updatedAtMillis)
        assertEquals(established.validUntilMillis, handover.validUntilMillis)
        assertTrue(handover.stopTimes.first { it.stopKey == "middle" }.departureObserved)
        assertNotNull(handover.stopTimes.first { it.stopKey == "destination" }.arrivalMillis)
        assertNull(estimator.unavailableReason())
    }

    @Test
    fun duplicateFixDoesNotCreateMovementEvidence() {
        val estimator = GpsJourneyTimeEstimator()
        val fix = fractionFix(.25)
        repeat(5) { assertNull(update(estimator, fix, progress())) }
        assertNull(update(estimator, fractionFix(.30), progress()))
        assertNotNull(update(estimator, fractionFix(.35), progress()))
    }

    @Test
    fun duplicateFixDoesNotCreateArrivalEvidence() {
        val estimator = GpsJourneyTimeEstimator()
        val fix = stationFix(1, base + 120_000)
        repeat(5) { assertNull(update(estimator, fix, progress(arrived = true))) }
        assertNotNull(update(estimator, fix.copy(timeMillis = fix.timeMillis + 5_000), progress(arrived = true)))
    }

    @Test
    fun clockTickUsesFreshCachedForecastButDoesNotExtendItsExpiry() {
        val estimator = GpsJourneyTimeEstimator()
        val estimate = travel(estimator)!!
        val last = fractionFix(.35)
        assertEquals(last.timeMillis + 30_000, estimate.validUntilMillis)
        assertEquals(estimate, update(estimator, last, progress(), now = last.timeMillis + 30_000))
        assertNull(update(estimator, last, progress(), now = last.timeMillis + 30_001))
    }

    @Test
    fun staleFixDropsOldOffsetAndRecoveryRequiresNewMovement() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator)
        val old = fractionFix(.35)
        assertNull(update(estimator, old, progress(), now = old.timeMillis + 31_000))
        assertNull(update(estimator, fractionFix(.40, offset = 60_000), progress()))
        assertNull(update(estimator, fractionFix(.45, offset = 60_000), progress()))
        val recovered = update(estimator, fractionFix(.50, offset = 60_000), progress())!!
        assertEquals(base + 180_000, recovered.stopTimes.first().arrivalMillis)
    }

    @Test
    fun locationInvalidationDoesNotReviveACachedFix() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator)
        estimator.invalidateLocation()
        assertNull(update(estimator, fractionFix(.35), progress()))
        assertNull(update(estimator, fractionFix(.40), progress()))
        assertNull(update(estimator, fractionFix(.45), progress()))
        assertNotNull(update(estimator, fractionFix(.50), progress()))
    }

    @Test
    fun apiModeAndNullLocationClearGpsTimes() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator)
        val last = fractionFix(.35)
        assertNull(estimator.update(route, progress(), TrackingSource.TIMETABLE, last, last.timeMillis))
        assertNull(update(estimator, last, progress()))
        assertNotNull(travel(estimator, offset = 60_000))
        assertNull(estimator.update(route, progress(), TrackingSource.GPS, null, base + 102_000))
    }

    @Test
    fun freshEstimatorAfterRestartHasNoPreviousArrivalOrOffset() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator, offset = 120_000)
        val restarted = GpsJourneyTimeEstimator()
        assertNull(update(restarted, fractionFix(.35, offset = 120_000), progress()))
    }

    @Test
    fun resetStartsANewJourneyWithoutOldTimeWatermark() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator, offset = 120_000)
        estimator.reset()
        assertNotNull(travel(estimator, offset = -120_000))
    }

    @Test
    fun outOfOrderFixClearsCacheAndDoesNotCountAsANewObservation() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator)
        assertNull(update(estimator, fractionFix(.30), progress()))
        assertNull(update(estimator, fractionFix(.35), progress()))
        assertNotNull(travel(estimator, offset = 120_000))
    }

    @Test
    fun invalidCoordinatesAccuracySpeedAndFutureFixesFallBackToApi() {
        val valid = fractionFix(.35)
        val invalid = listOf(
            valid.copy(latitude = Double.NaN), valid.copy(latitude = 91.0),
            valid.copy(longitude = 181.0), valid.copy(accuracyMeters = 76.0),
            valid.copy(accuracyMeters = -1.0), valid.copy(accuracyMeters = Double.NaN),
            valid.copy(speedMetersPerSecond = Double.NaN), valid.copy(speedMetersPerSecond = -1.0),
            valid.copy(speedMetersPerSecond = 101.0), valid.copy(timeMillis = 0)
        )
        for (bad in invalid) {
            val estimator = GpsJourneyTimeEstimator()
            travel(estimator)
            assertNull(update(estimator, bad, progress(), now = valid.timeMillis))
            assertNull(update(estimator, valid, progress()))
        }
        assertNull(update(GpsJourneyTimeEstimator(), valid, progress(), now = valid.timeMillis - 1))
    }

    @Test
    fun implausibleJumpDoesNotCreateAForecast() {
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, fractionFix(.05), progress()))
        assertNull(update(estimator, fractionFix(.90).copy(timeMillis = base + 7_000), progress()))
    }

    @Test
    fun unchangedOrChangedProviderRealtimeDoesNotReplaceGpsScheduleOffset() {
        val estimator = GpsJourneyTimeEstimator()
        val estimate = travel(estimator, offset = -120_000)!!
        val providerDelayed = route.map { it.copy(effectiveArrivalMillis = it.plannedArrivalMillis?.plus(300_000),
            effectiveDepartureMillis = it.plannedDepartureMillis?.plus(300_000)) }
        assertEquals(estimate, update(estimator, fractionFix(.35, offset = -120_000), progress(), route = providerDelayed))
        assertEquals(base, estimate.stopTimes.first().arrivalMillis)
    }

    @Test
    fun plannedTimeEditInvalidatesBaselineEvenWithTheSameStopUuid() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator)
        val edited = route.toMutableList().apply { this[1] = this[1].copy(plannedArrivalMillis = base + 135_000) }
        assertNull(update(estimator, fractionFix(.35), progress(), route = edited))
        assertNull(update(estimator, fractionFix(.40), progress(), route = edited))
        assertNull(update(estimator, fractionFix(.45), progress(), route = edited))
        val recovered = update(estimator, fractionFix(.50), progress(), route = edited)!!
        val middle = recovered.stopTimes.first { it.stopKey == "middle" }
        assertEquals(base + 135_000, middle.plannedArrivalMillis)
        assertEquals(base + 127_500, middle.arrivalMillis)
    }

    @Test
    fun departureBeforeArrivalInvalidatesForecastAndRejectsFreshFixes() {
        val estimator = GpsJourneyTimeEstimator()
        assertNotNull(travel(estimator))
        val invalid = route.toMutableList().apply {
            this[1] = this[1].copy(plannedArrivalMillis = base + 180_000)
        }
        for (fraction in listOf(.35, .40, .45, .50)) {
            assertNull(update(estimator, fractionFix(fraction), progress(), route = invalid))
        }
    }

    @Test
    fun routeReorderAndCoordinateEditInvalidateCachedForecast() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator)
        val reordered = listOf(route[0], route[2], route[1])
        assertNull(update(estimator, fractionFix(.35), progress().copy(nextIndex = 2), route = reordered))
        assertNotNull(travel(estimator, offset = 120_000))
        val changedCoordinates = route.map { it.copy(latitude = 50.01) }
        assertNull(update(estimator, fractionFix(.35, offset = 120_000), progress(), route = changedCoordinates))
    }

    @Test
    fun cancelledStopIsSkippedInTheOrderedScheduledSegment() {
        val cancelled = route.toMutableList().apply { this[1] = this[1].copy(cancelled = true) }
        val estimate = travel(GpsJourneyTimeEstimator(), offset = 60_000, index = 2, route = cancelled)!!
        assertFalse(estimate.stopTimes.any { it.stopKey == "middle" })
        assertEquals(base + 330_000, estimate.stopTimes.single().arrivalMillis)
    }

    @Test
    fun missingSegmentTimesAndMissingCoordinatesUseApiFallback() {
        val missingTimes = route.map { it.copy(plannedArrivalMillis = null, plannedDepartureMillis = null) }
        val missingCoordinates = route.map { it.copy(latitude = null) }
        assertNull(travel(GpsJourneyTimeEstimator(), route = missingTimes))
        assertNull(travel(GpsJourneyTimeEstimator(), route = missingCoordinates))
    }

    @Test
    fun implausibleScheduledIntervalAndZeroLengthSegmentUseApiFallback() {
        val badInterval = route.toMutableList().apply { this[1] = this[1].copy(plannedArrivalMillis = base + 10_000) }
        val zeroLength = route.toMutableList().apply { this[1] = this[1].copy(longitude = 0.0) }
        assertNull(travel(GpsJourneyTimeEstimator(), route = badInterval))
        assertNull(travel(GpsJourneyTimeEstimator(), route = zeroLength))
    }

    @Test
    fun unknownVisitIndexMismatchAndUnestablishedCursorUseApiFallback() {
        val fix = fractionFix(.35)
        val states = listOf(progress().copy(nextStopKey = "missing"), progress().copy(nextIndex = 2),
            progress().copy(gpsEstablished = false), progress().copy(nextStopKey = null))
        for (state in states) assertNull(update(GpsJourneyTimeEstimator(), fix, state))
    }

    @Test
    fun repeatedStationIdsUseExplicitOrderedVisitIdentity() {
        val circular = route.map { it.copy(stationId = 1) }
        val estimate = travel(GpsJourneyTimeEstimator(), index = 2, route = circular)!!
        assertEquals("destination", estimate.stopTimes.single().stopKey)
        assertEquals(base + 270_000, estimate.stopTimes.single().arrivalMillis)
    }

    @Test
    fun duplicateVisitKeysAndAmbiguousFallbackVisitsAreRejected() {
        val duplicateKeys = route.map { it.copy(key = "duplicate") }
        assertNull(travel(GpsJourneyTimeEstimator(), route = duplicateKeys))
        val fallback = route.mapIndexed { index, stop -> stop.copy(key = "1:plan:departure:$index", stationId = 1,
            plannedArrivalMillis = base + 120_000, plannedDepartureMillis = base + 150_000) }
        assertNull(travel(GpsJourneyTimeEstimator(), route = fallback))
    }

    @Test
    fun estimatorNeverMutatesProgressOrCompletesADestination() {
        val state = progress(2)
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, stationFix(2, base + 270_000), state))
        assertNull(update(estimator, stationFix(2, base + 280_000), state))
        assertFalse(state.completed)
        assertFalse(state.arrivedAtCurrent)
        assertEquals(2, state.nextIndex)
    }

    @Test
    fun timeForUsesUuidAndRefusesDifferentUuidWithSameStationAndTimes() {
        val snapshot = snapshot()
        assertNotNull(snapshot.timeFor(rawStop("middle"), base + 10_000))
        assertNull(snapshot.timeFor(rawStop("other"), base + 10_000))
    }

    @Test
    fun timeForRefusesNewPlanMarkersEvenWhenUuidStayedTheSame() {
        val snapshot = snapshot()
        assertNull(snapshot.timeFor(rawStop("middle").copy(arrivalPlanned = "2026-10-05T18:03:00Z"), base + 10_000))
        assertNull(snapshot.timeFor(rawStop("middle").copy(departurePlanned = null), base + 10_000))
    }

    @Test
    fun timeForMatchesFallbackByStationAndEquivalentPlannedInstants() {
        val time = gpsTime().copy(stopKey = "2:planned:departure:1")
        val snapshot = snapshot(listOf(time))
        val stop = rawStop(null).copy(arrivalPlanned = "2026-10-05T20:02:00+02:00")
        assertNotNull(snapshot.timeFor(stop, base + 10_000))
        assertNull(snapshot.timeFor(stop.copy(arrivalPlanned = "2026-10-05T18:03:00Z"), base + 10_000))
        assertNull(snapshot.timeFor(stop.copy(departurePlanned = "2026-10-05T18:03:30Z"), base + 10_000))
    }

    @Test
    fun fallbackVisitCannotKeepGpsTimesAfterEitherPlannedMarkerIsRemoved() {
        val snapshot = snapshot(listOf(gpsTime().copy(stopKey = "2:planned:departure:1")))
        val stop = rawStop(null)
        assertNotNull(snapshot.timeFor(stop, base + 10_000))
        assertNull(snapshot.timeFor(stop.copy(arrivalPlanned = null), base + 10_000))
        assertNull(snapshot.timeFor(stop.copy(departurePlanned = null), base + 10_000))
    }

    @Test
    fun fallbackVisitCannotKeepGpsTimesAfterAPreviouslyMissingPlannedMarkerIsAdded() {
        val stop = rawStop(null)
        val departureOnly = snapshot(listOf(gpsTime().copy(stopKey = "2:planned:departure:1",
            plannedArrivalMillis = null, arrivalMillis = null)))
        val arrivalOnly = snapshot(listOf(gpsTime().copy(stopKey = "2:planned:arrival:1",
            plannedDepartureMillis = null, departureMillis = null)))
        assertNotNull(departureOnly.timeFor(stop.copy(arrivalPlanned = null), base + 10_000))
        assertNotNull(arrivalOnly.timeFor(stop.copy(departurePlanned = null), base + 10_000))
        assertNull(departureOnly.timeFor(stop, base + 10_000))
        assertNull(arrivalOnly.timeFor(stop, base + 10_000))
    }

    @Test
    fun timeForCannotMatchByBareIndexOrStationId() {
        val stop = rawStop(null).copy(arrivalPlanned = null, departurePlanned = null)
        assertNull(snapshot().timeFor(stop, base + 10_000))
        assertNull(snapshot().timeFor(rawStop(null).copy(station = TrainStation(id = 99)), base + 10_000))
    }

    @Test
    fun timeForRejectsAmbiguousMatchesAndInvalidTimes() {
        assertNull(snapshot(listOf(gpsTime(), gpsTime())).timeFor(rawStop("middle"), base + 10_000))
        assertNull(snapshot(listOf(gpsTime().copy(arrivalMillis = -1))).timeFor(rawStop("middle"), base + 10_000))
        assertNull(snapshot(listOf(gpsTime().copy(arrivalMillis = null, departureMillis = null))).timeFor(rawStop("middle"), base + 10_000))
        assertNull(snapshot().timeFor(rawStop("middle").copy(cancelled = true), base + 10_000))
    }

    @Test
    fun timeForRejectsExpiredFutureOrUnboundedSnapshots() {
        val snapshot = snapshot()
        assertNull(snapshot.timeFor(rawStop("middle"), base - 1))
        assertNotNull(snapshot.timeFor(rawStop("middle"), base + 30_000))
        assertNull(snapshot.timeFor(rawStop("middle"), base + 30_001))
        assertNull(snapshot.copy(validUntilMillis = base + 300_000).timeFor(rawStop("middle"), base + 30_001))
        assertNull(snapshot.copy(updatedAtMillis = 0).timeFor(rawStop("middle"), base))
    }

    private fun travel(
        estimator: GpsJourneyTimeEstimator, offset: Long = 0, index: Int = 1,
        route: List<TrackingStop> = this.route
    ): GpsJourneyTimes? {
        var result: GpsJourneyTimes? = null
        for (fraction in listOf(.25, .30, .35)) {
            // A test fix is tied to the unchanged provider schedule. Baseline
            // edits are passed separately to update in the relevant tests.
            result = update(estimator, fractionFix(fraction, offset, index, route), progress(index, route = route), route = route)
        }
        return result
    }

    private fun longRoute() = route.toMutableList().apply {
        this[1] = this[1].copy(longitude = .2, plannedArrivalMillis = base + 600_000,
            plannedDepartureMillis = base + 630_000)
        this[2] = this[2].copy(longitude = .4, plannedArrivalMillis = base + 1_230_000,
            plannedDepartureMillis = base + 1_260_000)
    }

    private fun observeDepartureBeforeForecast(estimator: GpsJourneyTimeEstimator, longRoute: List<TrackingStop>): GpsJourneyTimes {
        update(estimator, stationFix(0, base), progress(0, arrived = true, route = longRoute), route = longRoute)
        assertNull(update(estimator, stationFix(0, base + 5_000),
            progress(0, arrived = true, route = longRoute), route = longRoute))
        return update(estimator, fractionFix(.02, route = longRoute).copy(timeMillis = base + 20_000),
            progress(route = longRoute), route = longRoute)!!
    }

    private fun update(
        estimator: GpsJourneyTimeEstimator, fix: LocationFix, progress: TrackingProgress,
        now: Long = fix.timeMillis, route: List<TrackingStop> = this.route,
        segmentGeometries: List<GpsSegmentGeometry> = emptyList(), useRoadGeometry: Boolean = false
    ): GpsJourneyTimes? = estimator.update(route, progress, TrackingSource.GPS, fix, now, segmentGeometries, useRoadGeometry)

    private fun curvedPath() = listOf(RoutePoint(50.0, .0), RoutePoint(50.005, .0),
        RoutePoint(50.005, .02), RoutePoint(50.0, .02))

    private fun roadGeometry(paths: List<List<RoutePoint>>, fromIndex: Int = 0, toIndex: Int = 1): GpsSegmentGeometry =
        GpsSegmentGeometry(route[fromIndex].key, route[toIndex].key, RoadRouteGeometry(
            RoutePoint(route[fromIndex].latitude!!, route[fromIndex].longitude!!),
            RoutePoint(route[toIndex].latitude!!, route[toIndex].longitude!!), paths, base
        ))

    /** Synthetic motion follows a supplied path, independently of the estimator. */
    private fun pathFix(path: List<RoutePoint>, point: RoutePoint, travelMillis: Long = 120_000, offset: Long = 60_000): LocationFix {
        fun distance(a: RoutePoint, b: RoutePoint): Double = hypot(
            Math.toRadians(b.longitude - a.longitude) * 6_371_000 * cos(Math.toRadians((a.latitude + b.latitude) / 2)),
            Math.toRadians(b.latitude - a.latitude) * 6_371_000
        )
        var along = 0.0
        var found = false
        for ((from, to) in path.zipWithNext()) {
            if (distance(from, point) + distance(point, to) - distance(from, to) < .01) {
                along += distance(from, point)
                found = true
                break
            }
            along += distance(from, to)
        }
        check(found) { "Synthetic point must lie on the source path" }
        val total = path.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        return LocationFix(point.latitude, point.longitude, 10.0,
            base + (travelMillis * along / total).roundToLong() + offset, 12.0)
    }

    private fun progress(index: Int = 1, arrived: Boolean = false, route: List<TrackingStop> = this.route) =
        TrackingProgress(nextIndex = index, nextStopKey = route[index].key, arrivedAtCurrent = arrived, gpsEstablished = true)

    private fun fractionFix(fraction: Double, offset: Long = 0, index: Int = 1, route: List<TrackingStop> = this.route): LocationFix {
        val previous = (index - 1 downTo 0).first { !route[it].cancelled }
        val from = route[previous]
        val to = route[index]
        val departure = from.plannedDepartureMillis ?: base
        val arrival = to.plannedArrivalMillis ?: base + 120_000
        return LocationFix(50.0, (from.longitude ?: 0.0) + ((to.longitude ?: .02) - (from.longitude ?: 0.0)) * fraction,
            10.0, departure + ((arrival - departure) * fraction).toLong() + offset, 12.0)
    }

    private fun stationFix(index: Int, time: Long) = LocationFix(50.0, route[index].longitude!!, 10.0, time, 1.0)

    private fun stop(key: String, id: Int, longitude: Double, arrival: Long?, departure: Long?,
        origin: Boolean = false, destination: Boolean = false) = TrackingStop(
        key, id, key, 50.0, longitude, arrival, arrival, departure,
        isOrigin = origin, isDestination = destination, plannedDepartureMillis = departure
    )

    private fun gpsTime() = GpsStopTime("middle", 2, base + 120_000, base + 150_000,
        base + 130_000, base + 160_000)

    private fun snapshot(times: List<GpsStopTime> = listOf(gpsTime())) = GpsJourneyTimes(base, base + 30_000, times)

    private fun rawStop(uuid: String?) = StopStation(uuid = uuid, station = TrainStation(id = 2),
        arrivalPlanned = "2026-10-05T18:02:00Z", departurePlanned = "2026-10-05T18:02:30Z")
}
