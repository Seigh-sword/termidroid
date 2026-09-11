# Security Policy

## Supported Versions

While Termidroid is in pre-1.0 development, only the latest commit on `main`
receives security fixes.

## Reporting a Vulnerability

If you discover a security issue in Termidroid, **do not open a public GitHub
issue**. Please report it privately:

- Email: `security@example.com` *(replace with project contact once established)*
- Or use GitHub's private vulnerability reporting on the repository.

We aim to acknowledge reports within 72 hours and to provide a timeline for a
fix within 7 days.

## Scope

In scope:
- The Android app (Kotlin sources)
- The terminal view library (`:term`)
- Bootstrap / rootfs download and verification (signature/SHA-256 handling)
- The proot-based isolation layer (when introduced)

Out of scope:
- Vulnerabilities in packages installed by the user inside the Linux userland
  (gcc, python, etc.) — those are the responsibility of the upstream distro
  used as the rootfs.
- Issues on rooted devices where the user has intentionally weakened the
  security boundary.

## Security Goals

- Never require device root; Termidroid runs on unmodified Android.
- Verify bootstrap rootfs with SHA-256 and (when key management is in place)
  cryptographic signatures.
- Do not expose any exported components (services, receivers, providers) that
  third-party apps can invoke.
- Use TLS (HTTPS) for all network traffic; cleartext is disallowed by the
  network security config.
