package musics.elements

import musics.common.Ratio
import musics.common.toRatio

typealias Duration = Ratio

fun asDuration(dur: Ratio): String {
    val (num, denom) = dur.destructured()
    return if (num <= denom) {
        when (num) {
            2 -> "${denom / 2.0}"
            3 -> "${denom / 2}."
            7 -> "${denom / 4}.."
            else -> "$denom"
        }
    } else when (dur) {
        "3/2".toRatio() -> "1."
        "7/4".toRatio() -> "1.."

        "2/1".toRatio() -> "breve"
        "3/1".toRatio() -> "breve."
        "7/2".toRatio() -> "breve.."

        "4/1".toRatio() -> "longa"
        "6/1".toRatio() -> "longa."
        "7/1".toRatio() -> "longa.."

        "8/1".toRatio() -> "maxima"
        "12/1".toRatio() -> "maxima."
        "14/1".toRatio() -> "maxima.."

        else -> "$denom*$num"
    }
}

fun asLilypond(dur: Ratio): String {
    val a = asDuration(dur)
    val b = a.split("//")
    return b[1]
}

val maxima = "8/1".toRatio()
val maximad = "12/1".toRatio()
val maximadd = "14/1".toRatio()

val longa = "4/1".toRatio()
val longad = "6/1".toRatio()
val longadd = "7/1".toRatio()

val breve = "4/2".toRatio()
val breved = "6/2".toRatio()
val brevedd = "7/2".toRatio()

val whole = "4/4".toRatio()
val wholed = "6/4".toRatio()
val wholedd = "7/4".toRatio()

val half = "4/8".toRatio()
val halfd = "6/8".toRatio()
val halfdd = "7/8".toRatio()

val D2nd = "4/8".toRatio()
val D2ndd = "6/8".toRatio()
val D2nddd = "7/8".toRatio()

val D4th = "4/16".toRatio()
val D4thd = "6/16".toRatio()
val D4thdd = "7/16".toRatio()

val D8th = "4/32".toRatio()
val D8thd = "6/32".toRatio()
val D8thdd = "7/32".toRatio()

val D16th = "4/64".toRatio()
val D16thd = "6/64".toRatio()
val D16thdd = "7/64".toRatio()

val D32nd = "4/128".toRatio()
val D32ndd = "6/128".toRatio()
val D32nddd = "7/128".toRatio()

val D64th = "4/256".toRatio()
val D64thd = "6/256".toRatio()
val D64thdd = "7/256".toRatio()

val D128th = "4/512".toRatio()
val D128thd = "6/512".toRatio()
val D128thdd = "7/512".toRatio()

val D256th = "4/1024".toRatio()
val D256thd = "6/1024".toRatio()
val D256thdd = "7/1024".toRatio()
