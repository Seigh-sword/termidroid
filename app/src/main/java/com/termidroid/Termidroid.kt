/*
 * Copyright 2026 Termidroid Contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.termidroid

import android.app.Application
import android.os.Build
import java.io.File

class Termidroid : Application() {

    lateinit var baseDir: File; private set
    lateinit var rootfsDir: File; private set
    lateinit var homeDir: File; private set
    lateinit var tmpDir: File; private set
    lateinit var prootFile: File; private set
    lateinit var tdpkgFile: File; private set
    lateinit var termidroidShFile: File; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        baseDir = File(filesDir, "termdroid")
        rootfsDir = File(baseDir, "rootfs")
        homeDir = File(baseDir, "home")
        tmpDir = File(cacheDir, "tmp")
        prootFile = File(baseDir, "bin/proot")
        tdpkgFile = File(baseDir, "bin/tdpkg")
        termidroidShFile = File(baseDir, "bin/termidroid")
        listOf(baseDir, homeDir, tmpDir, File(baseDir, "bin")).forEach { it.mkdirs() }
    }

    fun isBootstrapped(): Boolean =
        prootFile.exists() && prootFile.canExecute() &&
        File(rootfsDir, "etc/alpine-release").exists()

    fun detectArch(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            "arm64-v8a" in abis -> "aarch64"
            "armeabi-v7a" in abis -> "armv7"
            "x86_64" in abis -> "x86_64"
            "x86" in abis -> "i686"
            else -> "aarch64"
        }
    }

    fun prootCmd(bin: String, vararg args: String, workdir: String = "/root"): List<String> {
        val cmd = mutableListOf(
            prootFile.absolutePath,
            "--link2symlink", "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${homeDir.absolutePath}:/root",
            "-b", "/sdcard:/mnt/sdcard",
            "-b", "/storage:/mnt/storage",
            "-b", "${tmpDir.absolutePath}:/tmp",
            "-w", workdir,
            "/usr/bin/env",
            "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp", "USER=root", "TMDROID=1",
            bin,
        )
        cmd.addAll(args)
        return cmd
    }

    fun loginCmd(): List<String> = prootCmd("/bin/sh", "--login")

    fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor() == 0 && out == "0"
        } catch (_: Exception) { false }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val getUid = cls.getMethod("getUid")
            val uid = getUid.invoke(null) as? Int
            uid != null && uid >= 0
        } catch (_: Throwable) { false }
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

    companion object {
        lateinit var instance: Termidroid; private set
        const val VERSION = "0.4.0"
        const val VERSION_CODE = 6
    }
}
