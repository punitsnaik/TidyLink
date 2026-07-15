# Contributing to TidyLink

Thanks for your interest! Bug reports, feature requests, and pull requests are all welcome.

## Development setup

1. Install the **latest stable Android Studio** (the project uses AGP 9 / compileSdk 36.1).
2. Clone and open the project; let Gradle sync.
3. Run the app - no configuration or API keys are required to build. AI categorization needs a key added in-app (Settings → AI providers), but every other feature works without one.

## Before opening a PR

- Run the checks CI will run:

  ```bash
  ./gradlew lint testDebugUnitTest assembleDebug
  ```

- Add unit tests for pure logic (URL canonicalization, category normalization, parsers live under `app/src/test/`). UI changes should at minimum be exercised manually in light + dark theme.
- If you touch the Room schema, bump the DB version, add a `Migration`, and commit the generated schema JSON under `app/schemas/`.
- If you touch dependencies or ProGuard/R8 rules, smoke-test a **release** build (`./gradlew assembleRelease`): save a link, bulk import, classification, JSON export/import.
- Extract user-visible strings to `res/values/strings.xml` - no hardcoded UI strings.
- Keep PRs focused: one logical change per PR, with a short description of the why.

## Code style

- Kotlin official style (`kotlin.code.style=official`), 4-space indent.
- Match the existing patterns: unidirectional `UiState` from the ViewModel, repository owns the pipeline, pure logic split into testable objects.
- KDoc on non-obvious public members; comments explain *why*, not *what*.

## Reporting bugs

Use the bug report issue template. Please include device/Android version, app version (Settings → About), and steps to reproduce. Never include your API keys in logs or screenshots.

## Security issues

Please do **not** open a public issue - see [SECURITY.md](SECURITY.md).
