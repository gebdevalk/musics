package musics.elements

import musics.common.shiftedLeft

//interface OneBased{
//
//    fun degree2number(octave: Int, degree: Int): Int
//    fun degree2number(degree: Int): Int
//
//    val i: Int
//    val ii: Int
//    val iii: Int
//    val iv: Int
//    val v: Int
//    val vi: Int
//    val vii: Int
//    val viii: Int
//    val ix: Int
//    val x: Int
//    val xi: Int
//    val xii: Int
//    val xiii: Int
//    val xiv: Int
//    val xv: Int
//}


abstract class Scale(
    ascIntervals: List<Int>,
    descIntervals: List<Int>
) {
    constructor(intervals: List<Int>) : this(intervals, intervals)

    companion object {
        // AbstractScale interval distribution data
        val diatonicIntervals = listOf(2, 2, 1, 2, 2, 2, 1)
        val ascMelodicMinorIntervals = listOf(2, 1, 2, 2, 2, 2, 1)
        val harmonicMinorIntervals = listOf(2, 1, 2, 2, 1, 3, 1)
//        val pentatonicIntervals = listOf(2, 2, 3, 2, 3)
    }

    val size: Int
    val ascending: List<Int>
    val descending: List<Int>
    private val ascendingExt: List<Int>
    private val descendingExt: List<Int>

    private var currentIndex: Int = 0
    private var previousIndex: Int = 0

    init {
        assert(ascIntervals.size == descIntervals.size)
        ascending = ascIntervals.toScale()
        descending = descIntervals.toScale()
        ascendingExt = listOf(ascending, ascending.map { it + 12 }).flatten()
        descendingExt = listOf(descending, descending.map { it + 12 }).flatten()//.reversed()
        size = ascending.size
    }

//    operator fun plus(delta: Int): Scale = this
//
//    operator fun minus(delta: Int): Scale = this

    fun shiftedLeft(offset: Int) {
        ascending.shiftedLeft(offset)
        descending.shiftedLeft(offset)
    }

    private fun deltaUp(offsetLow: Int, offsetHigh: Int): Int =
        ascendingExt[offsetHigh] - ascendingExt[offsetLow]

    private fun deltaDown(offsetLow: Int, offsetHigh: Int): Int =
        descendingExt[offsetLow] - descendingExt[offsetHigh]

    fun upper(pitch: Int): Int {
        val offset = pitch % 12
        return pitch + deltaUp(offset, offset + 1)
    }

    fun lower(pitch: Int): Int {
        val offset = pitch % 12
        return pitch - deltaDown(offset, offset + 1)
    }

    fun degree2number(octave: Int, degree: Int): Int =
        degree2number((octave + 1) * size + degree)

    fun degree2number(degree: Int): Int {
        val index = degree - 1
        currentIndex = index
        val steps = if (currentIndex > previousIndex) ascending else descending
        previousIndex = currentIndex
        return index / size * 12 + steps[index % size]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Scale) return false
        if (ascending != other.ascending) return false
        return descending == other.descending
    }

    override fun hashCode(): Int =
        31 * ascending.hashCode() + descending.hashCode()

    override fun toString(): String {
        val accu = mutableListOf<String>()
        for (i in 1..size + 1) {
            val number = degree2number(i)
            accu.add(number.toString())
        }
        if (ascending != descending) {
            for (i in size downTo 1) {
                val number = degree2number(i)
                accu.add(number.toString())
            }
        }
        return "${javaClass.simpleName}$accu"
    }

    fun normalize(string: String) =
        string.replace("is", "#")
            .replace("es", "b")
            .replace("s", "b")

//    val i = ascending[0]
//    val ii = ascending[1]
//    val iii = ascending[2 % size]
//    val iv = ascending[3 % size]
//    val v = ascending[4 % size]
//    val vi = ascending[5 % size]
//    val vii = ascending[6 % size]
//    val viii = ascending[7 % size]
//    val ix = ascending[8 % size]
//    val x = ascending[9 % size]
//    val xi = ascending[10 % size]
//    val xii = ascending[11 % size]
//    val xiii = ascending[12 % size]
//    val xiv = ascending[13 % size]
//    val xv = ascending[14 % size]

    val i = degree2number(1)
    val ii = degree2number(2)
    val iii = degree2number(3)
    val iv = degree2number(4)
    val v = degree2number(5)
    val vi = degree2number(6)
    val vii = degree2number(7)
    val viii = degree2number(8)
    val ix = degree2number(9)
    val x = degree2number(10)
    val xi = degree2number(10)
    val xii = degree2number(12)
    val xiii = degree2number(13)
    val xiv = degree2number(14)
    val xv = degree2number(15)
}

abstract class Pentatonic(
    intervals: List<Int>
) : Scale(intervals)

open class Hexatonic(
    intervals: List<Int>
) : Scale(intervals)

