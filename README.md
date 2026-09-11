# Termidroid

A real Linux terminal for Android, written in Kotlin. Termidroid is **not an
emulator** — it runs real Linux binaries natively on your device via proot.
No root required.

## Download

**GitHub Releases (auto-built by CI):**
https://github.com/Seigh-sword/termidroid/releases

Every `v*` tag is built by GitHub Actions and signed-unsigned APKs are attached
to the release. Download the APK on your phone, allow "Install from unknown
sources", and launch Termidroid.

## What's in v0.2.0

- ✅ Real Linux shell (Alpine Linux) running via proot on unmodified Android
- ✅ VT100 terminal UI with monospace font, dark theme, extra-keys row
- ✅ **Package installer** (50+ packages, on demand):
    - **Compilers:** gcc, g++, clang, rust
    - **Languages:** python3, nodejs, go, ruby, perl, openjdk17, lua, php
    - **Build tools:** make, cmake, ninja, autoconf, automake, libtool, pkgconf, gdb, strace
    - **Version control:** git
    - **Editors:** vim, nano, neovim
    - **Networking:** openssh-client, curl, wget
    - **QEMU emulators:** qemu-system-x86_64, qemu-system-i386, qemu-system-aarch64,
      qemu-system-arm, qemu-system-riscv64, qemu-img
    - **Utilities:** htop, tmux, zip, unzip, tar, gzip, less, man, file, which,
      tree, bash, zsh, fish
- ✅ **QEMU launcher** with its own home-screen icon — runs full virtual machines
- ✅ Automatic APK builds via GitHub Actions (no PC needed)
- ✅ Apache 2.0 license, full repo docs

## How it works

Termidroid ships with a statically-linked `proot` binary (bundled in the APK
assets) and downloads an Alpine Linux mini rootfs on first launch (~3 MB).
Packages are installed via `apk add` inside proot — nothing is pre-bundled, so
the base APK stays small and you only install what you need.

## Building from Source

```bash
git clone https://github.com/Seigh-sword/termidroid.git
cd termidroid
./gradlew assembleDebug
```

You don't need a PC. Push code to this repo on GitHub; the workflow builds APKs
automatically within about 2 minutes.

## Documentation

- [docs/COMMANDS.md](docs/COMMANDS.md) — List of commands, how to use them, and
  the truth about rooting your phone
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Code layout and design notes

## Can Termidroid root my phone?

**No.** See [docs/COMMANDS.md#rooting](docs/COMMANDS.md#can-termidroid-root-my-phone).
Termidroid runs as a normal app on unmodified Android.

## License

Apache License, Version 2.0 — see [LICENSE](LICENSE).
