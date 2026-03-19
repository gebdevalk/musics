package musics.elements

import musics.common.cyclicListOf

object COF {
    private val tonicList = cyclicListOf(*Keys.values())

    private val stepsToFifthsValues = cyclicListOf(0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)

    private fun stepsToFifths(step: Int): Int {
        val index = stepsToFifthsValues[step]
        return index % tonicList.size
    }

    fun modulate(signature: Keys, delta: Int): Keys =
        tonicList[signature.ordinal + delta]

    fun transpose(signature: Keys, delta: Int): Keys =
        modulate(signature, stepsToFifths(delta))
}
