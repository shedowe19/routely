import os

replacements_white = {
    "Color.White": "MaterialTheme.colorScheme.surface",
    "androidx.compose.ui.graphics.Color.White": "MaterialTheme.colorScheme.surface"
}

def replace_in_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    new_content = content

    if "StatusCard.kt" in filepath:
        new_content = new_content.replace("containerColor = Color.White", "containerColor = MaterialTheme.colorScheme.surface")
        new_content = new_content.replace("DeepIndigo", "MaterialTheme.colorScheme.primary")
    elif "NotificationScreen.kt" in filepath:
        new_content = new_content.replace("androidx.compose.ui.graphics.Color.White", "MaterialTheme.colorScheme.surface")
        new_content = new_content.replace("DeepIndigo", "MaterialTheme.colorScheme.primary")
    elif "ProfileScreen.kt" in filepath:
        new_content = new_content.replace("androidx.compose.ui.graphics.Color.White", "MaterialTheme.colorScheme.surface")
        new_content = new_content.replace("DeepIndigo", "MaterialTheme.colorScheme.primary")
    elif "UserProfileScreen.kt" in filepath:
        new_content = new_content.replace("androidx.compose.ui.graphics.Color.White", "MaterialTheme.colorScheme.surface")
        new_content = new_content.replace("DeepIndigo", "MaterialTheme.colorScheme.primary")
    elif "SetupScreen.kt" in filepath:
        new_content = new_content.replace("containerColor = Color.White", "containerColor = MaterialTheme.colorScheme.surface")
        # Don't replace Color.White blindly in SetupScreen because it has a gradient background
        # Wait, SetupScreen uses gradient background for the whole screen?
    elif "StatusDetailScreen.kt" in filepath:
        new_content = new_content.replace("containerColor = Color.White", "containerColor = MaterialTheme.colorScheme.surface")
        new_content = new_content.replace("DeepIndigo", "MaterialTheme.colorScheme.primary")

    if new_content != content:
        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

for root, _, files in os.walk("app/src/main/kotlin/de/traewelling/app/ui"):
    for file in files:
        if file.endswith(".kt"):
            replace_in_file(os.path.join(root, file))
