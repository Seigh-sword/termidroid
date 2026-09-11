/*
 * Copyright 2026 Termidroid Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.termidroid

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Termidroid v0.2.0 — Android shell terminal.
 * Runs commands via /system/bin/sh with proper HOME/PATH/PS1 environment.
 * The full Linux userland (proot + package manager + QEMU) is being built out
 * incrementally; this release ships the terminal UI with a working Android
 * shell, extra keys, proper HOME so `cd ~` works, and a `help` command.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var process: Process
    private lateinit var reader: BufferedReader
    private lateinit var writer: OutputStreamWriter

    private val cyan = Color.parseColor("#4FC3F7")
    private val red = Color.parseColor("#E74856")
    private val yellow = Color.parseColor("#F9F1A5")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        scrollView = ScrollView(this).apply { isFillViewport = true }

        output = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
        }

        val extraKeysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(8, 8, 8, 8)
        }
        val keys = listOf("CTRL", "ESC", "TAB", "/", "-", "|", "↑", "↓", "←", "→")
        val dp = (4 * resources.displayMetrics.density).toInt()
        for ((label, _) in keys.map { it to 0 }) {
            val b = android.widget.Button(this).apply {
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
            process = pb.start()
            reader = BufferedReader(InputStreamReader(process.inputStream))
            writer = OutputStreamWriter(process.outputStream)

            Thread { readerLoop() }.apply { isDaemon = true; start() }

            appendLine("Termidroid v0.2.0", cyan)
            appendLine("Android shell — type 'help' for commands. (Built by GitHub Actions CI)", Color.GRAY)
            appendLine("------------------------------------------------------------------", Color.DKGRAY)
            writeToShell("")
        } catch (e: Exception) {
            appendLine("Failed to start shell: ${e.message}", red)
        }
    }

    private fun readerLoop() {
        try {
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val chunk = String(buf, 0, n)
                    .replace("\u001B\\[[;0-9]*[a-zA-Z]".toRegex(), "")
                runOnUiThread { appendRaw(chunk) }
            }
        } catch (_: Exception) {
            runOnUiThread { appendLine("[shell exited]", Color.GRAY) }
        }
    }

    private fun onExtraKey(label: String) {
        when (label) {
            "CTRL" -> writeToShell("\u0003") // send Ctrl+C (interrupt) for now
            "ESC" -> writeToShell("\u001B")
            "TAB" -> writeToShell("\t")
            "↑" -> writeToShell("\u001B[A")
            "↓" -> writeToShell("\u001B[B")
            "→" -> writeToShell("\u001B[C")
            "←" -> writeToShell("\u001B[D")
            else -> writeToShell(label)
        }
    }

    private fun handleCommand(cmd: String) {
        when (cmd.trim()) {
            "exit", "quit" -> { appendLine("Goodbye.", cyan); finish(); return }
            "help" -> printHelp()
            "clear" -> runOnUiThread { output.text = "" }
            "" -> writeToShell("")
            else -> writeToShell(cmd + "\n")
        }
    }

    private fun writeToShell(text: String) {
        try {
            writer.write(text)
            writer.flush()
        } catch (_: Exception) {}
    }

    private fun printHelp() {
        appendLine()
        appendLine("Termidroid v0.2.0 — Available commands", cyan)
        appendLine()
        appendLine("Built-in:", yellow)
        appendLine("  help     Show this help")
        appendLine("  clear    Clear the screen")
        appendLine("  exit     Quit Termidroid")
        appendLine()
        appendLine("Android /system/bin commands (examples):", yellow)
        appendLine("  ls       cd <dir>   pwd     cat    echo")
        appendLine("  mkdir    rm         mv      cp     chmod")
        appendLine("  ps       kill       top     df     getprop")
        appendLine("  am       pm         input   toybox")
        appendLine("Type 'toybox' to see ALL built-in Android commands.")
        appendLine()
        appendLine("Note: 'sudo', 'apt', 'git', 'gcc', 'python' require the Linux", red)
        appendLine("userland layer (proot). It is coming in a future release.", red)
        appendLine("Note: Termidroid cannot root your phone — a terminal app cannot do that.", red)
        appendLine("See docs/COMMANDS.md for full documentation.", Color.GRAY)
        appendLine()
        writeToShell("") // get prompt back
    }

    private fun appendRaw(text: String) {
        output.append(text)
        scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun appendLine(text: String = "", color: Int? = null) {
        if (text.isNotEmpty()) {
            if (color != null) {
                val s = android.text.SpannableStringBuilder(text)
                s.setSpan(android.text.style.ForegroundColorSpan(color), 0, s.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                output.append(s)
            } else {
                output.append(text)
            }
        }
        output.append("\n")
        scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        try { writer.close() } catch (_: Exception) {}
        try { process.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
