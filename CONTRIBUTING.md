# Contributing to Mycelium

Thank you for your interest in contributing to Mycelium! This is an open-source Nostr client for Android, and contributions are welcome.

## Getting Started

1. **Fork** the repository on GitHub
2. **Clone** your fork locally
3. **Set up** Android Studio or your preferred IDE with JDK 11+ and Android SDK (compileSdk 36, minSdk 35)
4. **Build:** `./gradlew assembleDebug`
5. **Run** on a physical Android device or emulator (API 35+)

See `docs/DEVELOPMENT.md` for detailed setup instructions.

## Project Structure

Mycelium has two modules:

- **`app/`** — The Android application (`social.mycelium.android`)
- **`cybin/`** — In-repo Nostr protocol library (`com.example.cybin`), included via Gradle composite build

Read `CLAUDE.md` for a concise architecture overview or `docs/ARCHITECTURE.md` for the full reference.

## How to Contribute

### Reporting Issues

- Check existing issues before creating a new one
- Include your Android version, device model, and steps to reproduce
- Attach relay URLs if the issue involves relay behavior
- Logs from `adb logcat` are helpful for crashes

### Submitting Changes

1. Create a feature branch from `main`
2. Make focused, incremental changes — one feature or fix per PR
3. Follow existing code patterns and conventions (see below)
4. Test on a physical device when possible
5. Update relevant documentation (see `AGENTS.md` → Documentation Maintenance)
6. Submit a pull request with a clear description of what and why

### Code Style

- **Kotlin** with coroutines for all async work
- **Jetpack Compose** for UI — Material Design 3 theming
- **Ktor CIO** for HTTP/WebSocket — not OkHttp
- **Kotlinx Serialization** for JSON
- No comments that just narrate code — only explain non-obvious intent
- Keep files under 1,000 lines when possible

### Commit Messages

```
feat: description of new feature
fix: description of bug fix
chore: build/config/dependency changes
refactor: restructuring without behavior change
```

Reference NIPs when relevant: `feat: NIP-51 relay list sync`

## Architecture Guidelines

- **Dependency direction:** UI → ViewModel → Repository → Relay/DB (never reverse)
- **Cybin stays pure:** No Android Context, Compose, or Room in the Cybin library
- **NIP isolation:** Each NIP's logic should be findable by its kind numbers
- **Relay sovereignty:** Never hardcode relay URLs or override user relay preferences
- **Error resilience:** Relay failures are expected — degrade gracefully, don't crash

## What Needs Review

All contributions are reviewed by the project maintainer to ensure alignment with the project's direction. Areas that receive extra scrutiny:

- Changes to the relay layer or subscription system
- New dependencies
- Navigation changes (the overlay thread system is particularly sensitive)
- Anything touching `NoteCard.kt` or `NotesRepository.kt` (high blast radius)

## License

By contributing, you agree that your contributions will be licensed under the MIT License, unless the contribution includes code from Apache 2.0 licensed projects (lightning-kmp, phoenix), in which case the Apache 2.0 notice must be preserved.

## Questions?

Open an issue or reach out on Nostr. The project maintainer's pubkey can be found in the app's about screen.
