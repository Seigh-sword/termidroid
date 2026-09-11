/*
 * Copyright 2026 Termidroid Contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.termidroid

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File

/**
 * Termidroid Application singleton. Holds global paths, preferences, arch
 * detection and helpers for building proot command lines.
 */
class Termidroid : Application() {

    lateinit var baseDir: File; private set
    lateinit var rootfsDir: File; private set
    lateinit var homeDir: File; private set
    lateinit var tmpDir: File; private set
    lateinit var prootFile: File; private set
    lateinit var tdpkgFile: File; private set
    lateinit var termidroidShFile: File; private set
    lateinit var backupDir: File; private set
    lateinit var prefs: SharedPreferences; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        baseDir = File(filesDir, "termdroid")
        rootfsDir = File(baseDir, "rootfs")
        homeDir = File(baseDir, "home")
        tmpDir = File(cacheDir, "tmp")
        backupDir = File(baseDir, "backups")
        prootFile = File(baseDir, "bin/proot")
        tdpkgFile = File(baseDir, "bin/tdpkg")
        termidroidShFile = File(baseDir, "bin/termidroid")
        listOf(baseDir, homeDir, tmpDir, backupDir, File(baseDir, "bin")).forEach { it.mkdirs() }
        prefs = getSharedPreferences("termidroid", Context.MODE_PRIVATE)
    }

    fun isBootstrapped(): Boolean =
        prootFile.exists() && prootFile.canExecute() &&
        File(rootfsDir, "etc/alpine-release").exists()

    fun detectArch(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.contains("arm64-v8a") -> "aarch64"
            abis.contains("armeabi-v7a") -> "armv7"
            abis.contains("x86_64") -> "x86_64"
            abis.contains("x86") -> "i686"
            else -> "aarch64"
        }
    }

    /**
     * Build a proot command line.
     * Binds /dev, /proc, /sys, sdcard, storage, home, tmp, and the host bin dir.
     */
    fun prootCmd(bin: String,
                 vararg args: String,
                 workdir: String = "/root",
                 extraEnv: Map<String, String> = emptyMap(),
                 extraBinds: List<Pair<String, String>> = emptyList()): List<String> {
        val cmd = mutableListOf(
            prootFile.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${homeDir.absolutePath}:/root",
            "-b", "/sdcard:/mnt/sdcard",
            "-b", "/storage:/mnt/storage",
            "-b", "${tmpDir.absolutePath}:/tmp",
        )
        for ((src, dst) in extraBinds) cmd.addAll(listOf("-b", "$src:$dst"))
        val hostBin = File(baseDir, "bin").absolutePath
        cmd.addAll(listOf("-b", "$hostBin:/usr/local/host-bin"))
        cmd.addAll(listOf(
            "-w", workdir,
            "/usr/bin/env",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/host-bin",
            "TMPDIR=/tmp",
            "USER=root",
            "TMDROID=1",
        ))
        for ((k, v) in extraEnv) cmd.add("$k=$v")
        cmd.add(bin)
        cmd.addAll(args)
        return cmd
    }

    fun loginCmd(workdir: String = "/root"): List<String> =
        prootCmd("/bin/sh", "--login", workdir = workdir)

    /** Check whether rooted `su` works on this device. */
    fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor() == 0 && out == "0"
        } catch (_: Exception) { false }
    }

    fun prootUrl(): String = when (detectArch()) {
        "aarch64" -> "https://github.com/termux/termux-packages/releases/download/proot-v5.1.107-3/proot-aarch64"
        "armv7"   -> "https://github.com/termux/termux-packages/releases/download/proot-v5.1.107-3/proot-arm"
        "x86_64"  -> "https://github.com/termux/termux-packages/releases/download/proot-v5.1.107-3/proot-x86_64"
        "i686"    -> "https://github.com/termux/termux-packages/releases/download/proot-v5.1.107-3/proot-i686"
        else      -> "https://github.com/termux/termux-packages/releases/download/proot-v5.1.107-3/proot-aarch64"
    }

    fun alpineUrl(): String {
        val v = "3.19.1"
        val alpineArch = when (detectArch()) {
            "aarch64" -> "aarch64"
            "armv7"   -> "armv7"
            "x86_64"  -> "x86_64"
            "i686"    -> "x86"
            else      -> "aarch64"
        }
        return "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/$alpineArch/alpine-minirootfs-$v-$alpineArch.tar.gz"
    }

    // --- Theming ---
    data class Theme(
        val name: String,
        val bg: Int,
        val fg: Int,
        val black: Int, val red: Int, val green: Int, val yellow: Int,
        val blue: Int, val magenta: Int, val cyan: Int, val white: Int,
        val brightBlack: Int, val brightRed: Int, val brightGreen: Int,
        val brightYellow: Int, val brightBlue: Int, val brightMagenta: Int,
        val brightCyan: Int, val brightWhite: Int,
    )

    companion object {
        lateinit var instance: Termidroid; private set
        const val VERSION = "0.4.0"
        const val VERSION_CODE = 6

        val THEMES = listOf(
            Theme("Default Dark",
                0xFF0C0C0C.toInt(), 0xFFCCCCCC.toInt(),
                0xFF0C0C0C.toInt(), 0xFFC50F1F.toInt(), 0xFF13A10E.toInt(), 0xFFC19C00.toInt(),
                0xFF0037DA.toInt(), 0xFF881798.toInt(), 0xFF3A96DD.toInt(), 0xFFCCCCCC.toInt(),
                0xFF767676.toInt(), 0xFFE74856.toInt(), 0xFF16C60C.toInt(), 0xFFF9F1A5.toInt(),
                0xFF3B78FF.toInt(), 0xFFB4009E.toInt(), 0xFF61D6D6.toInt(), 0xFFF2F2F2.toInt()),
            Theme("Solarized Dark",
                0xFF002B36.toInt(), 0xFF93A1A1.toInt(),
                0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
                0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
                0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()),
            Theme("Dracula",
                0xFF282A36.toInt(), 0xFFF8F8F2.toInt(),
                0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
                0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFBFBFBF.toInt(),
                0xFF4D4D4D.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
                0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()),
            Theme("Matrix Green",
                0xFF000000.toInt(), 0xFF33FF33.toInt(),
                0xFF000000.toInt(), 0xFFFF3030.toInt(), 0xFF00FF00.toInt(), 0xFFBFFF00.toInt(),
                0xFF00AAFF.toInt(), 0xFF00FFAA.toInt(), 0xFF00FFFF.toInt(), 0xFF88FF88.toInt(),
                0xFF444444.toInt(), 0xFFFF5555.toInt(), 0xFF33FF33.toInt(), 0xFFDDFF33.toInt(),
                0xFF33BBFF.toInt(), 0xFF33FFCC.toInt(), 0xFF66FFFF.toInt(), 0xFFCCFFCC.toInt()),
            Theme("Classic Green",
                0xFF000000.toInt(), 0xFF00FF00.toInt(),
                0xFF000000.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
                0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
                0xFF00AA00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
                0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt()),
            Theme("Light Paper",
                0xFFFDF6E3.toInt(), 0xFF2C2C2C.toInt(),
                0xFFEEE8D5.toInt(), 0xFFD33682.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                0xFF268BD2.toInt(), 0xFF6C71C4.toInt(), 0xFF2AA198.toInt(), 0xFF073642.toInt(),
                0xFF93A1A1.toInt(), 0xFFDC322F.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
                0xFF839496.toInt(), 0xFFD33682.toInt(), 0xFF93A1A1.toInt(), 0xFF002B36.toInt()),
        )

        fun themeByName(name: String?): Theme =
            THEMES.firstOrNull { it.name == name } ?: THEMES[0]
    }
}
