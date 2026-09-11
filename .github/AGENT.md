# AGENT.md — Guidance for AI Coding Agents

This file provides instructions to AI agents (e.g., Arena, Cursor, Codex, Claude Code, Copilot) working on the **Termidroid** repository. Read it before making changes.

## Project Overview

**Termidroid** is a real Linux terminal application for Android, written in **Kotlin** using the Android SDK. It is **not an emulator** — it provides a native Linux userland environment on the Android device itself (via proot/chroot-style isolation) so users can install and run real Linux packages (gcc, g++, python, make, cmake, etc.) through their distro's package manager.

Key properties:
- **Language:** Kotlin (1.9+), with JNI/C for the terminal bridge if needed.
- **Build system:** Gradle with Kotlin DSL (`*.gradle.kts`).
- **Min SDK:** Android 7.0 (API 24).
- **Target SDK:** Latest stable Android (currently API 34).
- **Architecture:** ARM64 (primary), ARMv7, x86_64.
- **License:** Apache License 2.0 — all new files must carry the appropriate header.
- **Package manager support:** Packages are **not** pre-installed (that would make the APK ~5 GB). Users install toolchains on demand via the built-in package management after first launch.

## Repository Layout (when fully built out)

```
termidroid/
├── app/                     # Main Android application module
│   ├── src/main/
│   │   ├── java/com/termidroid/   # Kotlin sources
│   │   ├── res/                   # Android resources
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── term/                    # Terminal view / vt100 emulation library module
├── bootstrap/               # Rootfs bootstrap scripts (downloads minimal rootfs)
├── .github/workflows/       # CI (builds APK on push/PR)
├── docs/                    # Documentation
├── LICENSE                  # Apache 2.0
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── README.md
└── AGENT.md                 # This file
```

## How the CI Works

GitHub Actions (`.github/workflows/build.yml`) automatically:
1. Checks out the code.
2. Sets up JDK 17 and the Android SDK.
3. Caches Gradle dependencies.
4. Runs `./gradlew assembleDebug assembleRelease` (release is unsigned unless keystore secrets are configured).
5. Uploads the resulting `.apk` files as build artifacts.
6. Creates a GitHub Release with APKs attached when a tag is pushed.

Do **not** commit keystores, `local.properties`, `signing.properties`, or build outputs.

## Coding Conventions

- Use **Kotlin idioms**: data classes, sealed classes, coroutines (not AsyncTask/Thread), Flow for streams.
- Follow the **official Kotlin coding conventions** (4-space indent, no tabs).
- Use Android Jetpack components where appropriate (ViewModel, LiveData/Flow, Room, Navigation).
- Terminal I/O is performance-sensitive: avoid allocations in the hot input/output path.
- All UI strings must go in `res/values/strings.xml` (no hardcoded English in Kotlin except logs/errors).
- Keep the APK base size small — never bundle large binaries; download them at first run.
- Use Material Design 3 for the UI.

## When Making Changes

1. **Do not** switch branches — always work on the current session branch.
2. Add an Apache 2.0 license header comment to new Kotlin/Java source files.
3. Update `README.md` or `docs/` if user-facing behavior changes.
4. If you add a new Gradle dependency, pin a specific version and use `libs.versions.toml` (the version catalog).
5. Test that `./gradlew assembleDebug` succeeds before finishing (if the environment allows).
6. Run `./gradlew lintDebug` and address any new warnings.

## Things to Avoid

- Do NOT pre-install/ship GCC, Python, etc. inside the APK. They are downloaded on demand.
- Do NOT require root. Termidroid must work on unmodified, stock Android devices.
- Do NOT use `targetSdkVersion` older than 33 without a documented reason.
- Do NOT add Google Services / Firebase / proprietary telemetry without discussion.
- Do NOT commit `local.properties`, `keystore`, `*.apk`, `build/`, `.gradle/`.

## Getting Help

If something is ambiguous, check `README.md` and `docs/` first. Open an issue on the tracker for design questions rather than guessing on major architecture changes.
