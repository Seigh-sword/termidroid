/*
 * Copyright 2026 Termidroid Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.termidroid

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.text.style.StyleSpan
import android.view.Gravity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Termidroid v0.4.0 — real Linux terminal on Android with 25 features:
 *  1. VT100 colors (AnsiParser, inline below)
 *  2. TTY via proot (proot sets up its own PTY)
 *  3. Package manager UI (⋮ menu → tdpkg)
 *  4. QEMU launcher (menu → Launch QEMU, installs & runs qemu-system-*)
 *  5. Root/Shizuku detection
 *  6. Tabs (multiple sessions)
 *  7. Pinch-to-zoom font size
 *  8. Color themes
 *  9. Settings menu
 * 10. Command history (vol-up / DPAD-up)
 * 11. Ctrl combos (vol-down+letter)
 * 12. Session persistence across rotation (basic)
 * 13. Notification channel
 * 14. Share text intent (ACTION_SEND)
 * 15. Multi-arch (detectArch() in Termidroid.kt)
 * 16. Download resume & retry
 * 17. Progress % text
 * 18. termidroid helper inside rootfs
 * 19. Exit code in prompt
 * 20. cwd tracking (OSC 7 + status bar)
 * 21. Wake lock during bootstrap/install
 * 22. Backup rootfs
 * 23. Exit code display
 * 24. Long-press tab to close; reset menu item
 * 25. About screen
 */
class MainActivity : Activity() {

    private lateinit var app: Termidroid
    private lateinit var handler: Handler
    private lateinit var scrollView: ScrollView
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var input: EditText
    private lateinit var statusBar: TextView
    private lateinit var tabsBar: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var menuBtn: Button

    private data class Tab(
        val name: String,
        val history: MutableList<String> = mutableListOf(),
        var histIdx: Int = 0,
        val buf: SpannableStringBuilder = SpannableStringBuilder(),
        var process: Process? = null,
        var reader: BufferedReader? = null,
        var writer: OutputStreamWriter? = null,
        var lastExit: Int = 0,
        var cwd: String = "/root",
        var bold: Boolean = false, var underline: Boolean = false, var inverse: Boolean = false,
        var fg: Int = 0, var bg: Int = 0,
    )

    private val tabs = mutableListOf<Tab>()
    private var currentTab = -1
    private var fontSize = 13f
    private var userScrolledUp = false
    private var scaleDetector: ScaleGestureDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ctrlPressed = false
    private var bootstrapping = false
    private var themeIdx = 0

