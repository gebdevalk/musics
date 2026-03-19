package musics.elements

import musics.parameters.Key
import musics.parts.chord
import musics.parts.chordPitches
import musics.parts.flat
import musics.parts.sharp
import java.util.*

// C d♭ D f♯ G a♭,
/**
 * A Keys member defines the name, relative root and location in the COF
 *
 * Members are arranged in the cycle of fifth
 *
 * @property accidentals the number of accidentals relative to C,
 * positive accidentals ar upward in the cycle of fifth, negative accidentals downwards
 * @property root number of semitones, determining relative position on the semitone scale
 */
enum class Keys(
    private val accidentals: Int,
    val root: Int //
) {
    Deses(-12, 0),
    Ases(-11, 7),
    Eses(-10, 2),
    Beses(-9, 9),
    Fes(-8, 4),
    Ces(-7, 11),
    Ges(-6, 6),
    Des(-5, 1),
    As(-4, 8),
    Es(-3, 3),
    Bes(-2, 10),
    F(-1, 5),
    C(0, 0),
    G(1, 7),
    D(2, 2),
    A(3, 9),
    E(4, 4),
    B(5, 11),
    Fis(6, 6),
    Cis(7, 1),
    Gis(8, 8),
    Dis(9, 3),
    Ais(10, 10),
    Eis(11, 4),
    Bis(12, 0);

    fun modulate(fifths: Int): Keys =
        COF.modulate(this, fifths)

    fun transpose(semitones: Int): Keys =
        COF.transpose(this, semitones)

    override fun toString(): String =
        name.replace("is", "#")
            .replace("es", "b")
            .replace("s", "b")

    fun toLilypond(): String = name.replace("-1", "").replace("0", "'")
        .replace("s", sharp).replace("es", flat).lowercase(Locale.getDefault())

    val major = Key(this, Major, 0)
    val minor = Key(this, Minor, -3)
    val melodicMinor = Key(this, MelodicMinor, -3)
    val harmonicMinor = Key(this, HarmonicMinor, -3)

    val ionian = Key(this, Ionian, 0)
    val dorian = Key(this, Dorian, -2)
    val phrygian = Key(this, Phrygian, -4)
    val lydian = Key(this, Lydian, +1)
    val mixolydian = Key(this, Mixolydian, -1)
    val aeolian = Key(this, Aeolian, -3)
    val locrian = Key(this, Locrian, -5)

    val hypoionian = Key(this, Hypoionian, -1)
    val hypodorian = Key(this, Hypodorian, -3)
    val hypophrygian = Key(this, Hypophrygian, -5)
    val hypolydian = Key(this, Hypolydian, 0)
    val hypomixolydian = Key(this, Hypomixolydian, -2)
    val hypoaeolian = Key(this, Hypoaeolian, -4)
    val hypolocrian = Key(this, Hypolocrian, -5)

    val kurd = Key(this, Kurd, -5)
    val gypsy = Key(this, Gypsy, -5)
    val ahavohRabboh = Key(this, AhavohRabboh, 0)
    val hungarian = Key(this, Hungarian, -5)
    val charhargah = Key(this, Charhargah, -4)
    val spanish = Key(this, Spanish, -4)

    val pentatonicMajor = Key(this, PentatonicMajor, 0)
    val pentatonicMinor = Key(this, PentatonicMinor, -3)

    val wholeTone = Key(this, WholeTone, 0)

    val majorHexatonic = Key(this, MajorHexatonic, 0)
    val minorHexatonic = Key(this, MinorHexatonic, -2)
    val augmented = Key(this, Augmented, -3)
    val promethean = Key(this, Promethean, -5)
    val blues = Key(this, Blues, 0)
    val tritone = Key(this, Tritone, -4)
    val twoSemitoneTritone = Key(this, TwoSemitoneTritone, -2)
    val byzantine = Key(this, Byzantine, -4)

    val I = chord(major, root, chordPitches["M"]!!)
    val II = chord(major, root + 2, chordPitches["m"]!!)
    val III = chord(major, root + 4, chordPitches["m"]!!)
    val IV = chord(major, root + 5, chordPitches["M"]!!)
    val V = chord(major, root + 7, chordPitches["M"]!!)
    val VI = chord(major, root + 9, chordPitches["m"]!!)
    val VII = chord(major, root + 11, chordPitches["dim"]!!)

    val Im = chord(minor, root, chordPitches["m"]!!)
    val IIm = chord(minor, root + 2, chordPitches["dim"]!!)
    val IIIm = chord(minor, root + 3, chordPitches["M"]!!)
    val IVm = chord(minor, root + 5, chordPitches["m"]!!)
    val Vm = chord(minor, root + 7, chordPitches["m"]!!)
    val VIm = chord(minor, root + 8, chordPitches["M"]!!)
    val VIIm = chord(minor, root + 10, chordPitches["M"]!!)


    val Ih = chord(harmonicMinor, root, chordPitches["m"]!!)
    val IIh = chord(harmonicMinor, root + 2, chordPitches["dim"]!!)
    val IIIh = chord(harmonicMinor, root + 3, chordPitches["aug"]!!)
    val IVh = chord(harmonicMinor, root + 5, chordPitches["m"]!!)
    val Vh = chord(harmonicMinor, root + 7, chordPitches["M"]!!)
    val VIh = chord(harmonicMinor, root + 8, chordPitches["M"]!!)
    val VIIh = chord(harmonicMinor, root + 11, chordPitches["dim"]!!)


    val maj = chord(major, root, chordPitches["M"]!!)
    val maj6 = chord(minor, root, chordPitches["M6"]!!)
    val maj46 = chord(major, root, chordPitches["M46"]!!)

    val min = chord(minor, root, chordPitches["m"]!!)
    val min6 = chord(minor, root, chordPitches["m6"]!!)
    val min46 = chord(minor, root, chordPitches["m46"]!!)

    val dim = chord(locrian, root, chordPitches["dim"]!!)
    val aug = chord(Key(this, HM2, 0), root, chordPitches["aug"]!!) //


    val dom7 = chord(locrian, root, chordPitches["D7"]!!)
    val dom56 = chord(locrian, root, chordPitches["D56"]!!)
    val dom34 = chord(minor, root, chordPitches["D34"]!!)
    val dom2 = chord(mixolydian, root, chordPitches["D2"]!!)

    val maj7 = chord(major, root, chordPitches["M7"]!!)
    val maj56 = chord(minor, root, chordPitches["M56"]!!)
    val maj34 = chord(major, root, chordPitches["M34"]!!)
    val maj2 = chord(locrian, root, chordPitches["M2"]!!)

    val min7 = chord(minor, root, chordPitches["m7"]!!)
    val min56 = chord(minor, root, chordPitches["m56"]!!)
    val min34 = chord(minor, root, chordPitches["m34"]!!)
    val min2 = chord(minor, root, chordPitches["m2"]!!)

    val dim7 = chord(locrian, root, chordPitches["dim7"]!!)
    val hdim7 = chord(locrian, root, chordPitches["hdim7"]!!)
    val aug7 = chord(dorian, root, chordPitches["aug7"]!!)
    val augM7 = chord(major, root, chordPitches["augM7"]!!)

    val keyMap = mapOf(
        "major" to major,
        "minor" to minor,
        "melodicMinor" to melodicMinor,
        "harmonicMinor" to harmonicMinor,
        "ionian" to ionian,
        "dorian" to dorian,
        "phrygian" to phrygian,
        "lydian" to lydian,
        "mixolydian" to mixolydian,
        "aeolian" to aeolian,
        "locrian" to locrian,
        "hypoionian" to hypoionian,
        "hypodorian" to hypodorian,
        "hypophrygian" to hypophrygian,
        "hypolydian" to hypolydian,
        "hypomixolydian" to hypomixolydian,
        "hypoaeolian" to hypoaeolian,
        "hypolocrian" to hypolocrian,
        "kurd" to kurd,
        "gypsy" to gypsy,
        "ahavohRabboh" to ahavohRabboh,
        "hungarian" to hungarian,
        "charhargah" to charhargah,
        "spanish" to spanish,
        "pentatonicMajor" to pentatonicMajor,
        "pentatonicMinor" to pentatonicMinor,
        "wholeTone" to wholeTone,
        "majorHexatonic" to majorHexatonic,
        "minorHexatonic" to minorHexatonic,
        "augmented" to augmented,
        "promethean" to promethean,
        "blues" to blues,
        "tritone" to tritone,
        "twoSemitoneTritone" to twoSemitoneTritone,
        "byzantine" to byzantine
    )

    val chordMap = mapOf(
        "maj" to maj,
        "maj6" to maj6,
        "maj46" to maj46,
        "min" to min,
        "min6" to min6,
        "min46" to min46,
        "dim" to dim,
        "aug" to aug,
        "dom7" to dom7,
        "dom56" to dom56,
        "dom34" to dom34,
        "dom2" to dom2,
        "maj7" to maj7,
        "maj56" to maj56,
        "maj34" to maj34,
        "maj2" to maj2,
        "min7" to min7,
        "min56" to min56,
        "min34" to min34,
        "min2" to min2,
        "dim7" to dim7,
        "hdim7" to hdim7,
        "aug7" to aug7,
        "augM7" to augM7,
    )

    companion object {
        //val keyNameList: Array<KeyName> = values()
        val keyNameMap = mapOf(
            "Deses" to Deses,
            "Ases" to Ases,
            "Eses" to Eses,
            "Beses" to Beses,
            "Ces" to Ces,
            "Ges" to Ges,
            "Des" to Des,
            "As" to As,
            "Es" to Es,
            "Bes" to Bes,
            "F" to F,
            "C" to C,
            "G" to G,
            "D" to D,
            "A" to A,
            "E" to E,
            "B" to B,
            "Fis" to Fis,
            "Cis" to Cis,
            "Gis" to Gis,
            "Dis" to Dis,
            "Ais" to Ais,
            "Eis" to Eis,
            "Bis" to Bis,

            "deses" to Deses,
            "ases" to Ases,
            "eses" to Eses,
            "beses" to Beses,
            "ces" to Ces,
            "ges" to Ges,
            "des" to Des,
            "aes" to As,
            "as" to As,
            "es" to Es,
            "ees" to Es,
            "bes" to Bes,
            "f" to F,
            "c" to C,
            "g" to G,
            "d" to D,
            "a" to A,
            "e" to E,
            "b" to B,
            "fis" to Fis,
            "cis" to Cis,
            "gis" to Gis,
            "dis" to Dis,
            "ais" to Ais,
            "eis" to Eis,
            "bis" to Bis,
        )
    }
}

typealias Chords = Keys

val CI = Keys.C.major

fun main() {
    Keys.values().forEach { keys ->
        keys.chordMap.forEach {
            println("${keys.name}.${it.key}=${it.value}")
        }
    }
}

