# AI Agent Guidelines

## 🎯 Available Skills

> [!IMPORTANT]
> **Skills are constantly updated!** Before any task:
>
> 1. Open `.agent/skills/CATALOG.md`
> 2. Search for skills matching your current task
> 3. Read the relevant `SKILL.md` files
> 4. Follow the best practices described

### How to Find Skills

1. **By keyword search**: Search CATALOG.md for relevant terms
2. **By category**: Browse the categorized skill list
3. **By trigger**: Look at the "Triggers" column for matching keywords

### Helpful Keywords for This Project

- For general Android development: `android`, `mobile`, `kotlin`
- For UI work: `jetpack`, `compose`, `ui`, `material`
- For async patterns: `coroutines`, `flow`, `async`
- For testing: `testing`, `unit`, `junit`

## Project Context

### Overview

Träwelling Android is a mobile client for the Träwelling platform, optimized for precise transit tracking, manual time corrections, and an excellent user interface. It focuses heavily on accurately representing travel data, including modifications to planned departure and arrival times.

### Key Files

| File | Purpose |
| --- | --- |
| `app/build.gradle.kts` | Application dependencies and configuration |
| `app/src/main/kotlin/de/traewelling/app/MainActivity.kt` | Application entry point and root composable setup |
| `app/src/main/kotlin/de/traewelling/app/ui/navigation/AppNavigation.kt` | Core navigation structure |
| `app/src/main/kotlin/de/traewelling/app/data/api/TraewellingApiService.kt` | Retrofit interface for Träwelling APIs |

## Common Patterns

### Compose State Management

```kotlin
class MyViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MyUiState())
    val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()

    // update state
    fun doSomething() {
        _uiState.update { it.copy(isLoading = true) }
    }
}
```

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // pass uiState downward
}
```

## Important Constraints

### Known Limitations & Requirements

- The Träwelling API often returns duplicate data. You **must deduplicate** stations, departures, and stopovers (e.g., using IDs or comparing exact times) before storing them or feeding them into Compose UI lists.
- Do not use un-deduplicated API-provided IDs as `key` parameters in Jetpack Compose `LazyColumn`s.
- Some DB stations return internal platform IDs prefixed with '9' (e.g., '91'). This prefix must be stripped.

### Avoid

- **DO NOT** commit test user credentials, JWT tokens, or any sensitive API keys.
- **DO NOT** perform expensive logic in composable functions. Defer heavy logic to the `ViewModel` or use `remember` for complex calculations.
- **DO NOT** pass `ViewModel` instances into child/shared components, pass data state and lambdas instead.