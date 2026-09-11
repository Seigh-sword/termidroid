/*
 * Copyright 2026 Termidroid Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.termidroid

import android.app.Application
import java.io.File

/**
 * Singleton holding paths for the Termidroid Linux environment.
 */
class Termidroid : Application() {

    lateinit var baseDir: File; private set
    lateinit var rootfsDir: File; private set
    lateinit var homeDir: File; private set
    lateinit var tmpDir: File; private set
    lateinit var prootFile: File; private set
    lateinit var tdpkgFile: File; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        baseDir = File(filesDir, "termdroid")
        rootfsDir = File(baseDir, "rootfs")
        homeDir = File(baseDir, "home")
        tmpDir = File(cacheDir, "tmp")
        prootFile = File(baseDir, "bin/proot")
        tdpkgFile = File(baseDir, "bin/tdpkg")
        baseDir.mkdirs()
        homeDir.mkdirs()
        tmpDir.mkdirs()
        File(baseDir, "bin").mkdirs()
    }

    fun isBootstrapped(): Boolean =
        prootFile.exists() && prootFile.canExecute() &&
        File(rootfsDir, "etc/alpine-release").exists()

    /** Build a proot command line for running a Linux binary. */
    fun prootCmd(bin: String, vararg args: String, workdir: String = "/root"): List<String> {
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
            "-w", workdir,
            "/usr/bin/env",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "USER=root",
            "TMDROID=1",
            bin
        )
        cmd.addAll(args)
        return cmd
    }

    /** Default login shell command via proot. */
    fun loginCmd(): List<String> = prootCmd("/bin/sh", "--login")

    companion object {
        lateinit var instance: Termidroid; private set
        const val PROOT_URL_AARCH64 = "https://github.com/termux/proot-distro/raw/master/dlcache/proot-aarch64-v5.1.107.tar.xz"
        const val ALPINE_ROOTFS_URL_AARCH64 = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz"
    }
}
