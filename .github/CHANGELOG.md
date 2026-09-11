# Changelog

All notable changes to Termidroid will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Project skeleton and Apache 2.0 licensing.
- Android application module (`:app`) with foreground service for sessions.
- Reusable terminal-view library module (`:term`).
- `TermView`: custom VT100 character-cell View with IME and hardware keyboard support.
- `TerminalSession` + `SessionManager` for PTY-backed shell processes (native PTY helper to land soon).
- Bootstrap manager that downloads a minimal Linux rootfs on first launch.
- Package registry for on-demand installation of gcc, g++, python, make, cmake, etc.
- GitHub Actions CI workflow that builds APKs on every push, PR, and tag.
- Settings screen (font size, color scheme, extra-keys row).

### Notes
- This is early scaffolding; the native PTY helper, package-manager UI, and
  full VT100 escape support are still under development.
