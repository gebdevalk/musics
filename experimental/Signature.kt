package musics.elements

import musics.common.CyclicList
import musics.common.cyclicListOf
import musics.parts.flat
import musics.parts.sharp
import java.util.*

// C d♭ D f♯ G a♭,
enum class Signature(
    val accidentals: Int,
    val rising: List<String>,
    val falling: List<String>
) {
    Deses(
        -12,
        listOf("dbb", "db", "ebb", "eb", "fb", "gbb", "gb", "abb", "ab", "bbb", "bb", "cb"),
        listOf("dbb", "db", "ebb", "eb", "fb", "gbb", "", "abb", "ab", "bbb", "bb", "cb")
    ),
    Ases(
        -11,
        listOf("dbb", "db", "ebb", "eb", "fb", "f", "gb", "abb", "ab", "bbb", "bb", "cb"),
        listOf("dbb", "db", "ebb", "eb", "fb", "f", "gb", "abb", "ab", "bbb", "bb", "cb")
    ),
    Eses(
        -10,
        listOf("c", "db", "ebb", "eb", "fb", "f", "gb", "abb", "ab", "bbb", "bb", "cb"),
        listOf("c", "db", "ebb", "eb", "fb", "f", "gb", "abb", "ab", "bbb", "bb", "cb")
    ),
    Beses(
        -9,
        listOf("c", "db", "ebb", "eb", "fb", "f", "gb", "g", "ab", "bbb", "bb", "cb"),
        listOf("c", "db", "ebb", "eb", "fb", "f", "gb", "g", "ab", "bbb", "bb", "cb")
    ),
    Fes(
        -8,
        listOf("c", "db", "d", "eb", "fb", "f", "gb", "g", "ab", "bbb", "bb", "cb"),
        listOf("c", "db", "d", "eb", "fb", "f", "gb", "g", "ab", "bbb", "bb", "cb")
    ),
    Ces(
        -7,
        listOf("c", "db", "d", "eb", "fb", "f", "gb", "g", "ab", "a", "bb", "cb"),
        listOf("c", "db", "d", "eb", "fb", "f", "gb", "g", "ab", "a", "bb", "cb")
    ),
    Ges(
        -6,

        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "cb"),
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "cb")
    ),
    Des(
        -5,
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "b"),
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "b")
    ),
    As(
        -4,
        listOf("c", "db", "d", "eb", "e", "f", "f#", "g", "ab", "a", "bb", "b"),
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "b")
    ),
    Es(
        -3,
        listOf("c", "c#", "d", "eb", "e", "f", "f#", "g", "ab", "a", "bb", "b"),
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "b")
    ),
    Bes(
        -2,
        listOf("c", "c#", "d", "eb", "e", "f", "f#", "g", "g#", "a", "bb", "b"),
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "b")
    ),
    F(
        -1,
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "bb", "b"),
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "b")
    ),
    C(
        0,
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"),
        listOf("c", "db", "d", "eb", "e", "f", "gb", "g", "ab", "a", "bb", "b")
    ),
    G(
        1,
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"),
        listOf("c", "db", "d", "eb", "e", "f", "f#", "g", "ab", "a", "bb", "b")
    ),
    D(
        2,
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"),
        listOf("c", "c#", "d", "eb", "e", "f", "f#", "g", "ab", "a", "bb", "b")
    ),
    A(
        3,
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"),
        listOf("c", "c#", "d", "eb", "e", "f", "f#", "g", "g#", "a", "bb", "b")
    ),
    E(
        4,
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"),
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "bb", "b")
    ),
    B(
        5,
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"),
        listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b")
    ),
    Fis(
        6,
        listOf("c", "c#", "d", "d#", "e", "e#", "f#", "g", "g#", "a", "a#", "b"),
        listOf("c", "c#", "d", "d#", "e", "e#", "f#", "g", "g#", "a", "a#", "b")
    ),
    Cis(
        7,
        listOf("b#", "c#", "d", "d#", "e", "e#", "f#", "g", "g#", "a", "a#", "b"),
        listOf("b#", "c#", "d", "d#", "e", "e#", "f#", "g", "g#", "a", "a#", "b")
    ),
    Gis(
        8,
        listOf("b#", "c#", "d", "d#", "e", "e#", "f#", "f##", "g#", "a", "a#", "b"),
        listOf("b#", "c#", "d", "d#", "e", "e#", "f#", "f##", "g#", "a", "a#", "b")
    ),
    Dis(
        9,
        listOf("b#", "c#", "c##", "d#", "e", "e#", "f#", "f##", "g#", "a", "a#", "b"),
        listOf("b#", "c#", "c##", "d#", "e", "e#", "f#", "f##", "g#", "a", "a#", "b")
    ),
    Ais(
        10,
        listOf("b#", "c#", "c##", "d#", "e", "e#", "f#", "f##", "g#", "g##", "a#", "b"),
        listOf("b#", "c#", "c##", "d#", "e", "e#", "f#", "f##", "g#", "g##", "a#", "b")
    ),
    Eis(
        11,
        listOf("b#", "c#", "c##", "d#", "d##", "e#", "f#", "f##", "g#", "g##", "a#", "b"),
        listOf("b#", "c#", "c##", "d#", "d##", "e#", "f#", "f##", "g#", "g##", "a#", "b")
    ),
    Bis(
        12,
        listOf("b#", "c#", "c##", "d#", "d##", "e#", "f#", "f##", "g#", "g##", "a#", "a##"),
        listOf("b#", "c#", "c##", "d#", "d##", "e#", "f#", "f##", "g#", "g##", "a#", "a##")
    );

//    fun modulate(delta: Int): KeyName = COF.modulate(this, delta)
//
//    fun transpose(delta: Int): KeyName =
//        COF.modulate(this, COF.stepsToFifths(delta))

//    fun transpose(signature: KeyName, delta: Int): KeyName =
//        COF.modulate(signature, COF.stepsToFifths(delta))

    override fun toString(): String =
        name.replace("is", "#")
            .replace("es", "b")
            .replace("s", "b")

    fun toLilypond(): String = name.replace("-1", "").replace("0", "'")
        .replace("s", sharp).replace("es", flat).lowercase(Locale.getDefault())

    companion object {

        private val signatureList: CyclicList<Signature> = cyclicListOf(*values())
        fun get(i: Int): Signature = signatureList[i]

//        val signatureMap = mapOf(
//            "Deses" to Deses,
//            "Ases" to Ases,
//            "Eses" to Eses,
//            "Beses" to Beses,
//            "Ces" to Ces,
//            "Ges" to Ges,
//            "Des" to Des,
//            "As" to As,
//            "Es" to Es,
//            "Bes" to Bes,
//            "F" to F,
//            "C" to C,
//            "G" to G,
//            "D" to D,
//            "A" to A,
//            "E" to E,
//            "B" to B,
//            "Fis" to Fis,
//            "Cis" to Cis,
//            "Gis" to Gis,
//            "Dis" to Dis,
//            "Ais" to Ais,
//            "Eis" to Eis,
//            "Bis" to Bis,
//
//            "ces" to Ces,
//            "ges" to Ges,
//            "des" to Des,
//            "aes" to As,
//            "as" to As,
//            "es" to Es,
//            "ees" to Es,
//            "bes" to Bes,
//            "f" to F,
//            "c" to C,
//            "g" to G,
//            "d" to D,
//            "a" to A,
//            "e" to E,
//            "b" to B,
//            "fis" to Fis,
//            "cis" to Cis
//        )
    }

}