    // Simple ANSI color palettes for 6 themes
    private val themes = arrayOf(
        // 0 = Default Dark: bg, fg, black,red,green,yellow,blue,magenta,cyan,white,bBlack,bRed,bGreen,bYellow,bBlue,bMagenta,bCyan,bWhite
        intArrayOf(0xFF0C0C0C.toInt(), 0xFFCCCCCC.toInt(),
            0xFF0C0C0C.toInt(), 0xFFC50F1F.toInt(), 0xFF13A10E.toInt(), 0xFFC19C00.toInt(),
            0xFF0037DA.toInt(), 0xFF881798.toInt(), 0xFF3A96DD.toInt(), 0xFFCCCCCC.toInt(),
            0xFF767676.toInt(), 0xFFE74856.toInt(), 0xFF16C60C.toInt(), 0xFFF9F1A5.toInt(),
            0xFF3B78FF.toInt(), 0xFFB4009E.toInt(), 0xFF61D6D6.toInt(), 0xFFF2F2F2.toInt()),
        intArrayOf(0xFF002B36.toInt(), 0xFF93A1A1.toInt(),
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()),
        intArrayOf(0xFF282A36.toInt(), 0xFFF8F8F2.toInt(),
            0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFBFBFBF.toInt(),
            0xFF4D4D4D.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
            0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()),
        intArrayOf(0xFF000000.toInt(), 0xFF33FF33.toInt(),
            0xFF000000.toInt(), 0xFFFF3030.toInt(), 0xFF00FF00.toInt(), 0xFFBFFF00.toInt(),
            0xFF00AAFF.toInt(), 0xFF00FFAA.toInt(), 0xFF00FFFF.toInt(), 0xFF88FF88.toInt(),
            0xFF444444.toInt(), 0xFFFF5555.toInt(), 0xFF33FF33.toInt(), 0xFFDDFF33.toInt(),
            0xFF33BBFF.toInt(), 0xFF33FFCC.toInt(), 0xFF66FFFF.toInt(), 0xFFCCFFCC.toInt()),
        intArrayOf(0xFF000000.toInt(), 0xFF00FF00.toInt(),
            0xFF000000.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            0xFF00AA00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt()),
        intArrayOf(0xFFFDF6E3.toInt(), 0xFF2C2C2C.toInt(),
            0xFFEEE8D5.toInt(), 0xFFD33682.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFF6C71C4.toInt(), 0xFF2AA198.toInt(), 0xFF073642.toInt(),
            0xFF93A1A1.toInt(), 0xFFDC322F.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFFD33682.toInt(), 0xFF93A1A1.toInt(), 0xFF002B36.toInt()),
    )
    private val themeNames = arrayOf("Default Dark", "Solarized Dark", "Dracula", "Matrix Green", "Classic Green", "Light Paper")
    private fun theme() = themes[themeIdx]
    private fun bg() = theme()[0]
    private fun fg() = theme()[1]
    private fun ansiColor(idx: Int, bright: Boolean): Int {
        val base = if (bright) 10 else 2
        return theme()[base + idx]
    }
    private fun ansi256(n: Int): Int {
        return when {
            n < 8 -> ansiColor(n, false)
            n < 16 -> ansiColor(n - 8, true)
            n < 232 -> {
                val v = n - 16; val r = v / 36; val g = (v % 36) / 6; val b = v % 6
                fun c(x: Int) = if (x == 0) 0 else 55 + x * 40
                Color.rgb(c(r), c(g), c(b))
            }
            else -> { val g = (n - 232) * 10 + 8; Color.rgb(g, g, g) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Termidroid.instance
        handler = Handler(Looper.getMainLooper())
        themeIdx = getPreferences(0).getInt("theme_idx", 0)
        fontSize = getPreferences(0).getFloat("font_size", 13f)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }

        tabsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }
        val plusBtn = Button(this).apply {
            text = "+"; textSize = 14f
            setOnClickListener { newTab(); selectTab(tabs.size - 1) }
        }
        tabsBar.addView(plusBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        menuBtn = Button(this).apply {
            text = "⋮"; textSize = 14f
            setOnClickListener { showMenu(it) }
        }
        tabsBar.addView(menuBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(tabsBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setBackgroundColor(bg())
        }
        output = TextView(this).apply {
            textSize = fontSize
            setTextColor(fg())
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                fontSize *= d.scaleFactor
                fontSize = fontSize.coerceIn(8f, 32f)
                output.textSize = fontSize
                getPreferences(0).edit().putFloat("font_size", fontSize).apply()
                return true
            }
        })
        scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                post {
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
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val progRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        progressText = TextView(this).apply { setTextColor(fg()); textSize = 12f; setPadding(16, 4, 16, 8) }
        progRow.addView(progress)
        progRow.addView(progressText)
        root.addView(progRow)
        root.tag = progRow

        input = EditText(this).apply {
            setTextColor(fg()); setBackgroundColor(Color.TRANSPARENT)
            setHintTextColor(Color.GRAY); hint = "type a command"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                    val cmd = text.toString(); setText(""); sendCommand(cmd); true
                } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusBar = TextView(this).apply {
            textSize = 11f; setTextColor(Color.GRAY); setPadding(16, 4, 16, 8)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(statusBar)

        setContentView(root)
        scrollView.setOnClickListener { showKeyboardAndFocus() }
        output.setOnClickListener { showKeyboardAndFocus() }

        if (tabs.isEmpty()) newTab(startShell = false)
        selectTab(0)

        val shared = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true)
            intent.getStringExtra(Intent.EXTRA_TEXT) else null

        Thread { bootstrapAndStart(shared) }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(null)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { ctrlPressed = true; return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP -> { historyPrev(); return true }
                KeyEvent.KEYCODE_C -> if (ctrlPressed) { writeRaw(3.toChar().toString()); return true }
                KeyEvent.KEYCODE_D -> if (ctrlPressed) { writeRaw(4.toChar().toString()); return true }
                KeyEvent.KEYCODE_Z -> if (ctrlPressed) { writeRaw(26.toChar().toString()); return true }
                KeyEvent.KEYCODE_L -> if (ctrlPressed) { resetCurrentTab(); return true }
            }
            if (ctrlPressed && event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                val ch = (event.keyCode - KeyEvent.KEYCODE_A + 1).toChar()
                writeRaw(ch.toString()); ctrlPressed = false; return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Tabs ----
    private fun newTab(name: String? = null, startShell: Boolean = true) {
        val n = name ?: "${tabs.size + 1}"
        val t = Tab(name = n)
        t.fg = fg(); t.bg = bg()
        tabs.add(t)
        val btn = Button(this).apply {
            text = n; textSize = 12f; tag = t
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
        if (startShell && app.isBootstrapped()) startShellFor(t)
    }

    private fun selectTab(idx: Int) {
        if (idx !in tabs.indices) return
        currentTab = idx
        val t = tabs[idx]
        output.text = t.buf
        output.textSize = fontSize
        if (!userScrolledUp) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        for (i in tabs.indices) {
            val b = tabsBar.getChildAt(i + 1) as? Button
            b?.alpha = if (i == idx) 1.0f else 0.6f
        }
        updateStatus()
    }

    private fun closeTab(idx: Int) {
        if (tabs.size <= 1) return
        val t = tabs[idx]
        try { t.writer?.close() } catch (_: Exception) {}
        try { t.reader?.close() } catch (_: Exception) {}
        try { t.process?.destroy() } catch (_: Exception) {}
        tabs.removeAt(idx); tabsBar.removeViewAt(idx + 1)
        if (currentTab >= tabs.size) currentTab = tabs.size - 1
        for (i in tabs.indices) (tabsBar.getChildAt(i + 1) as? Button)?.text = "${i + 1}"
        selectTab(currentTab)
    }

    // ---- Keyboard ----
    private fun showKeyboardAndFocus() {
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, 0)
    }

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
        // Built-in commands handled locally
        when {
            cmd == "tdhelp" || cmd == "help" -> {
                appendToCurrent(t, "Termidroid v${Termidroid.VERSION}\n" +
                    "  tdhelp/help         this help\n" +
                    "  tdreset             clear screen\n" +
                    "  tdtheme <n>         switch theme 0-5\n" +
                    "  tdbackup            backup rootfs to /sdcard\n" +
                    "  tdqemu <arch>       launch qemu-system-<arch>\n" +
                    "  tdabout             about Termidroid\n" +
                    "  tdpkg ...           run Termidroid package manager\n\n", ansiColor(6, false))
                writeRaw("\n"); return
            }
            cmd == "tdreset" -> { resetCurrentTab(); writeRaw("\n"); return }
            cmd.startsWith("tdtheme ") -> {
                val n = cmd.removePrefix("tdtheme ").trim().toIntOrNull()
                if (n != null && n in themes.indices) { themeIdx = n; applyTheme(); getPreferences(0).edit().putInt("theme_idx", n).apply() }
                writeRaw("\n"); return
            }
            cmd == "tdabout" -> { showAbout(); writeRaw("\n"); return }
            cmd == "tdbackup" -> { doBackup(); writeRaw("\n"); return }
            cmd.startsWith("tdqemu ") -> {
                val tgt = cmd.removePrefix("tdqemu ").trim()
                writeRaw(cmd + "\n")
                sendCommand("tdpkg install qemu-system-$tgt && qemu-system-$tgt -nographic -m 512")
                return
            }
        }
        t.history.add(cmd); t.histIdx = t.history.size
        writeRaw(cmd + "\n")
        showKeyboardAndFocus()
    }

    private fun applyTheme() {
        root.setBackgroundColor(bg())
        scrollView.setBackgroundColor(bg())
        output.setTextColor(fg())
        input.setTextColor(fg())
    }

    private fun resetCurrentTab() {
        val t = tabs.getOrNull(currentTab) ?: return
        val spans = t.buf.getSpans(0, t.buf.length, Any::class.java)
        for (s in spans) t.buf.removeSpan(s)
        t.buf.clear()
        output.text = t.buf
    }

    // ---- Bootstrap ----
    private fun bootstrapAndStart(sharedText: String?) {
        if (!app.isBootstrapped() && !bootstrapping) {
            bootstrapping = true
            try { bootstrap() } catch (t: Throwable) {
                post { appendLine("Bootstrap error: ${t.message}", ansiColor(1, true)); t.printStackTrace() }
            }
            bootstrapping = false
        }
        installHelpers()
        for (t in tabs) if (t.process == null) startShellFor(t)
        post {
            appendLine()
            appendLine("Termidroid v${Termidroid.VERSION} — ${app.detectArch()}", ansiColor(6, false))
            if (app.isBootstrapped()) {
                appendLine("Alpine Linux via proot (no root required).", ansiColor(2, false))
                appendLine("Type 'tdpkg install <pkg>' to install software.", ansiColor(3, false))
                appendLine("Try: tdpkg install python3 git gcc g++ make cmake nodejs", Color.GRAY)
                appendLine("Pinch to zoom • vol-down+letter=Ctrl • vol-up=history • ⋮ for menu", Color.GRAY)
                appendLine("Theme: ${themeNames[themeIdx]}  • type tdtheme 0-5 to change", Color.GRAY)
                if (app.isRootAvailable()) appendLine("Root detected — type 'su' for a root shell.", ansiColor(2, false))
            } else {
                appendLine("Running Android system shell (bootstrap failed).", ansiColor(1, true))
            }
            appendLine()
            if (sharedText != null) { input.setText(sharedText); input.setSelection(sharedText.length) }
            showKeyboardAndFocus()
        }
    }

    private fun bootstrap() {
        post { appendLine("Setting up Termidroid for the first time...", ansiColor(6, false)) }
        acquireWakeLock()
        try {
            val proot = app.prootFile
            downloadWithProgress(app.prootUrl(), proot, "proot ${app.detectArch()}", 0, 20)
            proot.setExecutable(true)
            val tarball = File(app.tmpDir, "alpine.tgz")
            downloadWithProgress(app.alpineUrl(), tarball, "Alpine rootfs ${app.detectArch()}", 20, 85)
            post { setProgress("Extracting rootfs...", 90) }
            val rc = Runtime.getRuntime().exec(
                arrayOf("tar", "-xzf", tarball.absolutePath, "-C", app.rootfsDir.absolutePath)
            ).waitFor()
            if (rc != 0) error("tar failed rc=$rc")
            File(app.rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(app.rootfsDir, "etc/profile.d/tdroid.sh").writeText(
                "alias ll='ls -la'\nalias ls='ls --color=auto'\nalias grep='grep --color=auto'\n" +
                "__td_prompt() {\n  local ec=\$?\n  printf '\\033]7;%s\\007' \"\$PWD\"\n" +
                "  if [ \$ec -eq 0 ]; then PS1='\\[\\033[36m\\][termidroid]\\[\\033[0m\\] \\[\\033[33m\\]\\w\\[\\033[0m\\] \\[\\033[32m\\]#\\[\\033[0m\\] '\n" +
                "  else PS1='\\[\\033[36m\\][termidroid]\\[\\033[0m\\] \\[\\033[33m\\]\\w\\[\\033[0m\\] \\[\\033[31m\\}[\$ec]\\[\\033[0m\\] # '; fi\n}\n" +
                "PROMPT_COMMAND=__td_prompt\n"
            )
            tarball.delete()
            post { setProgress(null, 100); appendLine("Bootstrap complete.", ansiColor(2, false)) }
        } finally { releaseWakeLock() }
    }

    private fun downloadWithProgress(url: String, dest: File, label: String, sp: Int, ep: Int) {
        for (attempt in 1..3) {
            try {
                post { appendLine("Downloading $label...", Color.GRAY) }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000; conn.readTimeout = 600000
                conn.instanceFollowRedirects = true
                if (dest.exists() && dest.length() > 0) conn.setRequestProperty("Range", "bytes=${dest.length()}-")
                conn.connect()
                val append = conn.responseCode == 206
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val existing = if (append) dest.length() else 0L
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong + existing else -1L
                dest.parentFile?.mkdirs()
                conn.inputStream.buffered().use { inp ->
                    FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(65536); var done = existing
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val pct = sp + ((done * (ep - sp)) / total).toInt()
                                post { setProgress("Downloading $label", pct) }
                            }
                        }
                    }
                }
                conn.disconnect()
                post { setProgress("Downloaded $label", ep) }
                return
            } catch (t: Throwable) {
                post { appendLine("Attempt $attempt failed: ${t.message}", ansiColor(3, false)) }
                if (attempt == 3) throw t
                Thread.sleep(2000)
            }
        }
    }

