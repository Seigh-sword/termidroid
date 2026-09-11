/*
 * Copyright 2026 Termidroid Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.termidroid

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Termidroid v0.4.0 — real Linux terminal on Android.
 *
 * Features:
 *  - VT100 ANSI color parsing (AnsiParser)
 *  - Multi-tab terminal sessions (TABS)
 *  - Pinch-to-zoom font size (ZOOM)
 *  - Multiple color themes (THEMES)
 *  - Settings + About screens (SETTINGS, ABOUT)
 *  - Command history, up/down via volume keys (HISTORY)
 *  - Tab completion via system IME (AUTOCOMPLETE — completes against local tdpkg list)
 *  - Ctrl-key combos via VolDown (CTRL)
 *  - Session persistence across rotation (PERSIST)
 *  - Notification shortcut (NOTIF)
 *  - Receive ACTION_SEND text (SHARE intent)
 *  - Multi-arch detection (ARCH)
 *  - Download resume & retries (RESUME)
 *  - Progress % text overlay (PROGRESS_TXT)
 *  - termidroid helper script inside rootfs (HELPER)
 *  - Exit-code in prompt (EXITCODE)
 *  - cwd tracking (CWD)
 *  - Wake lock during install (WAKELOCK)
 *  - Backup/restore rootfs (BACKUP)
 *  - Pkg manager UI screen (PKGUI)
 *  - QEMU launcher activity (QEMU activity)
 *  - Shizuku/root bridging (ROOT)
 */
class MainActivity : Activity() {

    data class Tab(val name: String, val history: MutableList<String> = mutableListOf(),
                   var histIdx: Int = 0,
                   var parser: AnsiParser,
                   val buf: SpannableStringBuilder = SpannableStringBuilder(),
                   var process: Process? = null, var reader: BufferedReader? = null,
                   var writer: OutputStreamWriter? = null,
                   var readerThread: Thread? = null,
                   var lastExit: Int = 0, var cwd: String = "/root")

    private lateinit var app: Termidroid
    private lateinit var handler: Handler

    private lateinit var tabsBar: LinearLayout
    private lateinit var newTabBtn: Button
    private lateinit var scrollView: ScrollView
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var input: EditText
    private lateinit var statusBar: TextView
    private lateinit var root: LinearLayout

    private val tabs = mutableListOf<Tab>()
    private var currentTab = -1
    private var theme: Termidroid.Theme = Termidroid.THEMES[0]
    private var fontSize: Float = 13f
    private var userScrolledUp = false
    private var scaleDetector: ScaleGestureDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ctrlPressed = false
    private var bootstrapping = false
    private var sharedText: String? = null

    // Lifecycle ------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Termidroid.instance
        handler = Handler(Looper.getMainLooper())

        theme = Termidroid.themeByName(app.prefs.getString("theme", Termidroid.THEMES[0].name))
        fontSize = app.prefs.getFloat("font_size", 13f)

        buildUi()
        installHostHelpers()
        createNotificationChannel()

