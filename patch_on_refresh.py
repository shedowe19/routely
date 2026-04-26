file_path = "app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt"
with open(file_path, "r") as f:
    content = f.read()

content = content.replace(
"""                    StatusDetailContent(
                        uiState = uiState,
                        onUserClick = onUserClick,
                        onRefresh = viewModel::refresh
                    )""",
"""                    StatusDetailContent(
                        uiState = uiState,
                        onUserClick = onUserClick
                    )""")

content = content.replace(
"""private fun StatusDetailContent(
    uiState: StatusDetailUiState,
    onUserClick: (String) -> Unit,
    onRefresh: () -> Unit
)""",
"""private fun StatusDetailContent(
    uiState: StatusDetailUiState,
    onUserClick: (String) -> Unit
)""")

with open(file_path, "w") as f:
    f.write(content)
