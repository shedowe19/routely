package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
        assertNull(update(estimator, first.copy(timeMillis = first.timeMillis + 5_000),
            progress(0, arrived = true), route = boardingRoute))
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
        assertNull(update(estimator, fractionFix(.20).copy(timeMillis = base + 20_000), progress()))
        assertNull(update(estimator, fractionFix(.25).copy(timeMillis = base + 26_000), progress()))
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
        assertNull(update(estimator, fractionFix(.02, route = longRoute).copy(timeMillis = base + 20_000), progress(), route = longRoute))
        var estimate: GpsJourneyTimes? = null
        for (fraction in listOf(.05, .06, .07)) {
            estimate = update(estimator, fractionFix(fraction, route = longRoute), progress(), route = longRoute)
        }
        val origin = estimate!!.stopTimes.first { it.stopKey == "origin" }
        assertEquals(base + 20_000, origin.departureMillis)
        assertTrue(origin.departureObserved)
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

    private fun update(
        estimator: GpsJourneyTimeEstimator, fix: LocationFix, progress: TrackingProgress,
        now: Long = fix.timeMillis, route: List<TrackingStop> = this.route
    ): GpsJourneyTimes? = estimator.update(route, progress, TrackingSource.GPS, fix, now)

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
