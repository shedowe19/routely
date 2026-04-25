import re

with open('app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt', 'r') as f:
    content = f.read()

# We need to reset `lastAnnouncedStopId` when tracking starts for a new trip.
# Find `startTracking` function
search_block = """    private fun startTracking(statusId: Int) {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {"""

replace_block = """    private fun startTracking(statusId: Int) {
        if (currentStatusId != statusId) {
            lastAnnouncedStopId = null
            currentStatusId = statusId
        }
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {"""

if search_block in content:
    with open('app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt', 'w') as f:
        f.write(content.replace(search_block, replace_block))
    print("Patched successfully")
else:
    print("Could not find search block")
