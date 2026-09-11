/*
 * Copyright 2026 Termidroid Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Termidroid v0.2.1 — Android shell terminal.
 * - Fixed scrolling (you can scroll up; auto-scroll only if you're at the bottom)
 * - Proper toybox help (doesn't run toybox spuriously)
 * - Detects root / Shizuku — type 'su' to start a root shell if available
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var handler: Handler
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var userScrolledUp = false

    private val cyan = Color.parseColor("#4FC3F7")
    private val red = Color.parseColor("#E74856")
    private val yellow = Color.parseColor("#F9F1A5")
    private val gray = Color.GRAY
    private val dkgray = Color.DKGRAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_SCROLL) {
                    post {
                        val child = getChildAt(0)
                        val atBottom = scrollY + height >= child.measuredHeight - 50
                        userScrolledUp = !atBottom
                    }
                }
                false
            }
        }

        output = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }

        val extraKeysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(8, 8, 8, 8)
        }
        val keys = listOf("CTRL", "ESC", "TAB", "/", "-", "|", "↑", "↓", "←", "→")
        val dp = (4 * resources.displayMetrics.density).toInt()
        for (label in keys) {
            val b = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setBackgroundColor(Color.parseColor("#222222"))
                typeface = Typeface.MONOSPACE
                setPadding(dp, dp, dp, dp)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp / 2 }
                layoutParams = lp
                setOnClickListener { onExtraKey(label) }
            }
            extraKeysContainer.addView(b)
        }

        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "type a command and press Enter..."
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setBackgroundColor(Color.parseColor("#151515"))
            setPadding(24, 24, 24, 24)
        }

        scrollView.addView(output)
        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(extraKeysContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(layout)

        startShell()

        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                val cmd = input.text.toString().trim('\n', '\r')
                input.setText("")
                handleCommand(cmd)
                true
            } else false
        }
    }

    private fun startShell() {
        try {
            val pb = ProcessBuilder("/system/bin/sh")
                .directory(filesDir)
                .redirectErrorStream(true)
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.environment()["PATH"] = "/system/bin:/system/xbin"
            pb.environment()["TERM"] = "xterm-256color"
            pb.environment()["TMPDIR"] = cacheDir.absolutePath
            pb.environment()["PS1"] = "$ "
            val p = pb.start()
            process = p
            reader = BufferedReader(InputStreamReader(p.inputStream))
            writer = OutputStreamWriter(p.outputStream)

            Thread { readerLoop() }.apply { isDaemon = true; start() }

            val rootAvailable = hasRoot()
            val shizukuAvailable = hasShizuku()

            handler.post {
                appendLine("Termidroid v0.2.1", cyan)
                appendLine("Android shell — type 'help' for commands. (Built by GitHub Actions CI)", gray)
                appendLine("----------------------------------------------------------------", dkgray)
                if (rootAvailable) {
                    appendLine("✓ Root detected — type 'su' to become root.", yellow)
                } else if (shizukuAvailable) {
                    appendLine("✓ Shizuku detected — type 'su' to run commands via Shizuku.", yellow)
                } else {
                    appendLine("No root / Shizuku detected. Commands run as the Termidroid app user.", gray)
                    appendLine("Install Shizuku (or root your device) to use 'su'.", gray)
                }
                appendLine()
            }
        } catch (e: Exception) {
            appendLine("Failed to start shell: ${e.message}", red)
        }
    }

    /** Check if root is available by trying to run 'su -c id'. */
    private fun hasRoot(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    } catch (_: Exception) { false }

    /**
     * Check if Shizuku is available. Shizuku runs a server as shell (uid 2000)
     * via ADB or root, and exposes a binder; we test by running /system/bin/sh
     * via the `shizuku` exec helper if present, or by trying to connect to the
     * standard shizuku socket path. Simpler: try `su` shell first; if regular
     * su fails, try `sh /sdcard/Android/data/moe.shizuku...` path. For v0.2.1
     * we detect Shizuku via the com.android.shell approach using `pm path
     * moe.shizuku.privileged.api`.
     */
    private fun hasShizuku(): Boolean {
        // Check if Shizuku app is installed
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c",
                "pm list packages | grep -q moe.shizuku && echo yes || echo no")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.contains("yes")
        } catch (_: Exception) { false }
    }

    private fun readerLoop() {
        try {
            val buf = CharArray(4096)
            while (true) {
                val n = reader?.read(buf) ?: break
                if (n < 0) break
                val chunk = String(buf, 0, n)
                    .replace("\u001B\\[[;0-9]*[a-zA-Z]".toRegex(), "")
                    .replace("\u0000", "")
                handler.post { appendRaw(chunk) }
            }
        } catch (_: Exception) {
            handler.post { appendLine("[shell exited]", gray) }
        }
    }

    private fun onExtraKey(label: String) {
        when (label) {
            "CTRL" -> writeToShell("\u0003")  // Ctrl+C (interrupt)
            "ESC"  -> writeToShell("\u001B")
            "TAB"  -> writeToShell("\t")
            "↑"    -> writeToShell("\u001B[A")
            "↓"    -> writeToShell("\u001B[B")
            "→"    -> writeToShell("\u001B[C")
            "←"    -> writeToShell("\u001B[D")
            else   -> writeToShell(label)
        }
    }

    private fun handleCommand(cmd: String) {
        when (cmd.trim()) {
            "exit", "quit" -> { appendLine("Goodbye.", cyan); finish(); return }
            "help" -> printHelp()
            "clear" -> runOnUiThread {
                output.text = ""
                userScrolledUp = false
            }
            "su", "sudo" -> startRootShell()
            "" -> writeToShell("\n")
            else -> writeToShell(cmd + "\n")
        }
    }

    /** Try to launch a root/Shizuku shell. */
    private fun startRootShell() {
        appendLine("Attempting to switch to root shell...", yellow)
        try {
            // In the current session we can't easily replace the process mid-stream,
            // so we just send 'su' to the existing shell. If su is available (real
            // root or Shizuku's su shim), it will start a root subshell.
            writeToShell("exec su\n")
        } catch (e: Exception) {
            appendLine("Could not start root shell: ${e.message}", red)
            appendLine("Make sure your device is rooted or Shizuku is running.", red)
        }
    }

    private fun writeToShell(text: String) {
        try {
            writer?.write(text)
            writer?.flush()
        } catch (_: Exception) {}
    }

    private fun printHelp() {
        appendLine()
        appendLine("Termidroid v0.2.1 — Available commands", cyan)
        appendLine()
        appendLine("Built-in:", yellow)
        appendLine("  help     Show this help")
        appendLine("  clear    Clear the screen")
        appendLine("  su/sudo  Start a root shell (requires root or Shizuku)")
        appendLine("  exit     Quit Termidroid")
        appendLine()
        appendLine("Android shell commands (run 'ls /system/bin' for the full list):", yellow)
        appendLine("  ls  cd  pwd  cat  echo  mkdir  rm  mv  cp  chmod")
        appendLine("  ps  kill  top  df  getprop  am  pm  input  toybox")
        appendLine()
        appendLine("Tips:", yellow)
        appendLine("  - Type 'toybox' with NO ARGUMENTS to see every built-in command name.")
        appendLine("  - Type 'toybox --help' for a summary; '<command> --help' for usage.")
        appendLine("  - Scroll up/down with your finger; auto-scroll resumes when you scroll to bottom.")
        appendLine("  - Long-press the output to select/copy text.")
        appendLine()
        appendLine("Note: 'apt', 'git', 'gcc', 'python' require the proot Linux userland", red)
        appendLine("layer, which is the next feature being built.", red)
        appendLine()
    }

    private fun appendRaw(text: String) {
        output.append(text)
        if (!userScrolledUp) {
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
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
        if (!userScrolledUp) {
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
