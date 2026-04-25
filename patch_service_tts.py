import re

with open('app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt', 'r') as f:
    content = f.read()

# First replace: Need to fetch operator string and vehicle type
# Inside the run block:
#         val destName = destination?.name ?: ""
# We will inject the operator parsing before announcement.

# Let's locate the announcement block
search_block = """                            val platformAnnouncement = if (!platform.isNullOrBlank()) " auf Gleis $platform" else ""
                            val isDestination = nextStop.id == destination?.id
                            val announcement = if (isDestination) {
                                "Bitte aussteigen, Endhaltestelle $nextStopName$platformAnnouncement erreicht."
                            } else if (lastAnnouncedStopId == null) {
                                "Bitte einsteigen, nächste Haltestelle $nextStopName$platformAnnouncement."
                            } else {
                                "Nächste Haltestelle in Kürze, $nextStopName$platformAnnouncement."
                            }
                            requestAudioFocus()
                            tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, "TTS_ANNOUNCEMENT")"""

replace_block = """                            val platformAnnouncement = if (!platform.isNullOrBlank()) " auf Gleis $platform" else ""
                            val isDestination = nextStop.id == destination?.id
                            val isOrigin = lastAnnouncedStopId == null

                            val mode = checkin.lineName ?: "Zug"
                            val rawOperator = checkin.train?.operator?.name ?: ""
                            val operatorName = if (rawOperator.startsWith("Betreiber:")) {
                                rawOperator.substringAfter("Betreiber:").trim()
                            } else {
                                rawOperator
                            }

                            val announcement = if (isOrigin) {
                                "Der $mode erreicht in kürze deine Anfangshaltestelle $nextStopName bitte einsteigen"
                            } else if (isDestination) {
                                "Du erreichst nun in kürze deine Ausstiegshaltestelle $nextStopName ich danke dir mit der Fahrt mit $operatorName"
                            } else {
                                "Nächste Haltestelle in Kürze, $nextStopName$platformAnnouncement."
                            }

                            requestAudioFocus()
                            tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, "TTS_ANNOUNCEMENT")"""

if search_block in content:
    with open('app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt', 'w') as f:
        f.write(content.replace(search_block, replace_block))
    print("Patched successfully")
else:
    print("Could not find search block")
