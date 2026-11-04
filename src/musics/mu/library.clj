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


;; A stack effect is read as in Forth: a result named like an input is that input
;; untouched, a prime (x') its changed version, any other name a new thing; n a
;; number, x anything, m music, l a list or stream, q a quotation, t a length in
;; whole notes, ... any number. The generated effect (arglists) is used unless the
;; word is here: where its arguments or its result are better named, or it has
;; two shapes. library_test checks each against the function's arities.
(def stack-effects
  {"R!" "( x [id] -- id )", "store" "( x [id] -- id )", "overwrite" "( x [id] -- id )", "R!!" "( x [id] -- id )"
   "R@" "( id -- x )", "fetch" "( id -- x )"
   "abs" "( n -- n' )", "negate" "( n -- n' )", "round" "( n -- n' )", "int" "( n -- n' )"
   "float" "( n -- n' )", "rational" "( n -- n' )", "mod" "( n1 n2 -- n3 )"
   "max" "( n1 n2 -- n3 ) or ( l -- n )", "min" "( n1 n2 -- n3 ) or ( l -- n )"
   "and" "( flag1 flag2 -- flag3 )", "or" "( flag1 flag2 -- flag3 )", "xor" "( flag1 flag2 -- flag3 )"
   "not" "( flag -- flag' )", "flag" "( flag -- )"
   "defaults" "( -- ctx )", "defaults-text" "( -- text )"
   "<" "( x1 x2 [q] -- flag )", ">" "( x1 x2 [q] -- flag )", "<=" "( x1 x2 [q] -- flag )", ">=" "( x1 x2 [q] -- flag )"
   "=" "( x1 x2 [q] -- flag )", "<>" "( x1 x2 [q] -- flag )"
   "+" "( x n -- x' )", "-" "( x n -- x' )", "*" "( x n -- x' )", "/" "( x n -- x' )"
   ">list" "( x -- l )", "repeats" "( x -- x' )", "measures" "( x -- l )"
   "chord?" "( leaf -- flag )", "drum?" "( leaf -- flag )", "note?" "( leaf -- flag )", "rest?" "( leaf -- flag )"
   "as-drums" "( line drums -- line' )", "at" "( x t -- note )", "bar" "( x n -- m )", "bars" "( x start end -- m )"
   "chord-name" "( x -- name )", "classes" "( x -- set )", "prime-form" "( x -- set )"
   "combos" "( leaf numbers k -- l )", "sounds" "( leaf numbers -- l )", "degrees" "( numbers key -- l )"
   "delay" "( x t -- x' )", "delays" "( x t -- x' )"
   "interval-vector" "( x -- v )", "key-of" "( x -- key )", "duration" "( x -- t )", "profile" "( x -- l )"
   "pulses" "( x [pulse] -- l )", "setting" "( x k -- v )", "walking-bass" "( chords [n] -- m )"
   ".." "( n1 n2 -- l )", "range" "( n1 n2 -- l )", "@" "( x n -- elem )", "nth" "( x n -- elem )"
   "concat" "( x1 x2 -- x3 )", "parts" "( x n -- l )", "partition" "( x n -- l )"
   "head" "( x -- elem )"
   "choose-from" "( x -- l )", "choose-n" "( x n -- l )", "length" "( x -- n )"
   "find" "( x q -- elem )", "index-of" "( x q -- n )", "indexes" "( x q -- l )"
   "interleave" "( x1 x2 -- x3 )", "intervals" "( l -- steps )", "iterate" "( x q -- l )"
   "last" "( x -- elem )", "map" "( x q -- x' )", "mean" "( l -- n )"
   "scale" "( key -- m ) or ( root intervals -- m )", "select" "( x weights -- elem )"
   "slice" "( x start end -- x' )", "sum" "( l -- n )"
   "playing" "( voice -- m )", "<-" "( voice -- m )"})

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
    (str (or (stack-effects (word-name v))
             (str "( " (str/join " " (concat args ["--"] (when (seq out) [out]))) " )"))
         "  " (str/replace (str doc) #"\s+" " "))))

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