open class Diatonic(
    ascIntervals: List<Int>,
    descIntervals: List<Int>
) : Scale(ascIntervals, descIntervals) {
    constructor(intervals: List<Int>) : this(intervals, intervals)
}

// Pentatonic scales
object PentatonicMajor : Pentatonic(listOf(2, 2, 3, 2, 2))
object PentatonicMinor : Pentatonic(listOf(2, 3, 2, 2, 2))
object Balines : Pentatonic(listOf(1, 2, 4, 1))

// Hexatonic scales
// the whole tone scale, C D E F♯ G♯ A♯ C;
object WholeTone : Hexatonic(listOf(2, 2, 2, 2, 2, 2))

// C D E F G A C
object MajorHexatonic : Hexatonic(listOf(2, 2, 1, 2, 2, 3))

// C D E♭ F G B♭ C
object MinorHexatonic : Hexatonic(listOf(2, 1, 2, 2, 3, 2))

// the augmented scale, C D♯ E G A♭ B C;
object Augmented : Hexatonic(listOf(3, 1, 3, 1, 3, 1)) // C E G♯ and E♭ G B

// the Prometheus scale, C D E F♯ A B♭ C;
object Promethean : Hexatonic(listOf(2, 2, 2, 3, 1, 2)) // C F♯ B♭ E A D the "mystic chord"

// the blues scale, C E♭ F G♭ G B♭ C.
object Blues : Hexatonic(listOf(3, 2, 1, 1, 3, 2))

// C D♭ D F♯ G A♭,
// C D♭ E G♭ G(♮) B♭
object Tritone : Hexatonic(listOf(1, 3, 2, 1, 3, 2))
object TwoSemitoneTritone : Hexatonic(listOf(1, 1, 4, 1, 1, 4))
object Byzantine : Hexatonic(listOf(1, 3, 1, 2, 1, 4))

// Diatonic scales (Heptatonic)
object Major : Diatonic(diatonicIntervals)
object Minor : Diatonic(diatonicIntervals.shiftedLeft(5))
object HarmonicMinor : Diatonic(harmonicMinorIntervals)
object MelodicMinor : Diatonic(ascMelodicMinorIntervals, diatonicIntervals.shiftedLeft(5))
object Ionian : Diatonic(diatonicIntervals)
object Dorian : Diatonic(diatonicIntervals.shiftedLeft(1))
object Phrygian : Diatonic(diatonicIntervals.shiftedLeft(2))
object Lydian : Diatonic(diatonicIntervals.shiftedLeft(3))
object Mixolydian : Diatonic(diatonicIntervals.shiftedLeft(4))
object Aeolian : Diatonic(diatonicIntervals.shiftedLeft(5))
object Locrian : Diatonic(diatonicIntervals.shiftedLeft(6))

// Plagal modes
object Hypoionian : Diatonic(diatonicIntervals.shiftedLeft(4))
object Hypodorian : Diatonic(diatonicIntervals.shiftedLeft(5))
object Hypophrygian : Diatonic(diatonicIntervals.shiftedLeft(6))
object Hypolydian : Diatonic(diatonicIntervals.shiftedLeft(7))
object Hypomixolydian : Diatonic(diatonicIntervals.shiftedLeft(8))
object Hypoaeolian : Diatonic(diatonicIntervals.shiftedLeft(9))
object Hypolocrian : Diatonic(diatonicIntervals.shiftedLeft(10))

//object HM0 : Diatonic(harmonicMinorIntervals)
//object HM1 : Diatonic(harmonicMinorIntervals.shiftedLeft(1))
object HM2 : Diatonic(harmonicMinorIntervals.shiftedLeft(2))
//object HM3 : Diatonic(harmonicMinorIntervals.shiftedLeft(3))
//object HM4 : Diatonic(harmonicMinorIntervals.shiftedLeft(4))
//object HM5 : Diatonic(harmonicMinorIntervals.shiftedLeft(5))
//object HM6 : Diatonic(harmonicMinorIntervals.shiftedLeft(6))

object Kurd : Diatonic(listOf(1, 2, 2, 2, 1, 2, 2))
object Gypsy : Diatonic(listOf(1, 3, 1, 2, 1, 3, 1))
object AhavohRabboh : Diatonic(listOf(1, 3, 1, 2, 1, 2, 2))
object Hungarian : Diatonic(listOf(2, 1, 3, 1, 1, 3, 1))
object Charhargah : Diatonic(listOf(1, 3, 1, 2, 1, 3, 1))
object Spanish : Diatonic(listOf(1, 3, 1, 2, 1, 2, 2))

// 12 tone
object Chromatic : Scale(listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)) {
//    override operator fun plus(delta: Int): Chromatic = this
//    override operator fun minus(delta: Int): Chromatic = this
}

fun List<Int>.toScale(): List<Int> {
    var accu = 0
    return this.map {
        val result = accu
        accu += it
        result
    }
}