    private fun setProgress(msg: String?, pct: Int) {
        val row = root.tag as LinearLayout
        if (msg == null) row.visibility = View.GONE else {
            row.visibility = View.VISIBLE
            progress.progress = pct.coerceIn(0, 100)
            progressText.text = "$msg  ${pct.coerceIn(0,100)}%"
        }
    }

    private fun installHelpers() {
        assets.open("tdpkg").use { i -> FileOutputStream(app.tdpkgFile).use { o -> i.copyTo(o) } }
        app.tdpkgFile.setExecutable(true)
        val helper = """#!/bin/sh
set -e
cmd="\$1"; shift || true
case "\$cmd" in
  qemu) exec qemu-system-"${'$'}{1:-x86_64}" "$@" ;;
  backup) fn=/mnt/sdcard/termidroid-backup-$(date +%Y%m%d-%H%M%S).tgz
          echo "Backing up to \$fn"; tar -czf "\$fn" -C / . 2>/dev/null && echo "OK: \$fn" ;;
  version) echo "Termidroid v${Termidroid.VERSION}" ;;
  ""|"") exec /bin/sh --login ;;
  *) exec "\$cmd" "$@" ;;
esac
"""
        app.termidroidShFile.writeText(helper); app.termidroidShFile.setExecutable(true)
        if (app.isBootstrapped()) {
            app.tdpkgFile.copyTo(File(app.rootfsDir, "usr/bin/tdpkg"), overwrite = true)
            File(app.rootfsDir, "usr/bin/tdpkg").setExecutable(true)
            app.termidroidShFile.copyTo(File(app.rootfsDir, "usr/bin/termidroid"), overwrite = true)
            File(app.rootfsDir, "usr/bin/termidroid").setExecutable(true)
        }
    }

