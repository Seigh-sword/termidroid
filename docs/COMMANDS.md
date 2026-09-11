# Termidroid Commands Guide

This guide lists the commands available in Termidroid and how to use them.

## Important: There Are TWO Layers of Commands

Termidroid is being built in phases:

1. **Now (v0.1.x)** — You're talking directly to Android's built-in shell (`/system/bin/sh`, using `toybox`). Commands like `ls`, `ps`, `pm`, `am`, `toybox` work. No `apt`, `gcc`, `python`, `git` yet.
2. **Later (v0.2+)** — We will add a real Linux userland (via proot, no root needed). That layer brings `apt`, `gcc`, `g++`, `python3`, `make`, `cmake`, `git`, `node`, `rust`, etc. — installed on demand, not pre-bundled.

Until the Linux layer lands, you are in Android's built-in shell. It's still a real shell — just the Android/BusyBox/Toybox version.

## Built-in Termidroid Commands

| Command | What it does |
|---------|--------------|
| `help`  | Show the in-app help message listing common commands |
| `clear` | Clear the terminal screen |
| `exit` / `quit` | Close Termidroid |

## Android Shell Commands

Type **`toybox`** at the prompt to see the full list of built-in commands available on your device (it prints all commands bundled in Toybox, the Android command-line toolkit).

### Navigation & Files

| Command | Example | Description |
|---------|---------|-------------|
| `ls`    | `ls`, `ls -la`, `ls /sdcard` | List files in a directory. `-la` shows hidden files + details. |
| `cd`    | `cd /sdcard`, `cd ~`, `cd ..` | Change directory. `~` means your home folder. `..` means parent folder. |
| `pwd`   | `pwd` | Print working directory (shows where you are now). |
| `cat`   | `cat somefile.txt` | Print the contents of a file. |
| `mkdir` | `mkdir myfolder` | Create a new folder. |
| `rm`    | `rm file.txt`, `rm -rf folder` | Remove file or folder. **Be careful with `-rf`.** |
| `mv`    | `mv old.txt new.txt` | Move or rename a file/folder. |
| `cp`    | `cp src.txt dst.txt` | Copy a file. |
| `touch` | `touch newfile.txt` | Create an empty file, or update its timestamp. |
| `echo`  | `echo hello`, `echo $PATH` | Print text or the value of a variable. |
| `chmod` | `chmod 755 script.sh` | Change file permissions. |
| `df`    | `df -h` | Show disk usage / free space. |
| `du`    | `du -sh *` | Show how big each file/folder is. |
| `find`  | `find . -name "*.txt"` | Search for files by name. |
| `grep`  | `grep "hello" file.txt` | Search inside files for text. |
| `head` / `tail` | `tail -20 log.txt` | Show first/last lines of a file. |
| `wc`    | `wc -l file.txt` | Count lines/words/characters. |

### Processes & System

| Command | Description |
|---------|-------------|
| `ps`    | List running processes. Try `ps -A` to see all. |
| `top`   | Live view of running processes (like Task Manager). Press Ctrl+C or `q` to exit. |
| `kill`  | Kill a process by PID, e.g. `kill 1234`. |
| `killall` | Kill all processes by name, e.g. `killall com.example.app`. |
| `getprop` | Read a system property, e.g. `getprop ro.build.version.release` (Android version). |
| `setprop` | Set a system property (**requires root**). |
| `uptime` | How long the device has been on, load averages. |
| `date`  | Show current date/time. |
| `id`    | Show current user/group IDs. |
| `uname -a` | Kernel info. |

### App Management (Android-specific)

| Command | Example | Description |
|---------|---------|-------------|
| `pm list packages` | `pm list packages` | List all installed apps. `pm list packages -f` shows APK paths. |
| `pm install` | `pm install /sdcard/app.apk` | Install an APK (needs permission). |
| `pm uninstall` | `pm uninstall com.example.app` | Uninstall an app. |
| `pm path` | `pm path com.termidroid` | Show where an app's APK lives on disk. |
| `am start` | `am start -a android.intent.action.VIEW -d https://google.com` | Start an activity / open a URL. |
| `am start -n com.termidroid/.MainActivity` | Start Termidroid from the shell. |
| `am force-stop` | `am force-stop com.some.app` | Force-kill an app. |
| `input text` | `input text "hello"` | Type text into whatever app is in front (requires permission). |
| `input tap` | `input tap 500 500` | Simulate a screen tap at x,y coordinates. |
| `dumpsys` | `dumpsys battery`, `dumpsys wifi` | Dump system service info (huge, but interesting). |

