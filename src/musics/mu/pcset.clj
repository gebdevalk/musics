(ns musics.mu.pcset
  "Sets of pitch classes as mu values: $[047] the classes of a chord, a
   line, $(037) a prime form, $<001110> an interval vector. A value is a
   musics.mu.value/PcSet, its kind and its numbers; what reads a list
   reads its numbers (musics.mu.material/elements), what changes them
   gives a plain list back, so the kind lasts only while the numbers are
   as they were made."
  (:require [clojure.string :as str]
            [musics.algo.melodic.serial :as serial]
            [musics.mu.eval :as e]
            [musics.mu.value :as v]))

(defn- glyph [c]
  (case c \T 10 \E 11 (Character/digit ^char c 10)))

(defn parse-nums
  "The numbers of a set's text: apart (\"12 12 6\", each a decimal or T or
   E) or glued (\"047TE\", one each)."
  [text]
  (cond
    (nil? text)              []
    (str/includes? text " ") (mapv #(case % "T" 10 "E" 11 (parse-long %)) (str/split text #" +"))
    :else                    (mapv glyph text)))

(defn make
  "A PcSet of kind (:classes :prime :vector) from items. Classes are
   sorted, each once, mod 12; a prime form must be one; a vector has six
   counts."
  [kind items]
  (case kind
    :classes (v/->PcSet :classes (vec (sort (distinct (map #(mod % 12) items)))))
    :prime   (let [items (vec items) prime (vec (serial/prime-form items))]
               (when-not (= items prime)
                 (e/mu-error (str (v/pcset-text (v/->PcSet :prime items)) ":") "is no prime form -- the prime form of those classes is"
                             (v/pcset-text (v/->PcSet :prime prime))))
               (v/->PcSet :prime items))
    :vector  (do (when-not (= 6 (count items))
                   (e/mu-error "$<…>: an interval vector has six counts, one for each interval class 1-6, got" (count items)))
                 (v/->PcSet :vector (vec items)))))

(defn read-node
  "The value of a parse node [:PcSet [:PcClasses [:PcNums text]]]."
  [[_ [tag nums]]]
  (make ({:PcClasses :classes :PcPrime :prime :PcVector :vector} tag)
        (parse-nums (second nums))))