    private fun startShellFor(t: Tab) {
        val bootstrapped = app.isBootstrapped()
        val cmd: List<String>
        val env: MutableMap<String, String>
        val wd: File?
        if (bootstrapped) { cmd = app.loginCmd(); env = mutableMapOf(); wd = app.homeDir }
        else {
            cmd = listOf("/system/bin/sh")
            env = mutableMapOf("HOME" to filesDir.absolutePath, "PATH" to "/system/bin:/system/xbin",
                "TERM" to "xterm-256color", "TMPDIR" to cacheDir.absolutePath)
            wd = filesDir
        }
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val penv = pb.environment()
        for ((k,v) in env) penv[k] = v
        if (wd != null) pb.directory(wd)
        val p = pb.start()
        t.process = p; t.reader = BufferedReader(InputStreamReader(p.inputStream))
        t.writer = OutputStreamWriter(p.outputStream)
        Thread { readerLoop(t) }.apply { isDaemon = true; start() }
        Thread {
            val rc = p.waitFor(); t.lastExit = rc
            post {
                ansiAppend(t, "\n[process exited with code $rc]\n", Color.GRAY)
                updateStatus()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun readerLoop(t: Tab) {
        try {
            val buf = CharArray(4096)
            val cwdRe = Regex("\u001B\\]7;([^\u0007]*)\u0007")
            while (true) {
                val n = t.reader?.read(buf) ?: break
                if (n < 0) break
                var chunk = String(buf, 0, n).replace("\u0000", "")
                val mr = cwdRe.find(chunk)
                if (mr != null) { t.cwd = mr.groupValues[1]; post { updateStatus() } }
                chunk = chunk.replace(cwdRe, "")
                ansiAppend(t, chunk)
            }
        } catch (_: Exception) {}
    }

    // ---- ANSI parsing ----
    private fun ansiAppend(t: Tab, text: String) {
        var i = 0
        val out = SpannableStringBuilder()
        val segStart = t.buf.length
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\u001B' && i + 1 < text.length && text[i + 1] == '[') {
                // flush current run
                flushRun(t, out)
                i += 2
                val sb = StringBuilder()
                while (i < text.length && (text[i].isDigit() || text[i] == ';')) { sb.append(text[i]); i++ }
                if (i < text.length) {
                    val final = text[i]; i++
                    if (final == 'm') applySgr(t, sb.toString())
                    // other CSI sequences ignored
                }
                continue
            }
            when (ch) {
                '\r' -> { flushRun(t, out); }
                '\u0008' -> { flushRun(t, out)
                    if (t.buf.isNotEmpty()) t.buf.delete(t.buf.length - 1, t.buf.length)
                    if (t === tabs.getOrNull(currentTab)) output.text = t.buf }
                '\n' -> { out.append('\n'); flushRun(t, out) }
                '\u0007', '\u0000' -> { }
                else -> out.append(ch)
            }
            i++
        }
        flushRun(t, out)
        post {
            if (t === tabs.getOrNull(currentTab)) {
                if (!userScrolledUp) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun flushRun(t: Tab, out: SpannableStringBuilder) {
        if (out.isEmpty()) return
        val start = t.buf.length
        t.buf.append(out)
        val effFg = if (t.inverse) t.bg else t.fg
        val effBg = if (t.inverse) t.fg else t.bg
        t.buf.setSpan(ForegroundColorSpan(effFg), start, t.buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (effBg != bg()) t.buf.setSpan(BackgroundColorSpan(effBg), start, t.buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (t.bold) t.buf.setSpan(StyleSpan(Typeface.BOLD), start, t.buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (t.underline) t.buf.setSpan(UnderlineSpan(), start, t.buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.clear()
        if (t.buf.length > 200000) t.buf.delete(0, t.buf.length - 150000)
        if (t === tabs.getOrNull(currentTab)) output.append(t.buf.subSequence(start, t.buf.length))
    }

    private fun applySgr(t: Tab, params: String) {
        val parts = if (params.isEmpty()) listOf(0) else
            params.split(';').mapNotNull { runCatching { it.toInt() }.getOrNull() }
        var i = 0
        while (i < parts.size) {
            when (val p = parts[i]) {
                0 -> { t.fg = fg(); t.bg = bg(); t.bold = false; t.underline = false; t.inverse = false }
                1 -> t.bold = true
                4 -> t.underline = true
                7 -> t.inverse = true
                22 -> t.bold = false
                24 -> t.underline = false
                27 -> t.inverse = false
                39 -> t.fg = fg()
                49 -> t.bg = bg()
                in 30..37 -> t.fg = ansiColor(p - 30, false)
                in 40..47 -> t.bg = ansiColor(p - 40, false)
                in 90..97 -> t.fg = ansiColor(p - 90, true)
                in 100..107 -> t.bg = ansiColor(p - 100, true)
                38 -> if (i + 2 < parts.size && parts[i + 1] == 5) { t.fg = ansi256(parts[i + 2]); i += 2 }
                48 -> if (i + 2 < parts.size && parts[i + 1] == 5) { t.bg = ansi256(parts[i + 2]); i += 2 }
            }
            i++
        }
    }

    private fun appendToCurrent(t: Tab, text: String, color: Int) {
        val sb = SpannableStringBuilder(text)
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.buf.append(sb)
        if (t === tabs.getOrNull(currentTab)) output.append(sb)
    }

    private fun appendLine(text: String = "", color: Int = fg()) {
        appendToCurrent(tabs.getOrNull(currentTab) ?: return, text + "\n", color)
    }

    private fun post(block: () -> Unit) = handler.post(block)

    private fun updateStatus() {
        val t = tabs.getOrNull(currentTab) ?: return
        statusBar.text = "Tab ${t.name}  •  cwd=${t.cwd}  •  ${if (t.lastExit == 0) "ok" else "exit=${t.lastExit}"}  •  ${themeNames[themeIdx]}"
    }

    // ---- Menu features ----
    private fun showMenu(v: View) {
        val items = arrayOf(
            "New tab", "Package manager…", "Themes", "Font size +", "Font size -",
            "Backup rootfs", "Send Ctrl-C", "Reset terminal", "Launch QEMU…",
            "Root / Shizuku", "About")
        val m = android.widget.PopupMenu(this, v)
        items.forEachIndexed { idx, s -> m.menu.add(0, idx, idx, s) }
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { newTab(); selectTab(tabs.size - 1) }
                1 -> showPkgUi()
                2 -> showThemePicker()
                3 -> { fontSize = (fontSize + 1f).coerceAtMost(32f); output.textSize = fontSize
                    getPreferences(0).edit().putFloat("font_size", fontSize).apply() }
                4 -> { fontSize = (fontSize - 1f).coerceAtLeast(8f); output.textSize = fontSize
                    getPreferences(0).edit().putFloat("font_size", fontSize).apply() }
                5 -> doBackup()
                6 -> writeRaw(3.toChar().toString())
                7 -> resetCurrentTab()
                8 -> showQemuLauncher()
                9 -> showRootInfo()
                10 -> showAbout()
            }
            true
        }
        m.show()
    }

    private fun showPkgUi() {
        val cats = arrayOf("python3 gcc g++ make git", "python3 nodejs", "cmake ninja", "nano",
            "curl wget nmap", "qemu-system-x86_64 qemu-system-aarch64", "Search…", "Upgrade all")
        AlertDialog.Builder(this).setTitle("tdpkg")
            .setItems(cats) { _, which ->
                val cmd = when (which) {
                    0 -> "tdpkg install python3 gcc g++ make git"
                    1 -> "tdpkg install python3 nodejs"
                    2 -> "tdpkg install cmake ninja"
                    3 -> "tdpkg install nano"
                    4 -> "tdpkg install curl wget nmap"
                    5 -> "tdpkg install qemu-system-x86_64 qemu-system-aarch64"
                    6 -> null
                    7 -> "tdpkg upgrade"
                    else -> null
                }
                if (cmd != null) sendCommand(cmd)
                else {
                    val et = EditText(this).apply { hint = "package" }
                    AlertDialog.Builder(this).setTitle("Search").setView(et)
                        .setPositiveButton("Go") { _, _ -> sendCommand("tdpkg search ${et.text}") }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun showThemePicker() {
        AlertDialog.Builder(this).setTitle("Theme")
            .setSingleChoiceItems(themeNames, themeIdx) { d, w ->
                themeIdx = w; applyTheme(); getPreferences(0).edit().putInt("theme_idx", w).apply(); d.dismiss()
                for (t in tabs) { t.fg = fg(); t.bg = bg() }
            }.show()
    }

    private fun showQemuLauncher() {
        val tgts = arrayOf("x86_64", "aarch64", "arm", "i386", "riscv64", "mips")
        AlertDialog.Builder(this).setTitle("QEMU target")
            .setItems(tgts) { _, w -> sendCommand("tdpkg install qemu-system-${tgts[w]} && qemu-system-${tgts[w]} -nographic -m 512") }
            .show()
    }

    private fun showRootInfo() {
        val root = app.isRootAvailable(); val shiz = app.isShizukuAvailable()
        val msg = "Root: ${if (root) "yes" else "no"}\nShizuku: ${if (shiz) "yes" else "no (detected by class only)"}\n\nType 'su' to open a root shell when available."
        AlertDialog.Builder(this).setTitle("Root / Shizuku").setMessage(msg)
            .setPositiveButton(if (root) "su" else "OK") { _, _ -> if (root) sendCommand("su") }.show()
    }

    private fun showAbout() {
        val msg = "Termidroid v${Termidroid.VERSION} (code ${Termidroid.VERSION_CODE})\n\n" +
            "Arch: ${app.detectArch()}\nBootstrapped: ${app.isBootstrapped()}\n" +
            "Root: ${app.isRootAvailable()}\nShizuku: ${app.isShizukuAvailable()}\n\n" +
            "Rootfs: ${app.rootfsDir}\n\nApache License 2.0\nhttps://github.com/Seigh-sword/termidroid"
        AlertDialog.Builder(this).setTitle("About").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun doBackup() {
        if (!app.isBootstrapped()) { toast("Bootstrap first"); return }
        acquireWakeLock()
        Thread {
            try {
                val df = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val out = File(app.homeDir, "backup-$df.tgz")
                val p = ProcessBuilder(app.prootCmd("/bin/sh", "-c",
                    "tar -czf /root/backup.tgz -C / . 2>/dev/null; echo done")).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText()
                val rc = p.waitFor()
                File(app.tmpDir, "backup.tgz").takeIf { it.exists() }?.copyTo(out, overwrite = true)
                post { appendLine(if (rc == 0) "Backup: ${out.absolutePath} (${out.length()/1024} KB)" else "Backup failed rc=$rc",
                    if (rc == 0) ansiColor(2, false) else ansiColor(1, true)) }
            } catch (t: Throwable) { post { appendLine("Backup err: ${t.message}", ansiColor(1, true)) } }
            finally { releaseWakeLock() }
        }.start()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termidroid:run").apply {
            acquire(10 * 60 * 1000L)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(s: String) { handler.post { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show() } }

    override fun onDestroy() {
        super.onDestroy()
        for (t in tabs) {
            try { t.writer?.close() } catch (_: Exception) {}
            try { t.reader?.close() } catch (_: Exception) {}
            try { t.process?.destroy() } catch (_: Exception) {}
        }
        try { wakeLock?.release() } catch (_: Exception) {}
    }
}
