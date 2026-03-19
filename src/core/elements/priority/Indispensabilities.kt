package musics.elements.priority

import mu.KotlinLogging


/**
 * Created by gebdevalk on 4-2-2018.
 */
object Indispensabilities {

    private val logger = KotlinLogging.logger {}

    private fun pi(start: Int, stop: Int, q: List<Int>): Int =
        (start until stop).reduce { a, b -> q[a] * q[b] }

/*
(defn calc
  "Calculate indispensability for n based on the coll of divisions"
  ([coll]
   (let [Q (reduce * 1 coll)]
     (reduce
       #(conj % (calc %2 Q coll))
       []
       (range Q))))
  ([n coll]
   (let [Q (reduce * 1 coll)]
     (calc n Q coll)))
  ([n Q coll]
   (let [n (rem n Q)
         q (count coll)
         d (mod (+ (dec n) Q) Q)]
     (loop [i 0 defaultArt 0]
       (if (< i q)
         (let [i' (- q i 1)
               a (pi 0 i' coll)
               b (pi (- q i), q coll)
               c (coll i')
               p (* a (mod (quot d b) c))]
           (recur (inc i) (+ defaultArt p)))
         ;(inc defaultArt)
         defaultArt
         )))))
*/


    fun calc(vararg divs: Int): List<Int> {
        val Q = divs.reduce { a, b -> a * b }
        return (0 until Q).map { calc(it, Q, divs) }
    }

    fun calc(n: Int, vararg divs: Int): Int {
        val Q = divs.reduce { a, b -> a * b }
        return calc(n, Q, divs)
    }

    private fun calc(n: Int, Q: Int, divs: IntArray): Int {
        val size = divs.size
        val d = (n - 1 + Q) % Q
        return (0 until size).map {
            val a = (0 until size - it - 1).reduce { i, j -> divs[i] * divs[j] }
            val b = (0 until size - it).reduce { i, j -> divs[i] * divs[j] }
            val c = divs[size - it - 1]
            a * (d / b % c)
        }.sum()
    }

    fun xnormalize(intArray: List<Int>): DoubleArray {
        val length = intArray.size
        val doubleArray = DoubleArray(length)
        var i = 0
        while (i < length) {
            val value = intArray[i]
            doubleArray[i] = normalize(value, length)
            i += 1
        }
        return doubleArray
    }

    fun normalize(array: IntArray): List<Double> =
        array.map { normalize(it, array.size) }

    private fun normalize(value: Int, divisor: Int): Double =
        value.toDouble() / divisor

//    obsolete priorities(vararg quotients: Int): DoubleArray {
//        return prioritize(normalize(calc(*quotients)))
//    }

    fun prioritize(array: DoubleArray): DoubleArray {
        return adhere(-1.0, array)
    }

    fun adhere(factor: Double, array: IntArray): IntArray {
        val length = array.size
        val S = array.reduce { a, b -> a + b }
        val result = IntArray(length)
        var n = 0
        while (n < length) {
            val pivot = S.toDouble() / length
            result[n] = adhere(factor, pivot, array[n])
            n += 1
        }
        return result
    }

    private fun adhere(factor: Double, pivot: Double, originalValue: Int): Int {
        val newValue = originalValue * factor
        val delta = factor * -pivot + pivot
        return (newValue + delta).toInt()
    }

    private fun adhere(factor: Double, array: DoubleArray): DoubleArray {
        val length = array.size
        val result = DoubleArray(length)
        var n = 0
        while (n < length) {
            result[n] = adhere(factor, array[n])
            n += 1
        }
        return result
    }

    private fun adhere(factor: Double, originalValue: Double): Double {
        val pivot = 0.625
        val newValue = originalValue * factor
        val delta = factor * -pivot + pivot
        return newValue + delta
    }

    fun play(n: Int, density: Double, array: DoubleArray): Boolean {
        val i = n % array.size
        return array[i] <= density
    }

    private fun pi(start: Int, stop: Int, q: IntArray): Int {
        return (start until stop).reduce { a, b -> q[a] * q[b] }
    }

    fun print(array: IntArray) {
        array.forEach { i -> System.out.print(String.format("%4s ", i)) }
        System.out.println()
    }

    fun print(array: DoubleArray) {
        array.forEach { i -> System.out.print(String.format("%.2f ", i)) }
        System.out.println()
    }


//    obsolete indispensabilities(vararg quotients: Int): IntArray {
//        val Q = quotients.reduce { a, b -> a * b }
//        val result = IntArray(Q)
//        var n = 0
//        while (n < Q) {
//            result[n] = calc(n, Q, *quotients)
//            n += 1
//        }
//        return result
//    }

//    obsolete indispx(n: Int, Q: Int, vararg quotients: Int): Int {
//        var result = 0
//        val qlimit = quotients.size
//        val d = (n - 1 + Q) % Q
//        var l = 0
//        while (l < qlimit) {
//            val level = l
//            val a = pi(0, qlimit - level - 1, quotients)
//            val b = pi(qlimit - level, qlimit, quotients)
//            val c = quotients[qlimit - level - 1]
//            val p = a * (d / b % c)
//            result += p
//            l += 1
//        }
//        return result + 1
//    }

//    private obsolete π(start: Int, stop: Int, q: IntArray): Int {
//        var result = 1
//        var i = start
//        while (i < stop) {
//            result *= q[i]
//            i += 1
//        }
//        return result
//    }
}


