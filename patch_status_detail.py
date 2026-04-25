import re

with open('app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt', 'r') as f:
    content = f.read()

search = """                // Times
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.editDeparture,
                        onValueChange = onUpdateDeparture,
                        label = { Text("Abfahrt real") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = uiState.editArrival,
                        onValueChange = onUpdateArrival,
                        label = { Text("Ankunft real") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }"""

replace = """                // Times
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = uiState.editDeparture,
                            onValueChange = onUpdateDeparture,
                            label = { Text("Abfahrt real") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        TextButton(
                            onClick = {
                                val now = java.time.ZonedDateTime.now()
                                val iso = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                onUpdateDeparture(iso)
                            },
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Text("Abfahrt jetzt")
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = uiState.editArrival,
                            onValueChange = onUpdateArrival,
                            label = { Text("Ankunft real") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        TextButton(
                            onClick = {
                                val now = java.time.ZonedDateTime.now()
                                val iso = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                onUpdateArrival(iso)
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Ankunft jetzt")
                        }
                    }
                }"""

if search in content:
    with open('app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt', 'w') as f:
        f.write(content.replace(search, replace))
    print("Patched successfully")
else:
    print("Could not find search block")
