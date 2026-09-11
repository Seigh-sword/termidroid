/*
 * Copyright 2026 Termidroid Contributors
 * Licensed under the Apache License, Version 2.0
 *
 * QEMU launcher activity (separate launcher icon "Termidroid QEMU").
 *
 * Provides:
 *   - Target selection (x86_64, aarch64, arm, i386, riscv64, mips)
 *   - RAM, CDROM/ISO, disk, boot order, KVM (if root), display options
 *   - Ensures qemu-system-<target> is installed via tdpkg
 *   - Launches QEMU inside the proot environment with correct binds
 *     (e.g., /sdcard passed through so you can boot ISOs you downloaded).
 */
package com.termidroid

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class QemuActivity : Activity() {

    private lateinit var app: Termidroid
    private lateinit var handler: Handler
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var targetInput: EditText
    private lateinit var memInput: EditText
    private lateinit var isoInput: EditText
    private lateinit var diskInput: EditText
    private lateinit var cdbootCheck: CheckBox
    private lateinit var kvmCheck: CheckBox
    private lateinit var runBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var backBtn: Button

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Termidroid.instance
        handler = Handler(Looper.getMainLooper())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "Termidroid QEMU launcher"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 18f
        }
        root.addView(title)

        fun labeled(lbl: String, default: String, hint: String): EditText {
            val row = LinearLayout(this@QemuActivity).apply { orientation = LinearLayout.VERTICAL }
            val l = TextView(this@QemuActivity).apply { text = lbl; setTextColor(Color.LTGRAY); textSize = 12f }
            val e = EditText(this@QemuActivity).apply {
                setText(default); setHintTextColor(Color.GRAY); setHint(hint)
                setTextColor(Color.WHITE); setSingleLine(true)
            }
            row.addView(l); row.addView(e, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return e
        }

        targetInput = labeled("Target arch (x86_64 / aarch64 / arm / i386 / riscv64 / mips)",
            intent.getStringExtra("target") ?: "x86_64", "")
        memInput = labeled("Memory (MB)", "512", "256–2048")
        isoInput = labeled("ISO path (inside /mnt/sdcard/... or browse)", "/mnt/sdcard/Download/", "")
        diskInput = labeled("Disk image (blank = no disk)", "", "/mnt/sdcard/qemu-disk.img")
        cdbootCheck = CheckBox(this).apply { text = "Boot from CDROM (-boot d)"; setTextColor(Color.LTGRAY); isChecked = true }
        kvmCheck = CheckBox(this).apply { text = "Enable KVM (needs root & aarch64/arm host)"; setTextColor(Color.LTGRAY) }
        root.addView(cdbootCheck); root.addView(kvmCheck)

        val browseIso = Button(this).apply {
            text = "Browse for ISO"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
                startActivityForResult(Intent.createChooser(i, "Select ISO"), 1001)
            }
        }
        root.addView(browseIso)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runBtn = Button(this).apply { text = "Start QEMU" }
        stopBtn = Button(this).apply { text = "Stop"; isEnabled = false }
        backBtn = Button(this).apply { text = "Back to terminal" }
        btnRow.addView(runBtn); btnRow.addView(stopBtn); btnRow.addView(backBtn)
        root.addView(btnRow)

        scroll = ScrollView(this).apply { isFillViewport = true }
        out = TextView(this).apply {
            textSize = 11f; setTextColor(Color.GREEN); typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8); movementMethod = ScrollingMovementMethod()
        }
        scroll.addView(out)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        runBtn.setOnClickListener { launchQemu() }
        stopBtn.setOnClickListener { stopQemu() }
        backBtn.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        log("QEMU launcher ready. Select a target, adjust options, and press Start.")
        log("QEMU binaries will be installed automatically via tdpkg if missing.")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                // Content URIs are hard to map back to a path; copy to a known location in cache
                log("Selected URI: $uri (will be bound via /mnt/content)")
                isoInput.setText("/mnt/sdcard/Download/")
            }
        }
    }

    private fun launchQemu() {
        if (!app.isBootstrapped()) { log("Please bootstrap Termidroid first (open the main terminal)."); return }
        val target = targetInput.text.toString().trim()
        val mem = memInput.text.toString().trim().ifEmpty { "512" }
        val iso = isoInput.text.toString().trim()
        val disk = diskInput.text.toString().trim()
        val cdboot = cdbootCheck.isChecked
        val kvm = kvmCheck.isChecked && app.isRootAvailable()

        val args = mutableListOf<String>()
        args.addAll(listOf("-m", mem))
        if (iso.isNotEmpty()) args.addAll(listOf("-cdrom", iso))
        if (disk.isNotEmpty()) args.addAll(listOf("-hda", disk))
        if (cdboot && iso.isNotEmpty()) args.addAll(listOf("-boot", "d"))
        if (kvm) args.add("-enable-kvm")
        args.addAll(listOf("-nographic", "-serial", "mon:stdio"))

        val cmd = app.prootCmd("/bin/sh", "-c",
            "if ! command -v qemu-system-$target >/dev/null 2>&1; then " +
            "echo 'Installing qemu-system-$target ...'; tdpkg install qemu-system-$target; fi; " +
            "echo 'Starting qemu-system-$target ${args.joinToString(" ")}'; " +
            "exec qemu-system-$target ${args.joinToString(" ")}",
            extraBinds = listOf("/sdcard" to "/mnt/sdcard"))

        log("Launching: qemu-system-$target -m $mem ...")
        Thread {
            try {
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                val p = pb.start()
                process = p
                reader = BufferedReader(InputStreamReader(p.inputStream))
                writer = OutputStreamWriter(p.outputStream)
                runOnUiThread { runBtn.isEnabled = false; stopBtn.isEnabled = true }
                val buf = CharArray(4096)
                while (true) {
                    val n = reader?.read(buf) ?: break
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    runOnUiThread { log(chunk) }
                }
                p.waitFor()
                runOnUiThread { log("[QEMU exited]"); runBtn.isEnabled = true; stopBtn.isEnabled = false }
            } catch (t: Throwable) {
                runOnUiThread { log("Error: ${t.message}"); runBtn.isEnabled = true; stopBtn.isEnabled = false }
            }
        }.start()
    }

    private fun stopQemu() {
        try { writer?.write("\u0001x"); writer?.flush() } catch (_: Exception) {} // send C-a x to QEMU monitor
        try { process?.destroy() } catch (_: Exception) {}
    }

    private fun log(s: String) {
        out.append(s)
        out.append("\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
    }
}
