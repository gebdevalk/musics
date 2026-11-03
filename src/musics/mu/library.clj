(ns musics.mu.library
  "Registers Clojure namespaces of music functions as mu's words: each
   public function the word of its name (a trailing ! left off) and of
   its :mu :names, in its :mu :ns (music by default), an entry {:var v}
   that musics.mu.eval runs from the stack. Registered after mu's own
   words, so a function takes the place of the word of its name."
  (:require [clojure.string :as str]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.tools]
            [musics.arith]
            [musics.compare]
            [musics.kernel]
            [musics.music]
            [musics.tool]
            [musics.play]
            [musics.rate]
            [musics.soundfont]))

(defn word-name
  "The mu word a function is: its name, a trailing ! left off."
  [v]
  (str/replace (str (:name (meta v))) #"!$" ""))

;; Words that give their first argument back changed or rebuilt: their result is
;; named for it with a prime in the stack effect (x' for x, a' for a).
(def ^:private changes-x
  #{"cut" "delete" "insert" "put" "splice" "within" "remove" "filter" "mute" "rotate"
    "shuffle" "dedupe" "distinct" "flatten" "barred" "unfold" "transpose"
    "retrograde" "inversion" "retrograde-inversion" "mirror" "delay"
    "delays" "with" "cresc" "dim" "+" "-" "*" "/" "take" "take-last" "drop-last" "take-while" "drop-while" "tail"
    "sort" "sputter" "rotating" "shuffling" "interleave" "partition" "times" "cycle"})

(defn effect
  "v's stack effect and description, as a word written in Clojure shows
   them: ( x [n] -- word )  its docstring. An optional argument (one with
   an :optional test) in brackets; a function done for its effect leaves
   nothing."
  [v]
  (let [{:keys [arglists doc]} (meta v)
        optional (set (map name (keys (:optional (:mu (meta v))))))
        args     (for [a (last (sort-by count arglists))] (if (optional (str a)) (str "[" a "]") (str a)))
        out      (cond (str/ends-with? (str (:name (meta v))) "!") ""
                       (:out (:mu (meta v))) (:out (:mu (meta v)))
                       (changes-x (word-name v)) (str (first args) "'")
                       :else (word-name v))]
    (str "( " (str/join " " (concat args ["--"] (when (seq out) [out]))) " )  " (str/replace (str doc) #"\s+" " "))))

(defn register!
  "Make every public function of clojure namespace ns-sym mu's words, in
   mu namespace mu-ns unless its :mu :ns says otherwise, each name's help
   naming the others it goes by: (also +)."
  [ns-sym mu-ns]
  (doseq [[_ v] (sort-by key (ns-publics ns-sym))
          :when (fn? @v)
          :let [{:keys [names] own-ns :ns} (:mu (meta v))
                all (cons (word-name v) names)]
          w all
          :let [others (remove #{w} all)]]
    (e/define! (or own-ns mu-ns) w
               {:var v :effect (str (effect v) (when (seq others) (str " (also " (str/join " " others) ")")))})))

(register! 'musics.kernel "kernel")
(register! 'musics.compare "kernel")
(register! 'musics.tool "tool")
(register! 'musics.play "play")
(register! 'musics.rate "rate")
(register! 'musics.soundfont "soundfont")
(register! 'musics.music "music")
(register! 'musics.arith "music")
