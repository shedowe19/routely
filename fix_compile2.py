file_path = "app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt"
with open(file_path, "r") as f:
    content = f.read()

# Let's fix the trailing commas and stuff if we removed arguments
content = content.replace("StatusDetailContent(\n                        uiState = uiState,\n                    )", "StatusDetailContent(\n                        uiState = uiState\n                    )")

with open(file_path, "w") as f:
    f.write(content)
