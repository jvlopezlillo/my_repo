package com.futmondo.core

/** Tono de una línea de resultado (la interfaz le pone color). */
enum class Tone { NORMAL, GOOD, WARN, BAD, MUTED }

data class Line(val text: String, val tone: Tone = Tone.NORMAL)

/** Bloque de un informe: la interfaz lo dibuja como tarjeta y [Sections.toText] como texto. */
data class Section(val title: String, val lines: List<Line>)

object Sections {
    fun toText(sections: List<Section>): String =
        sections.filter { it.title.isNotEmpty() || it.lines.isNotEmpty() }.joinToString("\n\n") { s ->
            (listOf(s.title).filter { it.isNotEmpty() } + s.lines.map { "  " + it.text }).joinToString("\n")
        }
}