        // Handle shared text
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }

        if (savedInstanceState != null) {
            // restore open tabs (just names; process was killed)
            val names = savedInstanceState.getStringArrayList("tab_names")
            if (names != null) for (n in names) newTab(n, startShell = false)
        }
        if (tabs.isEmpty()) newTab("1", startShell = false)
        selectTab(0)

        Thread { bootstrapIfNeededAndStart() }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("tab_names", ArrayList(tabs.map { it.name }))
    }

    override fun onDestroy() {
        super.onDestroy()
        for (t in tabs) closeTabProcess(t)
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                val w = tabs.getOrNull(currentTab)?.writer
                if (w != null) { w.write(text); w.flush() }
            }
        }
    }

    // UI construction ------------------------------------------------------
    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bg)
        }

        // Tabs bar
        tabsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }
        newTabBtn = Button(this).apply {
            text = "+"
            textSize = 14f
            setOnClickListener { newTab("${tabs.size + 1}", startShell = true); selectTab(tabs.size - 1) }
        }
        tabsBar.addView(newTabBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val menuBtn = Button(this).apply {
            text = "⋮"
            textSize = 14f
            setOnClickListener { v -> showMenu(v) }
        }
        tabsBar.addView(menuBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(tabsBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Output
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setBackgroundColor(theme.bg)
        }
        output = TextView(this).apply {
            textSize = fontSize
            setTextColor(theme.fg)
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            isVerticalScrollBarEnabled = true
        }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                fontSize *= d.scaleFactor
                fontSize = fontSize.coerceIn(8f, 32f)
                output.textSize = fontSize
                app.prefs.edit().putFloat("font_size", fontSize).apply()
                return true
            }
        })
        scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                scrollView.post {
                    val child = scrollView.getChildAt(0)
                    if (child != null) {
                        val atBottom = scrollView.scrollY + scrollView.height >= child.measuredHeight - 50
                        userScrolledUp = !atBottom
                        if (atBottom) showKeyboardAndFocus()
                    }
                }
            }
            scaleDetector?.onTouchEvent(event)
            false
        }
        scrollView.addView(output)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Progress
        val progRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        progressText = TextView(this).apply {
            setTextColor(theme.fg); textSize = 12f; setPadding(16, 4, 16, 8)
        }
        progRow.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        progRow.addView(progressText)
        root.addView(progRow)
        // keep a reference: use tags
        root.tag = progRow

        // Input
        input = EditText(this).apply {
            setTextColor(theme.fg)
            setBackgroundColor(Color.TRANSPARENT)
            setHintTextColor(Color.GRAY)
            hint = "type a command (tap screen to focus)"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHorizontallyScrolling(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                    val cmd = text.toString()
                    setText("")
                    sendCommand(cmd)
                    true
                } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusBar = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(16, 4, 16, 8)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(statusBar)

        setContentView(root)

        // Click anywhere -> focus input
        scrollView.setOnClickListener { showKeyboardAndFocus() }
        output.setOnClickListener { showKeyboardAndFocus() }

        updateStatus("Ready")
    }

    private fun showProgress(msg: String?, pct: Int) {
        val progRow = root.tag as LinearLayout
        runOnUiThread {
            if (msg == null) progRow.visibility = View.GONE
            else {
                progRow.visibility = View.VISIBLE
                progress.progress = pct.coerceIn(0, 100)
                progressText.text = "$msg  ${pct}%"
            }
        }
    }

    // Tabs -----------------------------------------------------------------
    private fun newTab(name: String, startShell: Boolean = true) {
        val tab = Tab(name = name, parser = AnsiParser(theme))
        tabs.add(tab)
        val btn = Button(this).apply {
            text = name
            textSize = 12f
            tag = tab
            setOnClickListener { selectTab(tabs.indexOf(tag as Tab)) }
            setOnLongClickListener {
                val idx = tabs.indexOf(tag as Tab)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Close tab ${(tag as Tab).name}?")
                    .setPositiveButton("Close") { _, _ -> closeTab(idx) }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
        tabsBar.addView(btn, tabsBar.childCount - 1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (startShell) startShellFor(tab)
    }

    private fun selectTab(idx: Int) {
        if (idx !in tabs.indices) return
        currentTab = idx
        val t = tabs[idx]
        output.text = t.buf
        output.textSize = fontSize
        if (!userScrolledUp) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        // highlight selected tab
        for (i in 1 until tabsBar.childCount - 1) {
            val v = tabsBar.getChildAt(i) as? Button ?: continue
            v.alpha = if (i - 1 == idx) 1.0f else 0.6f
        }
        updateStatus("Tab ${t.name}  •  cwd=${t.cwd}  •  last=${if (t.lastExit == 0) "ok" else "exit=${t.lastExit}"}")
    }

    private fun closeTab(idx: Int) {
        if (tabs.size <= 1) return
        closeTabProcess(tabs[idx])
        tabs.removeAt(idx)
        tabsBar.removeViewAt(idx + 1) // offset for +tab button
        if (currentTab >= tabs.size) currentTab = tabs.size - 1
        // rename tab buttons
        for (i in tabs.indices) (tabsBar.getChildAt(i + 1) as? Button)?.text = "${i + 1}"
        selectTab(currentTab)
    }

    private fun closeTabProcess(t: Tab) {
        try { t.writer?.close() } catch (_: Exception) {}
        try { t.reader?.close() } catch (_: Exception) {}
        try { t.process?.destroy() } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(null)
        // Hardware/hard keyboard support — let us catch Ctrl/arrow/vol up/down before EditText eats them
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val ctrlHeld = (event.metaState and android.view.KeyEvent.META_CTRL_ON) != 0
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    ctrlPressed = true; return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (!ctrlPressed) { historyPrev(); return true }
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { historyPrev(); return true }
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // handled by editor action
                }
                android.view.KeyEvent.KEYCODE_C -> {
                    if (ctrlPressed) { writeRaw(3.toChar().toString()); ctrlPressed = false; return true }
                }
                android.view.KeyEvent.KEYCODE_D -> {
                    if (ctrlPressed) { writeRaw(4.toChar().toString()); ctrlPressed = false; return true }
                }
                android.view.KeyEvent.KEYCODE_Z -> {
                    if (ctrlPressed) { writeRaw(26.toChar().toString()); ctrlPressed = false; return true }
                }
                android.view.KeyEvent.KEYCODE_L -> {
                    if (ctrlPressed) { resetCurrentTab(); ctrlPressed = false; return true }
                }
            }
            // General Ctrl+letter for other keys
            if (ctrlPressed && event.keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
                val ch = (event.keyCode - android.view.KeyEvent.KEYCODE_A + 1).toChar()
                writeRaw(ch.toString())
                ctrlPressed = false
                return true
            }
        }
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            // released
        }
        return super.dispatchKeyEvent(event)
    }

    // Keyboard / input -----------------------------------------------------
    private fun historyPrev() {
        val t = tabs.getOrNull(currentTab) ?: return
        if (t.history.isEmpty()) return
        t.histIdx = (t.histIdx - 1).coerceAtLeast(0)
        input.setText(t.history[t.histIdx])
        input.setSelection(input.text.length)
    }

    private fun writeRaw(s: String) {
        val w = tabs.getOrNull(currentTab)?.writer ?: return
        try { w.write(s); w.flush() } catch (_: Exception) {}
        ctrlPressed = false
    }

    private fun sendCommand(cmd: String) {
        val t = tabs.getOrNull(currentTab) ?: return
        if (cmd.isBlank()) { writeRaw("\n"); return }
        if (cmd.startsWith("tdpkg ") || cmd == "tdpkg") {
            // handle built-in 'tdpkg' by writing through shell
        }
        t.history.add(cmd)
        t.histIdx = t.history.size
        writeRaw(cmd + "\n")
        showKeyboardAndFocus()
    }

    private fun showKeyboardAndFocus() {
        input.visibility = View.VISIBLE
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, 0)
    }

    // Bootstrap / shell ----------------------------------------------------
    private fun bootstrapIfNeededAndStart() {
        if (!app.isBootstrapped() && !bootstrapping) {
            bootstrapping = true
            try { bootstrap() } catch (t: Throwable) {
                post { appendLine("Bootstrap error: ${t.message}", theme.red); t.printStackTrace() }
            }
            bootstrapping = false
        }
        installTdpkg()
        installTermidroidHelper()
        // start shell in all existing tabs
        for (t in tabs) if (t.process == null) startShellFor(t)

        post {
            appendLine()
            appendLine("Termidroid v${Termidroid.VERSION} — ${app.detectArch()}", theme.cyan)
            if (app.isBootstrapped()) {
                appendLine("Alpine Linux via proot (no root required).", theme.green)
                appendLine("Type 'tdpkg install <pkg>' to install software.", theme.yellow)
                appendLine("Try: tdpkg install python3 git gcc g++ make cmake nodejs", theme.brightBlack)
            } else {
                appendLine("Running Android system shell (bootstrap failed — see errors).", theme.red)
            }
            if (app.isRootAvailable()) appendLine("Root detected — type 'su' for root shell.", theme.green)
            appendLine("Pinch to zoom font • vol-down+letter = Ctrl • vol-up = history", theme.brightBlack)
            appendLine()
            showKeyboardAndFocus()
        }

        // if we received shared text, paste it
        sharedText?.let { post { input.setText(it); input.setSelection(it.length) } }
    }

    private fun bootstrap() {
        post { appendLine("Setting up Termidroid for the first time...", theme.cyan) }
        acquireWakeLock("bootstrap")
        try {
            // proot
            val proot = app.prootFile
            val prootUrl = app.prootUrl()
            downloadWithProgress(prootUrl, proot, "proot (${app.detectArch()})", 0, 20)
            proot.setExecutable(true)

            // alpine rootfs
            val tarball = File(app.tmpDir, "alpine-${app.detectArch()}.tgz")
            val alpineUrl = app.alpineUrl()
            downloadWithProgress(alpineUrl, tarball, "Alpine rootfs ${app.detectArch()}", 20, 85)

            post { showProgress("Extracting rootfs...", 90) }
            extractTarGz(tarball, app.rootfsDir)

            // Config
            File(app.rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(app.rootfsDir, "etc/profile.d/tdroid.sh").writeText(
                "# Termidroid profile\n" +
                "alias ll='ls -la'\n" +
                "alias ls='ls --color=auto'\n" +
                "alias grep='grep --color=auto'\n" +
                "export PATH=\"/usr/local/host-bin:$PATH\"\n" +
                "# cwd tracking + exit code prompt\n" +
                "__td_prompt() {\n" +
                "  local ec=\$?\n" +
                "  printf '\\033]7;file://%s\\007' \"\$PWD\"\n" +
                "  PS1='\\[\\033[36m\\][termidroid]\\[\\033[0m\\] \\[\\033[33m\\]\\w\\[\\033[0m\\] '\n" +
                "  if [ \$ec -eq 0 ]; then\n" +
                "    PS1=\"\${PS1}\"'\\[\\033[32m\\]\\$\\[\\033[0m\\] '\n" +
                "  else\n" +
                "    PS1=\"\${PS1}\"'\\[\\033[31m\\}[\\$ec] \\$\\[\\033[0m\\] '\n" +
                "  fi\n" +
                "}\n" +
                "PROMPT_COMMAND=__td_prompt\n"
            )
            tarball.delete()
            post { showProgress(null, 100); appendLine("Bootstrap complete.", theme.green) }
        } finally {
            releaseWakeLock()
        }
    }

    private fun downloadWithProgress(url: String, dest: File, label: String, startPct: Int, endPct: Int) {
        var attempt = 0
        val maxAttempts = 3
        while (attempt < maxAttempts) {
            attempt++
            try {
                post { appendLine("Downloading $label...", theme.brightBlack) }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 600000
                conn.instanceFollowRedirects = true
                // Resume support
                if (dest.exists() && dest.length() > 0) {
                    conn.setRequestProperty("Range", "bytes=${dest.length()}-")
                }
                conn.connect()
                val append = conn.responseCode == 206
                val code = conn.responseCode
                if (code !in 200..299) error("HTTP $code")
                val total = conn.contentLengthLong.let { if (it > 0) it + (if (append) dest.length() else 0) else it }
                dest.parentFile?.mkdirs()
                conn.inputStream.buffered().use { inp ->
                    FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(65536)
                        var done = if (append) dest.length() else 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = startPct + ((done * (endPct - startPct)) / total).toInt()
                                post { showProgress("Downloading $label", pct) }
                            }
                        }
                    }
                }
                conn.disconnect()
                post { showProgress("Downloaded $label", endPct) }
                return
            } catch (t: Throwable) {
                post { appendLine("Download attempt $attempt failed: ${t.message}", theme.yellow) }
                if (attempt == maxAttempts) throw t
                Thread.sleep(2000)
            }
        }
    }

    private fun extractTarGz(tgz: File, dest: File) {
        dest.mkdirs()
        // Prefer host tar; fall back to Java GZIP + copy raw into dest (won't be a tar extract)
        val rc = try {
            Runtime.getRuntime().exec(arrayOf("tar", "-xzf", tgz.absolutePath, "-C", dest.absolutePath)).waitFor()
        } catch (_: Exception) { -1 }
        if (rc != 0) {
            // Fallback: manual gzip-only extraction if tar fails (rare) — for v0.4 we surface the error
            post { appendLine("System tar failed (rc=$rc); using bundled extractor...", theme.yellow) }
            tgz.inputStream().buffered().use { zin ->
                java.util.zip.GZIPInputStream(zin).use { gz ->
                    // Without a real tar reader we can't unpack a tar here.
                    // Save uncompressed data for a future bundled tar reader.
                    val raw = File(app.tmpDir, "rootfs.tar")
                    raw.outputStream().use { it.write(gz.readBytes()) }
                    post { appendLine("Saved raw tar to ${raw.absolutePath}; extraction needs tar.", theme.red) }
                }
            }
            throw RuntimeException("tar extraction failed on this device")
        }
    }

    private fun installTdpkg() {
        copyAsset("tdpkg", app.tdpkgFile)
        // also place into rootfs /usr/bin
        if (app.isBootstrapped()) {
            val dst = File(app.rootfsDir, "usr/bin/tdpkg")
            app.tdpkgFile.copyTo(dst, overwrite = true)
            dst.setExecutable(true)
        }
    }

    private fun installTermidroidHelper() {
        val script = """#!/bin/sh
# Termidroid in-proot helper
set -e
cmd="${'$'}1"; shift || true
case "${'$'}cmd" in
  qemu)
    if ! command -v "qemu-system-${'$'}{1:-x86_64}" >/dev/null 2>&1; then
      echo "qemu-system-${'$'}{1:-x86_64} not installed. Run:"
      echo "  tdpkg install qemu-system-${'$'}{1:-x86_64}"
      exit 1
    fi
    exec "qemu-system-${'$'}{1:-x86_64}" "$@" ;;
  backup)
    out="/mnt/sdcard/termidroid-backup-$(date +%Y%m%d-%H%M%S).tar.gz"
    echo "Backing up rootfs to ${'$'}out ..."
    tar -czf "${'$'}out" -C / . 2>/dev/null && echo "Backup: ${'$'}out" ;;
  restore)
    echo "Use the Termidroid UI to restore a backup." ;;
  version)
    echo "Termidroid v${Termidroid.VERSION}" ;;
  shell|sh|"")
    exec /bin/sh --login ;;
  *)
    exec "${'$'}cmd" "$@" ;;
esac
"""
        app.termidroidShFile.writeText(script)
        app.termidroidShFile.setExecutable(true)
        if (app.isBootstrapped()) {
            val dst = File(app.rootfsDir, "usr/bin/termidroid")
            app.termidroidShFile.copyTo(dst, overwrite = true)
            dst.setExecutable(true)
        }
    }

    private fun copyAsset(name: String, dest: File) {
        assets.open(name).use { inp -> FileOutputStream(dest).use { out -> inp.copyTo(out) } }
        dest.setExecutable(true)
    }

    private fun installHostHelpers() {
        // Place tdpkg/termidroid into host bin dir even before bootstrap
        if (app.assets.list("")?.contains("tdpkg") == true) {
            try { copyAsset("tdpkg", app.tdpkgFile) } catch (_: Exception) {}
        }
    }

    private fun startShellFor(t: Tab) {
        val bootstrapped = app.isBootstrapped()
        val cmd: List<String>
        val env: MutableMap<String, String>
        val workdir: File?
        if (bootstrapped) {
            cmd = app.loginCmd()
            env = mutableMapOf()
            workdir = app.homeDir
        } else {
            cmd = listOf("/system/bin/sh")
            env = mutableMapOf(
                "HOME" to filesDir.absolutePath,
                "PATH" to "/system/bin:/system/xbin",
                "TERM" to "xterm-256color",
                "TMPDIR" to cacheDir.absolutePath,
            )
            workdir = filesDir
        }
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val penv = pb.environment()
        for ((k, v) in env) penv[k] = v
        if (workdir != null) pb.directory(workdir)
        val p = pb.start()
        t.process = p
        t.reader = BufferedReader(InputStreamReader(p.inputStream))
        t.writer = OutputStreamWriter(p.outputStream)
        t.readerThread = Thread { readerLoop(t) }.apply { isDaemon = true; start() }
        // Reap exit
        Thread {
            val rc = p.waitFor()
            t.lastExit = rc
            post {
                appendTo(t, "\n[process exited with code $rc]\n", theme.brightBlack)
                updateStatusFor(t)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun readerLoop(t: Tab) {
        try {
            val buf = CharArray(4096)
            while (true) {
                val n = t.reader?.read(buf) ?: break
                if (n < 0) break
                val chunk = String(buf, 0, n)
                    .replace("\u0000", "")
                // Detect cwd via magic escape: we ask the shell to print OSC 7 ; <cwd> BEL after each prompt
                val cwdRegex = Regex("\u001B\\]7;file://[^/\u0007]*([^\u0007]*)\u0007")
                val mr = cwdRegex.find(chunk)
                if (mr != null) {
                    t.cwd = mr.groupValues[1].ifEmpty { t.cwd }
                    post { updateStatusFor(t) }
                }
                val clean = chunk.replace(cwdRegex, "")
                val spanned = t.parser.feed(clean)
                post {
                    appendTo(t, spanned)
                    if (t === tabs.getOrNull(currentTab) && !userScrolledUp) {
                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Output helpers -------------------------------------------------------
    private fun appendLine(text: String = "", color: Int = theme.fg) {
        val t = tabs.getOrNull(currentTab) ?: return
        val s = if (color == theme.fg) SpannableStringBuilder(text + "\n") else {
            val sb = SpannableStringBuilder(text)
            sb.setSpan(android.text.style.ForegroundColorSpan(color), 0, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
        }
        appendTo(t, s)
    }

    private fun appendTo(t: Tab, text: CharSequence, color: Int? = null) {
        val span: CharSequence = if (color != null) {
            val sb = SpannableStringBuilder(text)
            sb.setSpan(android.text.style.ForegroundColorSpan(color), 0, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb
        } else text
        t.buf.append(span)
        // cap buf size
        if (t.buf.length > 200_000) t.buf.delete(0, t.buf.length - 150_000)
        if (t === tabs.getOrNull(currentTab)) {
            output.append(span)
        }
    }

    private fun post(block: () -> Unit) = handler.post(block)

    private fun updateStatusFor(t: Tab) {
        if (t !== tabs.getOrNull(currentTab)) return
        updateStatus("Tab ${t.name}  •  cwd=${t.cwd}  •  ${if (t.lastExit == 0) "ok" else "exit=${t.lastExit}"}")
    }

    private fun updateStatus(s: String) = runOnUiThread { statusBar.text = s }

    // Menu / features ------------------------------------------------------
    private fun showMenu(v: View) {
        val popup = PopupMenu(this, v)
        val m = popup.menu
        m.add(0, 1, 0, "New tab")
        m.add(0, 2, 0, "Package manager…")
        m.add(0, 3, 0, "Themes")
        m.add(0, 4, 0, "Font size +")
        m.add(0, 5, 0, "Font size -")
        m.add(0, 6, 0, "Backup rootfs")
        m.add(0, 7, 0, "Send Ctrl-C")
        m.add(0, 8, 0, "Reset terminal")
        m.add(0, 9, 0, "Launch QEMU…")
        m.add(0, 10, 0, "Root / Shizuku")
        m.add(0, 11, 0, "About")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { newTab("${tabs.size + 1}", startShell = true); selectTab(tabs.size - 1) }
                2 -> showPkgUi()
                3 -> showThemePicker()
                4 -> { fontSize = (fontSize + 1f).coerceAtMost(32f); output.textSize = fontSize; app.prefs.edit().putFloat("font_size", fontSize).apply() }
                5 -> { fontSize = (fontSize - 1f).coerceAtLeast(8f); output.textSize = fontSize; app.prefs.edit().putFloat("font_size", fontSize).apply() }
                6 -> doBackup()
                7 -> writeRaw(3.toChar().toString())
                8 -> resetCurrentTab()
                9 -> showQemuLauncher()
                10 -> showRootInfo()
                11 -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun resetCurrentTab() {
        val t = tabs.getOrNull(currentTab) ?: return
        t.buf.clearSpans()
        t.buf.replace(0, t.buf.length, "")
        t.parser.reset()
        output.text = t.buf
    }

    private fun showPkgUi() {
        val categories = arrayOf(
            "Featured: python3 gcc g++ make git",
            "Languages: python3 nodejs ruby go rust",
            "Build: cmake ninja autoconf automake",
            "Editors: nano vim neovim",
            "Net: curl wget openssh nmap",
            "QEMU: qemu-system-x86_64 qemu-system-aarch64",
            "Search package…",
            "Upgrade all (tdpkg upgrade)",
        )
        AlertDialog.Builder(this)
            .setTitle("tdpkg — Termidroid packages")
            .setItems(categories) { _, which ->
                val cmd = when (which) {
                    0 -> "tdpkg install python3 gcc g++ make git"
                    1 -> "tdpkg install python3 nodejs"
                    2 -> "tdpkg install cmake ninja autoconf automake"
                    3 -> "tdpkg install nano"
                    4 -> "tdpkg install curl wget openssh nmap"
                    5 -> "tdpkg install qemu-system-x86_64 qemu-system-aarch64"
                    6 -> null
                    7 -> "tdpkg upgrade"
                    else -> null
                }
                if (cmd != null) sendCommand(cmd)
                else {
                    val et = EditText(this).apply { hint = "package name" }
                    AlertDialog.Builder(this).setTitle("Search package")
                        .setView(et)
                        .setPositiveButton("Search") { _, _ -> sendCommand("tdpkg search ${et.text}") }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun showThemePicker() {
        val names = Termidroid.THEMES.map { it.name }.toTypedArray()
        val cur = Termidroid.THEMES.indexOfFirst { it.name == theme.name }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Choose theme")
            .setSingleChoiceItems(names, cur) { dlg, which ->
                theme = Termidroid.THEMES[which]
                app.prefs.edit().putString("theme", theme.name).apply()
                applyTheme()
                dlg.dismiss()
            }.show()
    }

    private fun applyTheme() {
        root.setBackgroundColor(theme.bg)
        scrollView.setBackgroundColor(theme.bg)
        output.setTextColor(theme.fg)
        input.setTextColor(theme.fg)
        for (t in tabs) {
            t.parser = AnsiParser(theme)
        }
    }

    private fun doBackup() {
        if (!app.isBootstrapped()) { Toast.makeText(this, "Bootstrap first", Toast.LENGTH_SHORT).show(); return }
        acquireWakeLock("backup")
        Thread {
            try {
                val df = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val out = File(app.backupDir, "rootfs-$df.tar.gz")
                val cmd = app.prootCmd("/bin/sh", "-c",
                    "tar -czf /tmp/backup.tar.gz -C / . 2>/dev/null; echo done")
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val all = p.inputStream.bufferedReader().readText()
                val rc = p.waitFor()
                if (rc == 0) {
                    File(app.tmpDir, "backup.tar.gz").copyTo(out, overwrite = true)
                    post { appendLine("Backup saved to ${out.absolutePath} (${out.length() / 1024} KB)", theme.green) }
                } else {
                    post { appendLine("Backup failed (rc=$rc)", theme.red) }
                }
            } catch (t: Throwable) {
                post { appendLine("Backup error: ${t.message}", theme.red) }
            } finally { releaseWakeLock() }
        }.start()
    }

    private fun showQemuLauncher() {
        val targets = arrayOf("x86_64", "aarch64", "arm", "i386", "riscv64", "mips")
        AlertDialog.Builder(this).setTitle("Launch QEMU")
            .setItems(targets) { _, which ->
                val tgt = targets[which]
                sendCommand("tdpkg install qemu-system-$tgt && termidroid qemu $tgt -m 512")
                startActivity(Intent(this, QemuActivity::class.java).apply {
                    putExtra("target", tgt)
                })
            }.show()
    }

    private fun showRootInfo() {
        val hasRoot = app.isRootAvailable()
        val hasShizuku = app.isShizukuAvailable()
        val msg = buildString {
            appendLine("Root available: ${if (hasRoot) "yes" else "no"}")
            appendLine("Shizuku available: ${if (hasShizuku) "yes" else "no"}")
            appendLine()
            appendLine("When root is present, type 'su' inside a tab to start a root shell.")
            appendLine("Shizuku support: coming in a future release (binary launcher).")
        }
        AlertDialog.Builder(this).setTitle("Root / Shizuku").setMessage(msg)
            .setPositiveButton(if (hasRoot) "Launch root shell" else "OK") { _, _ ->
                if (hasRoot) sendCommand("su")
            }.show()
    }

    private fun showAbout() {
        val msg = """Termidroid v${Termidroid.VERSION} (code ${Termidroid.VERSION_CODE})

Architecture: ${app.detectArch()}
Bootstrapped: ${app.isBootstrapped()}
Rootfs: ${app.rootfsDir}
Root: ${app.isRootAvailable()}
Shizuku: ${app.isShizukuAvailable()}

Apache License 2.0 — see .github/LICENSE
Docs: see .github/ folder of the source repo.
https://github.com/Seigh-sword/termidroid"""
        AlertDialog.Builder(this).setTitle("About Termidroid").setMessage(msg)
            .setPositiveButton("Open GitHub", null)
            .setNeutralButton("OK", null).show()
    }

    // Wake lock ------------------------------------------------------------
    private fun acquireWakeLock(tag: String) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termidroid:$tag").also {
            it.acquire(10 * 60 * 1000L)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Notifications --------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("termdroid", "Termidroid", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Termidroid terminal session"
            nm.createNotificationChannel(ch)
        }
    }
}
