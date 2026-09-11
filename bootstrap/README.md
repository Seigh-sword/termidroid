# Bootstrap rootfs

This directory contains scripts used to build the minimal Linux rootfs that
Termidroid downloads on first launch. The rootfs is **not** committed to the
repository and is **not** bundled in the APK — that would push the APK size
to several gigabytes once development toolchains were included.

Instead, on first launch `BootstrapManager` (under `app/.../bootstrap/`)
downloads a small (~tens of MB) archive containing just the base system
(BusyBox or a minimal Alpine/Debian root) plus a package manager, then
lets the user install gcc, g++, python, make, cmake, etc. on demand.

## Producing a rootfs

The plan is to support multiple distros. For the first release we target a
minimal **Alpine Linux mini root filesystem** for aarch64/armv7/x86_64,
repacked with:

- A fixed `/etc/resolv.conf`
- A known `alpine-keys` keyring
- A profile that sets `TERM=xterm-256color` and adds Termidroid's bin paths
- `proot` (or equivalent) so the userland can run without root

A script to produce the archive will land here shortly. The expected output
files are:

```
rootfs-alpine-aarch64.tar.gz
rootfs-alpine-armv7.tar.gz
rootfs-alpine-x86_64.tar.gz
rootfs-alpine-${arch}.tar.gz.sha256
```

These will be hosted alongside GitHub Releases and verified against a pinned
SHA-256 in `BootstrapManager`.

## Adding packages post-install

Once the rootfs is set up, users can install packages through the distro's
package manager from inside the terminal (e.g. `apk add gcc g++ python3 make
cmake`), or through Termidroid's Packages screen which wraps these commands.
