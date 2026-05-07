cat << 'PATCH' > var2.patch
<<<<<<< SEARCH
                            val isBusAnim = checkin.lineName?.startsWith("Bus", ignoreCase = true) == true || checkin.category == "bus"
                            StopoverItem(
                                stop = stop,
                                prevStop = prevStop,
                                nextStop = nextStop,
                                now = now,
                                index = index,
                                isBus = isBusAnim,
                                showCancelMessage = statusDetailUiState.showCancelMessages
                            )
=======
                            val isBusType = checkin.lineName?.startsWith("Bus", ignoreCase = true) == true || checkin.category == "bus"
                            StopoverItem(
                                stop = stop,
                                prevStop = prevStop,
                                nextStop = nextStop,
                                now = now,
                                index = index,
                                isBus = isBusType,
                                showCancelMessage = statusDetailUiState.showCancelMessages
                            )
>>>>>>> REPLACE
PATCH
