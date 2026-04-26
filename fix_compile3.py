file_path = "app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt"
with open(file_path, "r") as f:
    content = f.read()

content = content.replace("private fun StatusDetailContent(\n    uiState: StatusDetailUiState,\n    onUserClick: (String) -> Unit\n)", "private fun StatusDetailContent(\n    uiState: StatusDetailUiState\n)")
content = content.replace("StatusDetailContent(\n                        uiState = uiState,\n                        onUserClick = onUserClick\n                    )", "StatusDetailContent(\n                        uiState = uiState\n                    )")

with open(file_path, "w") as f:
    f.write(content)
