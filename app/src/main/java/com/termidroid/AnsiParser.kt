/*
 * Copyright 2026 Termidroid Contributors
 * Licensed under the Apache License, Version 2.0
 *
 * A streaming ANSI escape-code parser that converts a raw byte stream into
 * colored Spannable text suitable for rendering in a TextView.  Supports:
 *   - SGR attributes (30-37, 90-97 fg; 40-47, 100-107 bg; 0 reset; 1 bold; 4 underline; 7 inverse)
 *   - 256-color palette (ESC[38;5;Nm and ESC[48;5;Nm)
 *   - Carriage return / line feed / backspace handling
 *   - Cursor positioning sequences are *mostly* ignored (we are a "dumb"
 *     terminal that only scrolls forward) — full-screen TUI apps will look
 *     jumbled until we add a real screen buffer.
 */
package com.termidroid

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

class AnsiParser(private val theme: Termidroid.Theme) {

    private var buf = StringBuilder()
    private var fg: Int = theme.fg
    private var bg: Int = theme.bg
    private var bold = false
    private var underline = false
    private var inverse = false

    private val out = SpannableStringBuilder()

    private enum class State { NORM, ESC, CSI, OSC }
    private var state = State.NORM
    private var csi = StringBuilder()
    private var osc = StringBuilder()

    fun reset() {
        buf.setLength(0); out.clear()
        fg = theme.fg; bg = theme.bg
        bold = false; underline = false; inverse = false
        state = State.NORM; csi.setLength(0); osc.setLength(0)
    }

    fun feed(data: String): Spanned {
        // Track where this chunk starts
        val start = out.length
        for (ch in data) process(ch)
        flushText()
        return out.subSequence(start, out.length) as Spanned
    }

    private fun process(ch: Char) {
        when (state) {
            State.NORM -> when (ch) {
                '\u001B' -> { flushText(); state = State.ESC }
                '\r' -> flushText()  // CR: drop (LF advances line; CR moves cursor which we ignore)
                '\u0008' -> { // backspace: remove last char in buf
                    flushText()
                    if (out.isNotEmpty()) out.delete(out.length - 1, out.length)
                }
                '\u0007' -> { } // BEL ignore
                '\u0000' -> { } // NUL ignore
                '\n' -> { buf.append('\n'); flushText() }
                else -> buf.append(ch)
            }
            State.ESC -> when (ch) {
                '[' -> { csi.setLength(0); state = State.CSI }
                ']' -> { osc.setLength(0); state = State.OSC }
                else -> state = State.NORM // unknown 2-char seq, ignore
            }
            State.CSI -> {
                // CSI collects until a letter (final byte 0x40-0x7E)
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch == '@' || ch == '~' || ch == '`') {
                    dispatchCSI(csi.toString(), ch)
                    state = State.NORM
                } else {
                    csi.append(ch)
                    if (csi.length > 32) { state = State.NORM } // safety
                }
            }
            State.OSC -> {
                // OSC ends on BEL or ST (\u001B\\)
                if (ch == '\u0007') { state = State.NORM }
                else if (ch == '\u001B') { osc.append(ch); /* wait for backslash */ }
                else if (osc.endsWith('\u001B') && ch == '\\') { state = State.NORM }
                else { osc.append(ch); if (osc.length > 256) state = State.NORM }
            }
        }
    }

    private fun flushText() {
        if (buf.isEmpty()) return
        val start = out.length
        out.append(buf.toString())
        buf.setLength(0)
        val effFg = if (inverse) bg else fg
        val effBg = if (inverse) fg else bg
        out.setSpan(ForegroundColorSpan(effFg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (effBg != theme.bg) {
            out.setSpan(BackgroundColorSpan(effBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (underline) out.setSpan(UnderlineSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun dispatchCSI(params: String, final: Char) {
        when (final) {
            'm' -> applySgr(params)
            // Cursor movement / erase: we mostly ignore them but keep basic erase-line
            'J', 'K' -> { } // clear screen/line (ignore — we append-only)
            'H', 'f', 'A', 'B', 'C', 'D', 'G' -> { } // move cursor: ignore
            'h', 'l' -> { } // mode set/reset: ignore
            '?', 'n', 's', 'u' -> { }
            else -> { }
        }
    }

    private fun applySgr(params: String) {
        flushText()
        val parts = if (params.isEmpty()) listOf(0) else
            params.split(';').mapNotNull { it.trim().toIntOrNull() }
        var i = 0
        while (i < parts.size) {
            when (val p = parts[i]) {
                0 -> { fg = theme.fg; bg = theme.bg; bold = false; underline = false; inverse = false }
                1 -> bold = true
                4 -> underline = true
                7 -> inverse = true
                22 -> bold = false
                24 -> underline = false
                27 -> inverse = false
                39 -> fg = theme.fg
                49 -> bg = theme.bg
                in 30..37 -> fg = color8(p - 30, false)
                in 40..47 -> bg = color8(p - 40, false)
                in 90..97 -> fg = color8(p - 90, true)
                in 100..107 -> bg = color8(p - 100, true)
                38 -> if (i + 1 < parts.size) {
                    when (parts[i + 1]) {
                        5 -> { if (i + 2 < parts.size) { fg = color256(parts[i + 2]); i += 2 } }
                        2 -> { if (i + 4 < parts.size) { fg = Color.rgb(parts[i+2], parts[i+3], parts[i+4]); i += 4 } }
                    }
                }
                48 -> if (i + 1 < parts.size) {
                    when (parts[i + 1]) {
                        5 -> { if (i + 2 < parts.size) { bg = color256(parts[i + 2]); i += 2 } }
                        2 -> { if (i + 4 < parts.size) { bg = Color.rgb(parts[i+2], parts[i+3], parts[i+4]); i += 4 } }
                    }
                }
            }
            i++
        }
    }

    private fun color8(idx: Int, bright: Boolean): Int = when (idx) {
        0 -> if (bright) theme.brightBlack else theme.black
        1 -> if (bright) theme.brightRed else theme.red
        2 -> if (bright) theme.brightGreen else theme.green
        3 -> if (bright) theme.brightYellow else theme.yellow
        4 -> if (bright) theme.brightBlue else theme.blue
        5 -> if (bright) theme.brightMagenta else theme.magenta
        6 -> if (bright) theme.brightCyan else theme.cyan
        7 -> if (bright) theme.brightWhite else theme.white
        else -> theme.fg
    }

    private fun color256(n: Int): Int = when {
        n < 8 -> color8(n, false)
        n < 16 -> color8(n - 8, true)
        n < 232 -> {
            val v = n - 16
            val r = (v / 36); val g = (v % 36) / 6; val b = v % 6
            fun c(x: Int) = if (x == 0) 0 else 55 + x * 40
            Color.rgb(c(r), c(g), c(b))
        }
        else -> {
            val g = (n - 232) * 10 + 8
            Color.rgb(g, g, g)
        }
    }
}
