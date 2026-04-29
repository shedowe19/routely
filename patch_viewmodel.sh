cat << 'INNER_EOF' > modify.patch
<<<<<<< SEARCH
                    val tripId = enrichedStatus.checkin?.trip
                    if (tripId != null) {
                        repo.getStopovers(tripId).onSuccess { stops ->
                            val enrichedStops = enrichStops(stops, origin, destination)
                            _uiState.update {
                                it.copy(
                                    status = enrichedStatus,
                                    stopovers = enrichedStops,
                                    isLoading = false
                                )
                            }
                        }.onFailure {
                            _uiState.update { it.copy(isLoading = false) }
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
=======
                    val tripId = enrichedStatus.checkin?.trip
                    if (tripId != null) {
                        repo.getStopovers(tripId).onSuccess { stops ->
                            val enrichedStops = enrichStops(stops, origin, destination)

                            val finalOrigin = enrichedStops.find { it.id == origin?.id } ?: origin
                            val finalDestination = enrichedStops.find { it.id == destination?.id } ?: destination

                            enrichedStatus = enrichedStatus.copy(
                                checkin = enrichedStatus.checkin?.copy(
                                    origin = finalOrigin,
                                    destination = finalDestination
                                )
                            )

                            _uiState.update {
                                it.copy(
                                    status = enrichedStatus,
                                    stopovers = enrichedStops,
                                    isLoading = false
                                )
                            }
                        }.onFailure {
                            _uiState.update { it.copy(isLoading = false) }
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
>>>>>>> REPLACE
<<<<<<< SEARCH
            val enrichedStatus = status.copy(
                checkin = status.checkin?.copy(
                    origin = origin,
                    destination = destination
                )
            )

            _uiState.update { it.copy(status = enrichedStatus) }

            val tripId = enrichedStatus.checkin?.trip
            if (tripId != null) {
                repo.getStopovers(tripId).onSuccess { stops ->
                    val enrichedStops = enrichStops(stops, origin, destination)
                    _uiState.update {
                        it.copy(
                            status = enrichedStatus,
                            stopovers = enrichedStops,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                }
            }
=======
            var enrichedStatus = status.copy(
                checkin = status.checkin?.copy(
                    origin = origin,
                    destination = destination
                )
            )

            _uiState.update { it.copy(status = enrichedStatus) }

            val tripId = enrichedStatus.checkin?.trip
            if (tripId != null) {
                repo.getStopovers(tripId).onSuccess { stops ->
                    val enrichedStops = enrichStops(stops, origin, destination)

                    val finalOrigin = enrichedStops.find { it.id == origin?.id } ?: origin
                    val finalDestination = enrichedStops.find { it.id == destination?.id } ?: destination

                    enrichedStatus = enrichedStatus.copy(
                        checkin = enrichedStatus.checkin?.copy(
                            origin = finalOrigin,
                            destination = finalDestination
                        )
                    )

                    _uiState.update {
                        it.copy(
                            status = enrichedStatus,
                            stopovers = enrichedStops,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                }
            }
>>>>>>> REPLACE
<<<<<<< SEARCH
    private fun propagateDelays(stops: List<StopStation>): List<StopStation> {
        var currentDelayMinutes: Long = 0
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        return stops.map { stop ->
            var updatedStop = stop
            var delayUpdated = false

            // Process Arrival
            if (stop.arrivalPlanned != null) {
                val plannedArrivalZdt = try { ZonedDateTime.parse(stop.arrivalPlanned) } catch (e: DateTimeParseException) {
                    Log.w("StatusDetailViewModel", "Malformed arrivalPlanned time for stop ${stop.id}: ${stop.arrivalPlanned}", e)
                    null
                }
                val realArrivalZdt = try { stop.arrivalReal?.let { ZonedDateTime.parse(it) } } catch (e: DateTimeParseException) {
                    Log.w("StatusDetailViewModel", "Malformed arrivalReal time for stop ${stop.id}: ${stop.arrivalReal}", e)
                    null
                }

                if (plannedArrivalZdt != null) {
                    if (realArrivalZdt != null && !realArrivalZdt.isEqual(plannedArrivalZdt)) {
                        // We have a known real delay for this stop
                        currentDelayMinutes = ChronoUnit.MINUTES.between(plannedArrivalZdt, realArrivalZdt)
                    } else if (currentDelayMinutes != 0L && (stop.arrivalReal == null || stop.arrivalReal == stop.arrivalPlanned)) {
                        // Apply propagated delay
                        val newRealArrival = plannedArrivalZdt.plusMinutes(currentDelayMinutes)
                        updatedStop = updatedStop.copy(
                            arrivalReal = newRealArrival.format(formatter),
                            isArrivalDelayed = currentDelayMinutes > 0
                        )
                        delayUpdated = true
                    }
                }
            }

            // Process Departure
            if (stop.departurePlanned != null) {
                val plannedDepartureZdt = try { ZonedDateTime.parse(stop.departurePlanned) } catch (e: DateTimeParseException) {
                    Log.w("StatusDetailViewModel", "Malformed departurePlanned time for stop ${stop.id}: ${stop.departurePlanned}", e)
                    null
                }
                val realDepartureZdt = try { stop.departureReal?.let { ZonedDateTime.parse(it) } } catch (e: DateTimeParseException) {
                    Log.w("StatusDetailViewModel", "Malformed departureReal time for stop ${stop.id}: ${stop.departureReal}", e)
                    null
                }

                if (plannedDepartureZdt != null) {
                    if (realDepartureZdt != null && !realDepartureZdt.isEqual(plannedDepartureZdt)) {
                        // We have a known real delay for this stop
                        currentDelayMinutes = ChronoUnit.MINUTES.between(plannedDepartureZdt, realDepartureZdt)
                    } else if (currentDelayMinutes != 0L && (stop.departureReal == null || stop.departureReal == stop.departurePlanned)) {
                        // Apply propagated delay
                        val newRealDeparture = plannedDepartureZdt.plusMinutes(currentDelayMinutes)
                        updatedStop = updatedStop.copy(
                            departureReal = newRealDeparture.format(formatter),
                            isDepartureDelayed = currentDelayMinutes > 0
                        )
                        delayUpdated = true
                    }
                }
            }

            if (delayUpdated) updatedStop else stop
        }
    }
=======
    private fun propagateDelays(stops: List<StopStation>): List<StopStation> {
        var currentDelayMinutes: Long = 0
        var lastPlannedTime: ZonedDateTime? = null
        var fractionalRecovery = 0.0

        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        return stops.map { stop ->
            var updatedStop = stop
            var delayUpdated = false

            val plannedArrivalZdt = try { stop.arrivalPlanned?.let { ZonedDateTime.parse(it) } } catch (e: Exception) { null }
            val realArrivalZdt = try { stop.arrivalReal?.let { ZonedDateTime.parse(it) } } catch (e: Exception) { null }

            val plannedDepartureZdt = try { stop.departurePlanned?.let { ZonedDateTime.parse(it) } } catch (e: Exception) { null }
            val realDepartureZdt = try { stop.departureReal?.let { ZonedDateTime.parse(it) } } catch (e: Exception) { null }

            // Intelligent delay recovery based on elapsed travel time
            val currentPlannedTime = plannedArrivalZdt ?: plannedDepartureZdt
            if (currentPlannedTime != null && lastPlannedTime != null && currentDelayMinutes > 0) {
                val elapsedMinutes = ChronoUnit.MINUTES.between(lastPlannedTime, currentPlannedTime).coerceAtLeast(0)
                // Assume train recovers ~1 minute per 10 minutes of travel (10% recovery rate)
                fractionalRecovery += elapsedMinutes * 0.1
                if (fractionalRecovery >= 1.0) {
                    val recoveredMins = fractionalRecovery.toLong()
                    currentDelayMinutes = (currentDelayMinutes - recoveredMins).coerceAtLeast(0)
                    fractionalRecovery -= recoveredMins
                }
            }

            // Process Arrival
            if (plannedArrivalZdt != null) {
                if (realArrivalZdt != null && !realArrivalZdt.isEqual(plannedArrivalZdt)) {
                    currentDelayMinutes = ChronoUnit.MINUTES.between(plannedArrivalZdt, realArrivalZdt)
                    fractionalRecovery = 0.0
                } else if (currentDelayMinutes != 0L && (stop.arrivalReal == null || stop.arrivalReal == stop.arrivalPlanned)) {
                    val newRealArrival = plannedArrivalZdt.plusMinutes(currentDelayMinutes)
                    updatedStop = updatedStop.copy(
                        arrivalReal = newRealArrival.format(formatter),
                        isArrivalDelayed = currentDelayMinutes > 0
                    )
                    delayUpdated = true
                } else if (currentDelayMinutes != 0L) {
                    fractionalRecovery = 0.0
                }
            }

            // Process Departure
            if (plannedDepartureZdt != null) {
                if (realDepartureZdt != null && !realDepartureZdt.isEqual(plannedDepartureZdt)) {
                    currentDelayMinutes = ChronoUnit.MINUTES.between(plannedDepartureZdt, realDepartureZdt)
                    fractionalRecovery = 0.0
                } else if (currentDelayMinutes != 0L && (stop.departureReal == null || stop.departureReal == stop.departurePlanned)) {
                    val newRealDeparture = plannedDepartureZdt.plusMinutes(currentDelayMinutes)
                    updatedStop = updatedStop.copy(
                        departureReal = newRealDeparture.format(formatter),
                        isDepartureDelayed = currentDelayMinutes > 0
                    )
                    delayUpdated = true
                } else if (currentDelayMinutes != 0L) {
                    fractionalRecovery = 0.0
                }
            }

            if (currentPlannedTime != null) {
                lastPlannedTime = plannedDepartureZdt ?: plannedArrivalZdt ?: currentPlannedTime
            }

            if (delayUpdated) updatedStop else stop
        }
    }
>>>>>>> REPLACE
INNER_EOF
patch /app/app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt < modify.patch
