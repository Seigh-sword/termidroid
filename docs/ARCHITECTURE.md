# Termidroid Architecture

Termidroid is a **real Linux terminal** for Android. It is **not an emulator** —
it executes real Linux ELF binaries on the Android kernel using a
`proot`-style (or future `chroot`/mount namespace) isolation layer.

This document describes how the codebase is organized.

## Modules

```
:app    → Main Android application (Activities, Service, bootstrap, UI)
:term   → Reusable VT100/xterm terminal-view + input-connection library
```

The terminal view module (`:term`) has no dependency on application logic and
can be reused in other projects.

## Layering

```
┌─────────────────────────────────────────────────────────────┐
│                        Android UI                           │
│  MainActivity → TerminalActivity (TermView) → Settings      │
└───────────────────┬─────────────────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────────────────┐
│                TerminalService (foreground)                 │
│  owns SessionManager → list of TerminalSession              │
└───────────────────┬─────────────────────────────────────────┘
                    │ PTY master fd
┌───────────────────▼─────────────────────────────────────────┐
│              Native: openpty / termios / exec               │
│  fork/exec shell under proot, wiring up pty slave to stdio  │
└───────────────────┬─────────────────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────────────────┐
│            Linux userland (downloaded rootfs)               │
│  /bin/sh, bash, package manager (apk/apt/...), gcc, python  │
└─────────────────────────────────────────────────────────────┘
```

## Bootstrap

On first launch:

1. `BootstrapWizardActivity` shows progress.
2. `BootstrapManager` downloads a small (~tens of MB) signed rootfs tarball.
3. SHA-256 is verified; archive extracted under `context.filesDir/rootfs/`.
4. Home directory created at `context.filesDir/home/`.

Development toolchains (gcc, g++, python, make, cmake, node, rust, etc.) are
**not** included in this archive. Users install them on demand via the
package-manager UI after setup. This keeps the initial APK and rootfs small.

## Sessions

- Each terminal tab/window corresponds to a `TerminalSession`.
- Sessions are owned by `TerminalService` (a foreground service) so they are
  not killed when the Activity is destroyed (orientation change, brief
  backgrounding).
- Each session owns a PTY master fd and a child shell process.
- The reader thread pulls bytes from the PTY and posts them to the `TermView`.
- `TermView` parses VT100/ANSI escape sequences into its `TerminalBuffer` and
  renders character cells to a Canvas.
- Key events (physical keyboard, IME) are converted to their byte sequences
  (e.g. arrow keys → `\x1b[A`, Ctrl+C → `\x03`) and written to the PTY master.

## Rendering

`TermView` is a custom `View`:

- Uses a monospace typeface and measures glyph width/height on init.
- Maintains rows/cols based on pixel size and font metrics.
- Paints each cell with its fg/bg color.
- Shows a filled-block cursor.
- Hooks into `BaseInputConnection` so soft keyboards work correctly.

Blinking cursor, selection, scrolling regions, and full escape-sequence support
will be added incrementally.

## CI

GitHub Actions (`.github/workflows/build.yml`):

- Runs on every push, PR, and tag.
- Builds debug APK (unsigned, always produced).
- Builds release APK (signed only if keystore secrets are configured).
- Uploads APKs as artifacts and creates GitHub Releases on tags.

## License

Apache 2.0 — see [LICENSE](../LICENSE).
