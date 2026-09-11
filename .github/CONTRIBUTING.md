# Contributing to Termidroid

Thank you for your interest in contributing to Termidroid! This document outlines
the process for contributing.

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Bugs

- Use the GitHub issue tracker.
- Describe the bug, steps to reproduce, expected behavior, and actual behavior.
- Include your Android version, device model, Termidroid version, and logcat output if relevant.

### Suggesting Features

- Open an issue with the label `enhancement`.
- Describe the use case and why it would be useful to most users.

### Pull Requests

1. Fork the repository.
2. Create a feature branch off `main`.
3. Make your changes following the coding conventions (see below).
4. Add or update tests where applicable.
5. Ensure the project builds with `./gradlew assembleDebug` and lint passes
   with `./gradlew lintDebug`.
6. Submit a pull request against `main`.
7. Wait for review; address feedback.

## Coding Conventions

- Write **Kotlin** code following the [official Kotlin style guide](https://kotlinlang.org/docs/coding-conventions.html).
- Use 4-space indentation (no tabs).
- Use coroutines and `Flow` for asynchronous work.
- All user-visible strings must be added as resources in `res/values/strings.xml`.
- All new source files must carry the Apache 2.0 license header:

```kotlin
/*
 * Copyright 2026 Termidroid Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

## Commit Messages

- Use the present tense ("Add feature" not "Added feature").
- Use the imperative mood ("Fix crash" not "Fixes crash").
- The first line should be ≤ 72 characters.
- Reference issues in the body where appropriate.

## Setting Up a Development Environment

1. Install **Android Studio** (latest stable) with the Android SDK (API 34).
2. Set `JAVA_HOME` to JDK 17.
3. Clone your fork.
4. Open the project in Android Studio or run `./gradlew assembleDebug` from the command line.
5. APKs will be generated in `app/build/outputs/apk/`.

## CI

Every push and pull request is built by GitHub Actions. See
`.github/workflows/build.yml`. CI must pass before PRs are merged.

## Licensing

By contributing to Termidroid, you agree that your contributions are licensed
under the [Apache License 2.0](LICENSE).
