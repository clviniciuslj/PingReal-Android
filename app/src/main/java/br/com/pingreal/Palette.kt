package br.com.pingreal

import android.graphics.Color

/**
 * Cores do app, em um só lugar. Há duas paletas (escura e clara, no estilo do app da UniFi);
 * [apply] escolhe qual vale e deve ser chamada antes de montar as telas.
 */
object Palette {
    var isDark = true
        private set

    var bg = 0; private set
    var panel = 0; private set
    var panelStroke = 0; private set
    var consoleBg = 0; private set
    var accent = 0; private set
    var onAccent = 0; private set
    var green = 0; private set
    var cyan = 0; private set
    var violet = 0; private set
    var orange = 0; private set
    var amber = 0; private set
    var red = 0; private set
    var white = 0; private set
    var text = 0; private set
    var muted = 0; private set

    /** Cores das curvas quando há vários alvos. */
    var series = IntArray(4); private set

    /** Branco translúcido no escuro, preto translúcido no claro (pressionado, trilhas, grades). */
    fun overlay(alpha: Int): Int =
        if (isDark) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)

    init { apply(true) }

    fun apply(dark: Boolean) {
        isDark = dark
        if (dark) {
            bg = Color.rgb(10, 12, 15)
            panel = Color.rgb(20, 23, 28)
            panelStroke = Color.rgb(38, 43, 51)
            consoleBg = Color.rgb(13, 15, 19)
            accent = Color.rgb(0, 111, 255)
            onAccent = Color.WHITE
            green = Color.rgb(52, 199, 123)
            cyan = Color.rgb(61, 169, 252)
            violet = Color.rgb(159, 122, 234)
            orange = Color.rgb(246, 153, 63)
            amber = Color.rgb(240, 187, 64)
            red = Color.rgb(240, 84, 84)
            white = Color.rgb(237, 240, 245)
            text = Color.rgb(188, 195, 206)
            muted = Color.rgb(125, 134, 148)
        } else {
            bg = Color.rgb(243, 245, 249)
            panel = Color.WHITE
            panelStroke = Color.rgb(222, 227, 235)
            consoleBg = Color.rgb(237, 240, 246)
            accent = Color.rgb(0, 111, 255)
            onAccent = Color.WHITE
            green = Color.rgb(16, 163, 90)
            cyan = Color.rgb(0, 132, 214)
            violet = Color.rgb(124, 77, 220)
            orange = Color.rgb(222, 108, 14)
            amber = Color.rgb(184, 125, 0)
            red = Color.rgb(213, 50, 62)
            white = Color.rgb(17, 24, 39)
            text = Color.rgb(55, 65, 81)
            muted = Color.rgb(107, 118, 134)
        }
        series = intArrayOf(accent, green, violet, orange)
    }
}
