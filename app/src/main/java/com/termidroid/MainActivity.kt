/*
 * Copyright 2026 Termidroid Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.termidroid

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Termidroid v0.3.0 — real Linux terminal on Android.
 *
 * First launch: downloads a static proot binary (~200 KB) and an Alpine Linux
 * mini rootfs (~3 MB), extracts them, then drops you into a real Linux shell
 * via proot (no root required). After that you can type 'tdpkg install gcc'
 * (or python3, make, cmake, git, g++, qemu-system-x86_64, etc.) to install
 * real Linux packages on demand.
 *
 * UI: no fake button bar. Tap anywhere on the terminal (especially the $
 * prompt at the bottom) to bring up the keyboard and start typing — just
 * like a real terminal.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var handler: Handler

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var userScrolledUp = false
    private var bootstrapped = false

    private val cyan = Color.parseColor("#4FC3F7")
    private val red = Color.parseColor("#E74856")
    private val yellow = Color.parseColor("#F9F1A5")
    private val green = Color.parseColor("#16C60C")
    private val gray = Color.GRAY
    private val fg = Color.parseColor("#CCCCCC")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    v.post {
                        val child = (v as ScrollView).getChildAt(0)
                        val atBottom = v.scrollY + v.height >= child.measuredHeight - 50
                        userScrolledUp = !atBottom
                        if (atBottom) showKeyboardAndFocus()
                    }
                }
                false
            }
        }

        output = TextView(this).apply {
            textSize = 13f
            setTextColor(fg)
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
            movementMethod = android.text.method.ScrollingMovementMethod()
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }

        input = EditText(this).apply {
            visibility = View.GONE
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
            height = 1
            setPadding(0, 0, 0, 0)
        }

        scrollView.addView(output)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(input)
        setContentView(root)

        // Tap anywhere -> focus input + show keyboard
        scrollView.setOnClickListener { showKeyboardAndFocus() }
        output.setOnClickListener { showKeyboardAndFocus() }

        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                val cmd = input.text.toString().trim('\n', '\r')
                input.setText("")
                sendCommand(cmd)
                true
            } else false
        }

        Thread { start() }.start()
    }

    private fun showKeyboardAndFocus() {
        input.visibility = View.VISIBLE
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, 0)
    }

    private fun start() {
        val app = Termidroid.instance
        try {
            if (!app.isBootstrapped()) {
                bootstrap()
            }
            installTdpkg()
            startLoginShell()
        } catch (t: Throwable) {
            post { appendLine("Fatal error: ${t.message}", red); t.printStackTrace() }
        }
    }

    // --------------------------------------------------------------------- Bootstrap

    private fun bootstrap() {
        post {
            progress.visibility = View.VISIBLE
            progress.progress = 0
            appendLine("Setting up Termidroid for the first time...", cyan)
        }
        val app = Termidroid.instance

        // 1) Download proot binary (small, ~200KB)
        val proot = app.prootFile
        download(PROOT_URL, proot, "Downloading proot...", 0, 25)
        proot.setExecutable(true)

        // 2) Download Alpine mini rootfs
        val tarball = File(app.tmpDir, "alpine.tgz")
        download(ALPINE_ROOTFS_URL, tarball, "Downloading Alpine Linux rootfs (3 MB)...", 25, 90)

        // 3) Extract
        post {
            appendLine("Extracting rootfs...", cyan)
            progress.progress = 92
        }
        extractTarGz(tarball, app.rootfsDir)

        // 4) Configure
        File(app.rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(app.rootfsDir, "etc/profile.d/tdroid.sh").writeText(
            "export PS1='\\[\\033[36m\\][termidroid]\\[\\033[0m\\] \\w # '\n" +
            "alias ll='ls -la'\n"
        )
        tarball.delete()
        post { progress.progress = 100; progress.visibility = View.GONE }
    }

    private fun download(url: String, dest: File, phase: String, startPct: Int, endPct: Int) {
        post { appendLine(phase, gray) }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 600000
        conn.instanceFollowRedirects = true
        val total = conn.contentLength.coerceAtLeast(1).toLong()
        dest.parentFile?.mkdirs()
        conn.inputStream.buffered().use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(8192)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = startPct + ((done * (endPct - startPct)) / total).toInt()
                    post { progress.progress = pct.coerceIn(startPct, endPct) }
                }
            }
        }
        conn.disconnect()
    }

    private fun extractTarGz(tgz: File, dest: File) {
        dest.mkdirs()
        Runtime.getRuntime().exec(
            arrayOf("tar", "-xzf", tgz.absolutePath, "-C", dest.absolutePath)
        ).waitFor()
    }

    /** Copy the tdpkg script from assets into $baseDir/bin, then bind-mount it into /usr/bin inside proot. */
    private fun installTdpkg() {
        val app = Termidroid.instance
        val outFile = app.tdpkgFile
        assets.open("tdpkg").use { inp ->
            FileOutputStream(outFile).use { out -> inp.copyTo(out) }
        }
        outFile.setExecutable(true)
        // Make tdpkg visible inside proot by copying it into /usr/bin/tdpkg in rootfs
        val destInsideRootfs = File(app.rootfsDir, "usr/bin/tdpkg")
        if (app.isBootstrapped()) {
            outFile.copyTo(destInsideRootfs, overwrite = true)
            destInsideRootfs.setExecutable(true)
        }
    }

    // --------------------------------------------------------------------- Shell

    private fun startLoginShell() {
        val app = Termidroid.instance
        bootstrapped = app.isBootstrapped()

        val cmd = if (bootstrapped) app.loginCmd()
                  else listOf("/system/bin/sh")

        val env = if (bootstrapped) null else {
            val e: MutableMap<String, String> = mutableMapOf()
            e["HOME"] = filesDir.absolutePath
            e["PATH"] = "/system/bin:/system/xbin"
            e["TERM"] = "xterm-256color"
            e["TMPDIR"] = cacheDir.absolutePath
            e["PS1"] = "$ "
            e
        }
        val workdir = if (bootstrapped) null else filesDir

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        if (env != null) { pb.environment().putAll(env) }
        if (workdir != null) pb.directory(workdir)
        val p = pb.start()
        process = p
        reader = BufferedReader(InputStreamReader(p.inputStream))
        writer = OutputStreamWriter(p.outputStream)
        Thread { readerLoop() }.apply { isDaemon = true; start() }

        post {
            appendLine()
            appendLine("Termidroid v0.3.0", cyan)
            if (bootstrapped) {
                appendLine("Running Alpine Linux via proot (no root required).", green)
                appendLine("Type 'tdpkg install <package>' to install software.", yellow)
                appendLine("Try: tdpkg install python3 git gcc g++ make cmake nodejs", gray)
            } else {
                appendLine("Running Android system shell (proot/bootstrap unavailable).", yellow)
                appendLine("Bootstrap did not complete — some commands may not work.", red)
            }
            appendLine("Tap anywhere on this screen and start typing.", gray)
            appendLine()
            showKeyboardAndFocus()
        }
    }

    private fun readerLoop() {
        try {
            val buf = CharArray(4096)
            while (true) {
                val n = reader?.read(buf) ?: break
                if (n < 0) break
                val chunk = String(buf, 0, n)
                    .replace("\u001B\\[[;0-9]*[a-zA-Z]".toRegex(), "")
                    .replace("\u001B\\][^\\x07]*\\x07".toRegex(), "")
                    .replace("\u0007", "")
                    .replace("\u0000", "")
                post { appendRaw(chunk) }
            }
        } catch (_: Exception) {}
        post { appendLine("[process exited]", gray) }
    }

    private fun sendCommand(cmd: String) {
        val w = writer ?: return
        try {
            w.write(cmd + "\n")
            w.flush()
        } catch (_: Exception) {}
    }

    // --------------------------------------------------------------------- UI helpers

    private fun appendRaw(text: String) {
        output.append(text)
        if (!userScrolledUp) scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendLine(text: String = "", color: Int? = null) {
        if (text.isNotEmpty()) {
            if (color != null) {
                val s = SpannableStringBuilder(text)
                s.setSpan(ForegroundColorSpan(color), 0, s.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                output.append(s)
            } else {
                output.append(text)
            }
        }
        output.append("\n")
        if (!userScrolledUp) scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun post(block: () -> Unit) = handler.post(block)

    override fun onDestroy() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        // Known good mirror for proot (Termux-distributed static binary)
        private const val PROOT_URL = "https://github.com/termux/termux-packages/releases/download/proot-v5.1.107-3/proot-aarch64"
        private const val ALPINE_ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz"
    }
}
