package musics.elements

import musics.common.Ratio
import musics.parameters.Key
import java.util.*

interface Lilypond {
    fun toLilypond(): String
}

/*

See pitch for this information!
The accenting of the scale in Helmholtz notation always starts on the note C and ends at B (e.g. C D E F G A B).
The note C is shown in different octaves by using upper-case letters for low notes, and lower-case letters for high notes,
and adding sub-primes and primes in the following sequence: C͵͵ C͵ C c c′ c″ c‴
(or ,,C ,C C c c′ c″ c‴ or C⸜⸜ C⸜ C c c⸝ c⸝⸝ c⸝⸝⸝) and so on.

Middle C is designated c′, therefore the octave from middle C upwards is c′–b′.
*/
val sharp2: String = "\u266F"
val flat2: String = "\u266D"

private val helmholtzTable = listOf(
    ",,,", ",,", ",", "", "", "'", "''", "'''", "''''", "'''''", "''''''", "'''''''"
)

fun toLilypondNameWithOctave(name: String, octave: Int): String {
    val symbol = helmholtzTable[octave]
    val noteName = toLilypondName(name)
    val result = noteName.replaceFirstChar {
        if (octave <= 3) it.titlecase(Locale.getDefault())
        else it.lowercase(Locale.getDefault())
    }
    return "$result$symbol"
}

//fun asLilypondPitch(number: Int, key: Key): String = asNoteName(number, key.signature)

fun asLilypondNoteNameWithOctave(number: Int, signature: Signature): String {
    if (number == 0) return "r"
    val octave = number / 12 - 1
    val name = asNoteName(number, signature)
    return toLilypondNameWithOctave(name, octave)
}

fun asLilypondNoteNameWithOctave(number: Int, key: Key): String = asLilypondNoteNameWithOctave(number, key.signature)

fun asLilypondNoteNameWithOctave(number: Int) = asNoteNameWithOctave(number, Signature.C)

fun toLilypondName(name: String): String {
    val first = name.substring(0, 1)
    val rest = name.substring(1)
        .replace("#", "is")
        .replace("b", "es")
    return (first + rest)
        .replace("ee", "e")
        .replace("ae", "a")
}

fun lilypondDururation(dur: Ratio): String {
    // TODO: implement this function
    val (num, denom) = dur.destructured()
    return if (num <= denom) {
        when (num) {
            2 -> "${denom / 2.0}"
            3 -> "${denom / 2}."
            7 -> "${denom / 4}.."
            else -> "$denom"
        }
    } else when (dur) {
        Ratio(3, 2) -> "1."
        Ratio(7, 4) -> "1.."

        Ratio(2, 1) -> "breve"
        Ratio(3, 1) -> "breve."
        Ratio(7, 2) -> "breve.."

        Ratio(4, 1) -> "longa"
        Ratio(6, 1) -> "longa."
        Ratio(7, 1) -> "longa.."

        Ratio(8, 1) -> "maxima"
        Ratio(12, 1) -> "maxima."
        Ratio(14, 1) -> "maxima.."

        else -> "$denom*$num"
    }
}
