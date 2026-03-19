package musics.elements

import musics.common.Ratio
import musics.parts.Leaf

fun ornamented(leaf: Leaf): List<Leaf> {
    val ornamentType = leaf.text.replace("\\", "")
    val leafs = ornamentFunctionMap.getOrDefault(ornamentType, ::plain)(leaf)
    return leafs
}

fun plain(leaf: Leaf): List<Leaf> = listOf(leaf)

fun prall(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    return listOf(
        Leaf(value8, pitches, key, volume, articulation, ties),
        Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties),
        Leaf(duration * Ratio(3, 4), pitches, key, volume, articulation, ties)
    )
}

fun prallup(leaf: Leaf): List<Leaf> = listOf(leaf)
fun pralldown(leaf: Leaf): List<Leaf> = listOf(leaf)

fun upprall(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    val high = Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value8, pitches, key, volume, articulation, ties)
    val low = Leaf(value8, pitches.map { localScale.lower(it) }, key, volume, articulation, ties)
    return listOf(low, base, high, base, high, base, high, base)
}

fun downprall(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    val high = Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value8, pitches, key, volume, articulation, ties)
    val low = Leaf(value8, pitches.map { localScale.lower(it) }, key, volume, articulation, ties)
    return listOf(high, base, low, base, high, base, high, base)
}

fun prallprall(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    val high = Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value8, pitches, key, volume, articulation, ties)
    return listOf(
        base, high, base, high,
        Leaf(duration * Ratio(1, 2), pitches, key, volume, articulation, ties)
    )
}

fun lineprall(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value16 = duration / 16
    val high = Leaf(value16, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value16, pitches, key, volume, articulation, ties)
    return listOf(
        Leaf(duration / 2, pitches.map { localScale.upper(it) }, key, volume, articulation, ties),
        base, high, base, high,
        Leaf(duration / 4, pitches, key, volume, articulation, ties)
    )
}

fun prallmordent(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    val high = Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value8, pitches, key, volume, articulation, ties)
    val low = Leaf(value8, pitches.map { localScale.lower(it) }, key, volume, articulation, ties)
    return listOf(high, base, high, base, high, base, low, base)
}

fun mordent(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    return listOf(
        Leaf(value8, pitches, key, volume, articulation, ties),
        Leaf(value8, pitches.map { localScale.lower(it) }, key, volume, articulation, ties),
        Leaf(duration * Ratio(3, 4), pitches, key, volume, articulation, ties)
    )
}

fun upmordent(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    val high = Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value8, pitches, key, volume, articulation, ties)
    val low = Leaf(value8, pitches.map { localScale.lower(it) }, key, volume, articulation, ties)
    return listOf(low, base, high, base, high, base, low, base)
}

fun downmordent(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 12
    val high = Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value8, pitches, key, volume, articulation, ties)
    val low = Leaf(value8, pitches.map { localScale.lower(it) }, key, volume, articulation, ties)
    return listOf(
        high, base, low, base,
        high, base, high, base,
        high, base, low, base
    )
}

fun trill(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value8 = duration / 8
    val high = Leaf(value8, pitches.map { localScale.upper(it) }, key, volume, articulation, ties)
    val base = Leaf(value8, pitches, key, volume, articulation, ties)
    return listOf(
        high, base, high, base, high,
        Leaf(duration * Ratio(3, 8), pitches, key, volume, articulation, ties)
    )
}

fun turn(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value4 = duration / 4
    val base = Leaf(value4, pitches, key, volume, articulation, ties)
    return listOf(
        Leaf(value4, pitches.map { localScale.upper(it) }, key, volume, articulation, ties),
        base,
        Leaf(value4, pitches.map { localScale.lower(it) }, key, volume, articulation, ties),
        base
    )
}

fun reverseturn(leaf: Leaf): List<Leaf> = with(leaf) {
    val localScale = key!!.scale
    val value4 = duration / 4
    val base = Leaf(value4, pitches, key, volume, articulation, ties)
    return listOf(
        Leaf(value4, pitches.map { localScale.lower(it) }, key, volume, articulation, ties),
        base,
        Leaf(value4, pitches.map { localScale.upper(it) }, key, volume, articulation, ties),
        base
    )
}

// fermata

fun shortfermata(leaf: Leaf): List<Leaf> = with(leaf) {
    listOf(Leaf(duration * Ratio(3, 2), pitches, key, volume, articulation, ties))
}

fun fermata(leaf: Leaf): List<Leaf> = with(leaf) {
    listOf(Leaf(duration * Ratio(4, 2), pitches, key, volume, articulation, ties))
}

fun longfermata(leaf: Leaf): List<Leaf> = with(leaf) {
    listOf(Leaf(duration * Ratio(6, 2), pitches, key, volume, articulation, ties))
}

fun verylongfermata(leaf: Leaf): List<Leaf> = with(leaf) {
    listOf(Leaf(duration * Ratio(8, 2), pitches, key, volume, articulation, ties))
}

private val ornamentFunctionMap = mapOf<String, (Leaf) -> List<Leaf>>(
    "prall" to ::prall,
    "prallup" to ::prallup,
    "pralldown" to ::pralldown,
    "upprall" to ::upprall,
    "downprall" to ::downprall,
    "prallprall" to ::prallprall,
    "lineprall" to ::lineprall,
    "prallmordent" to ::prallmordent,
    "mordent" to ::mordent,
    "upmordent" to ::upmordent,
    "downmordent" to ::downmordent,
    "trill" to ::trill,
    "turn" to ::turn,
    "reverseturn" to ::reverseturn,
    "shortfermata" to ::shortfermata,
    "fermata" to ::fermata,
    "longfermata" to ::longfermata,
    "verylongfermata" to ::verylongfermata
)
