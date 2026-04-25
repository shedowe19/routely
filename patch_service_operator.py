import re

with open('app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt', 'r') as f:
    content = f.read()

# Fix compilation error: checkin.train doesn't exist, it should just be checkin.operator
search_block = """                            val rawOperator = checkin.train?.operator?.name ?: "" """
replace_block = """                            val rawOperator = checkin.operator?.name ?: "" """

if search_block in content:
    with open('app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt', 'w') as f:
        f.write(content.replace(search_block, replace_block))
    print("Patched operator successfully")
else:
    print("Could not find operator search block")