### Scripting & Logic

Even inside Android's shell you can run scripts:

- **Variables**: `NAME="world"; echo "hello $NAME"`
- **If/else**: `if [ -f somefile ]; then echo exists; fi`
- **For loops**: `for i in 1 2 3; do echo $i; done`
- **Pipes**: `ps -A | grep termidroid`
- **Redirection**: `echo hello > file.txt`, `cat file.txt >> other.txt`
- **Subshells**: `(cd /sdcard && ls)`

## What's NOT Available (Yet)

| Command | Why not? |
|---------|----------|
| `sudo`, `su` | **Root required.** Termidroid does not root your phone and cannot give you root if you don't already have it. See "About Root" below. |
| `apt`, `apk`, `pacman`, `yum` | These are Linux distro package managers. They come with the Linux userland layer (v0.2+). |
| `git`, `gcc`, `g++`, `clang`, `make`, `cmake`, `python`, `python3`, `node`, `npm`, `ruby`, `rustc`, `cargo`, `vim`, `nano`, `ssh` | Also Linux userland packages. Will be installable on demand once the proot layer lands. |
| `bash` | Android ships with `mksh`/`sh` (Toybox shell). `bash` will arrive with the Linux userland. |

## Useful Tricks

- Pressing **Enter** sends whatever you typed to the shell.
- Use **up/down arrows** to scroll through the terminal (history support coming in a later release).
- **Ctrl+C** (hold Ctrl on physical keyboard, or use the upcoming extra-keys row) cancels the running command.
- `toybox --help` lists every Toybox command with a short description.
- `<command> --help` shows usage for any command, e.g. `ls --help`.
- Your home folder (where `~` points) is inside Termidroid's app data:
  `/data/data/com.termidroid/files/` — this is private to the app.
- To access your internal storage, `cd /sdcard/` — you may need storage permission for writing.

## About Root — Can Termidroid Root My Phone?

**No.** A terminal app **cannot root your phone**. Rooting requires:

1. **Unlocking the bootloader** (done from the bootloader/fastboot screen on a PC, not from inside Android),
2. **Flashing a modified boot image** (like Magisk) or a custom recovery (TWRP),
3. Which gives you a `su` binary that lets apps request root.

Termidroid runs as a normal Android app, with the same permissions as any other app. It cannot:
- Unlock your bootloader
- Flash partitions
- Install su/Magisk
- Escalate its own privileges

If your phone is **already rooted** (you have Magisk installed and have granted root to Termidroid), then `su` will work inside Termidroid just like in any other terminal app — but Termidroid does not provide root itself.

If your phone is **not rooted**, all commands in Termidroid run as the regular `u0_axx` app user — you cannot modify system files, you cannot read other apps' private data, and you cannot run commands that require root. That's normal Android security.

### How to root (if you really want to)

This varies hugely per device. The general process is:

1. Back up everything. Rooting wipes data on most devices.
2. Enable Developer Options → OEM unlocking.
3. Reboot into fastboot/bootloader mode.
4. `fastboot oem unlock` (or `fastboot flashing unlock`) — this **wipes your device**.
5. Flash [Magisk](https://github.com/topjohnwu/Magisk) patched boot image, or flash TWRP then flash Magisk zip.
6. Reboot, install Magisk Manager, and grant root to apps that request it.

**Warning**: Rooting voids warranties on many phones, breaks banking apps / Google Pay / DRM (Netflix, etc.), and can brick your device if done wrong. Proceed only if you know what you're doing. Termidroid will happily use root once you have it — but it can't give you root.

## Coming Soon (Linux Userland)

When the proot layer lands, you'll be able to:

- Type `pkg install gcc g++` (or `apt install …` depending on the distro chosen)
- Compile real C/C++ programs: `g++ hello.cpp -o hello && ./hello`
- Run Python scripts: `python3 script.py`
- Use `git clone`, `make`, `cmake`, `vim`, `ssh`, etc.
- Install packages on demand (they are **not** pre-installed, which keeps the APK small).

This requires no root. proot runs real Linux binaries under your normal Android UID.

If you want to know when this lands, watch the repo or the Releases page — every new tag will automatically build and publish a new APK to
<https://github.com/Seigh-sword/termidroid/releases>.
