cat << 'PATCH' > var.patch
<<<<<<< SEARCH
                            val isBusAnim = lineName.startsWith("Bus", ignoreCase = true) || checkin.category == "bus"
                            val platformAnnouncement = if (!platform.isNullOrBlank()) {
                                if (isBusAnim) " an Haltestelle $platform" else " auf Gleis $platform"
                            } else ""
=======
                            val isBusAnnouncement = lineName.startsWith("Bus", ignoreCase = true) || checkin.category == "bus"
                            val platformAnnouncement = if (!platform.isNullOrBlank()) {
                                if (isBusAnnouncement) " an Haltestelle $platform" else " auf Gleis $platform"
                            } else ""
>>>>>>> REPLACE
PATCH
