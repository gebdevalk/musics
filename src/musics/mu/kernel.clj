(ns musics.mu.kernel
  "mu's kernel words (namespace kernel): the stack, quotations,
   printing, numbers, bindings, the repo, playback, namespaces, help,
   files and the defaults (doc/mu-repl.md, section 8)."
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]
            [musics.core :as m]
            [musics.engine :as engine]
            [musics.input.reader.leaf-parser :as lp]
            [musics.domain.resolve :as r]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

;; -- stack ------------------------------------------------------------

(word! "kernel" "dup" "( a -- a a )  copy the top"
       (fn [s] (need s 1 "dup") (conj s (peek s))))

(word! "kernel" "pop" "( a -- )  drop the top"
       (fn [s] (need s 1 "pop") (pop s)))

(word! "kernel" "swap" "( a b -- b a )  exchange the top two"
       (fn [s]
         (need s 2 "swap")
         (let [n (count s)] (assoc s (- n 1) (s (- n 2)) (- n 2) (s (- n 1))))))

(word! "kernel" "over" "( a b -- a b a )  copy the second over the top"
       (fn [s] (need s 2 "over") (conj s (s (- (count s) 2)))))

(word! "kernel" "rot" "( a b c -- b c a )  rotate the top three: the third comes on top"
       (fn [s]
         (need s 3 "rot")
         (let [n (count s)] (conj (subvec s 0 (- n 3)) (s (- n 2)) (s (- n 1)) (s (- n 3))))))

(word! "kernel" "nip" "( a b -- b )  drop the second"
       (fn [s] (need s 2 "nip") (conj (pop (pop s)) (peek s))))

(word! "kernel" "tuck" "( a b -- b a b )  copy the top under the second"
       (fn [s]
         (need s 2 "tuck")
         (let [b (peek s) a (peek (pop s))] (conj (pop (pop s)) b a b))))

(word! "kernel" "pick" "( xn … x0 n -- xn … x0 xn )  copy the n-th item to the top, the top being 0"
       (fn [s]
         (need s 1 "pick")
         (let [n (peek s) s (pop s)]
           (when-not (and (integer? n) (< -1 n (count s)))
             (e/mu-error "pick: needs an index 0 to" (dec (count s)) "under it, got" n))
           (conj s (s (- (count s) 1 n))))))

(word! "kernel" "depth" "( -- n )  how many items the stack holds (the n of the mu<n> prompt)"
       (fn [s] (conj s (count s))))

;; -- quotations ---------------------------------------------------------

(word! "kernel" "call" "( q -- … )  run the quotation"
       (fn [s] (need s 1 "call") (e/call (pop s) (peek s))))

(word! "kernel" "execute" "( 'word -- … )  run the word a quoted name names (or a quotation, as call)"
       (fn [s]
         (need s 1 "execute")
         (let [x (peek s)]
           (cond
             (symbol? x)       (e/run-word (pop s) (str x))
             (v/quotation? x)  (e/call (pop s) x)
             :else (e/mu-error "execute: needs a quoted word, like 'dup, got" (p/show x))))))

(defn- quote? [x] (v/quotation? x))

(word! "kernel" "if" "( flag q-then q-else -- … )  run q-then if flag is true, else q-else"
       (fn [s]
         (need s 3 "if")
         (let [n (count s) [f t el] (subvec s (- n 3))]
           (when-not (and (quote? t) (quote? el)) (e/mu-error "if: needs two quotations '( … ) on top"))
           (e/call (subvec s 0 (- n 3)) (if f t el)))))

(word! "kernel" "when" "( flag q -- … )  run q if flag is true"
       (fn [s]
         (need s 2 "when")
         (let [q (peek s) f (peek (pop s)) s (pop (pop s))]
           (if f (e/call s q) s))))

(word! "kernel" "each" "( things q -- … )  run q on each element in turn"
       (fn [s]
         (need s 2 "each")
         (let [q (peek s) xs (mt/elements (peek (pop s)))]
           (reduce (fn [st x] (e/call (conj st x) q)) (pop (pop s)) xs))))

(word! "kernel" "reduce" "( things init q -- r )  fold the elements into one value with q, from init: '[1 2 3] 0 '( .+ ) reduce gives 6"
       (fn [s]
         (need s 3 "reduce")
         (let [n (count s) [xs init q] (subvec s (- n 3))]
           (reduce (fn [st x] (e/call (conj st x) q)) (conj (subvec s 0 (- n 3)) init) (mt/elements xs)))))

;; -- printing -------------------------------------------------------------

(word! "kernel" "." "( a -- )  print the top and drop it"
       (fn [s] (need s 1 ".") (println (p/show (peek s))) (pop s)))

(word! "kernel" "cs" "( … -- )  clear the stack" (fn [_] []))

(word! "kernel" ".s" "( -- )  the stack, one item a line, top first, numbered as pick counts"
       (fn [s]
         (doseq [[i x] (map-indexed vector (rseq s))] (println (str i "> " (p/show x))))
         s))

;; -- numbers ------------------------------------------------------------
;; The plain symbols are musical; arithmetic and comparison on numbers
;; take a leading . (doc/mu-repl.md).

(defn- num2 [word f]
  (fn [s]
    (need s 2 word)
    (let [b (peek s) a (peek (pop s))]
      (when-not (and (number? a) (number? b))
        (e/mu-error (str word ":") "needs two numbers, got" (p/show a) "and" (p/show b)))
      (conj (pop (pop s)) (f a b)))))

(defn- num1 [word f]
  (fn [s]
    (need s 1 word)
    (let [a (peek s)]
      (when-not (number? a) (e/mu-error (str word ":") "needs a number, got" (p/show a)))
      (conj (pop s) (f a)))))

(word! "kernel" ".+" "( a b -- a+b )  add two numbers" (num2 ".+" +))
(word! "kernel" ".-" "( a b -- a-b )  subtract b from a" (num2 ".-" -))
(word! "kernel" ".*" "( a b -- a*b )  multiply two numbers" (num2 ".*" *))
(word! "kernel" "./" "( a b -- a/b )  divide a by b, exactly: 1 4 ./ gives 1/4"
       (num2 "./" (fn [a b] (when (zero? b) (e/mu-error "./: division by zero")) (/ a b))))
(word! "kernel" "mod" "( a b -- a mod b )  the remainder of a divided by b" (num2 "mod" mod))
(word! "kernel" "min" "( a b -- min )  the smaller of two numbers" (num2 "min" min))
(word! "kernel" "max" "( a b -- max )  the larger of two numbers" (num2 "max" max))
(word! "kernel" "neg" "( a -- -a )  the number negated" (num1 "neg" -))
(word! "kernel" "abs" "( a -- |a| )  the number without its sign" (num1 "abs" abs))
(word! "kernel" "round" "( a -- n )  rounded to the nearest whole number" (num1 "round" #(long (Math/round (double %)))))
(word! "kernel" "int" "( a -- n )  cut to a whole number, toward zero: 7/2 int gives 3" (num1 "int" long))
(word! "kernel" "float" "( a -- f )  as a decimal number: 1/4 float gives 0.25" (num1 "float" double))
(word! "kernel" "rational" "( a -- r )  as an exact ratio: 0.1 rational gives 1/10" (num1 "rational" rationalize))

(word! "kernel" ".=" "( a b -- flag )  true when a equals b (any values)" (fn [s] (need s 2 ".=") (conj (pop (pop s)) (= (peek (pop s)) (peek s)))))
(word! "kernel" ".<" "( a b -- flag )  true when a is less than b" (num2 ".<" <))
(word! "kernel" ".>" "( a b -- flag )  true when a is greater than b" (num2 ".>" >))
(word! "kernel" ".<=" "( a b -- flag )  true when a is at most b" (num2 ".<=" <=))
(word! "kernel" ".>=" "( a b -- flag )  true when a is at least b" (num2 ".>=" >=))
(word! "kernel" "not" "( flag -- flag )  true becomes false, false true" (fn [s] (need s 1 "not") (conj (pop s) (not (peek s)))))

;; -- the musical symbols ------------------------------------------------------

(defn- musical? [x] (or (mt/leaf? x) (v/material? x) (and (sequential? x) (not (string? x)))))

(defn- on-material
  "A word ( material n -- material' ): g n on every leaf. On two numbers
   it points to the numeric .word."
  [word g check]
  (fn [s]
    (need s 2 word)
    (let [n (peek s) x (peek (pop s))]
      (cond
        (and (number? x) (number? n))
        (e/mu-error (str word ":") "is musical; on numbers write" (str "." word))
        (not (and (musical? x) (number? n)))
        (e/mu-error (str word ":") "needs material and a number, got" (p/show x) "and" (p/show n))
        :else (do (check n) (conj (pop (pop s)) (mt/map-leaves (partial g n) x)))))))

(defn- transpose-leaf [n leaf]
  (if (seq (:pitches leaf)) (update leaf :pitches #(mapv (partial + n) %)) leaf))

(defn- whole! [word n] (when-not (integer? n) (e/mu-error (str word ":") "transposes by a whole number of semitones, got" n)))
(defn- positive! [word n] (when-not (pos? n) (e/mu-error (str word ":") "needs a positive factor, got" n)))

(defn- or-key
  "f, but a key under a whole number moves by that many semitones
   times sign (D.major 2 + gives E.major)."
  [f sign]
  (fn [s]
    (let [n (peek s) k (when (> (count s) 1) (peek (pop s)))]
      (if (and (instance? musics.common.music_elements.Key k) (integer? n))
        (conj (pop (pop s)) (el/transpose-key k (* sign n)))
        (f s)))))

(word! "kernel" "+" "( material|key n -- up n semitones )  every pitch up n semitones (a key moves up too): [c e g] 7 +"
       (or-key (on-material "+" transpose-leaf (partial whole! "+")) 1))
(word! "kernel" "-" "( material|key n -- down n semitones )  every pitch down n semitones: [c e g] 12 -"
       (or-key (on-material "-" (fn [n leaf] (transpose-leaf (- n) leaf)) (partial whole! "-")) -1))
;; The factor is kept as the leaf's :ratio, as c4*1/3 keeps it, so a
;; length that is no note value prints as written: c4 1/3 * as C4/4*1/3.
(defn- scale-duration [leaf f]
  (-> leaf (update :duration * f) (update :ratio (fnil * 1) f)))

(word! "kernel" "*" "( material f -- durations times f )  every duration times f: [c d] 2 * gives halves"
       (on-material "*" (fn [f leaf] (scale-duration leaf f)) (partial positive! "*")))
(word! "kernel" "/" "( material f -- durations divided by f )  every duration divided by f: [c d e] 3 / gives a triplet"
       (on-material "/" (fn [f leaf] (scale-duration leaf (/ 1 f))) (partial positive! "/")))

(defn- hairpin [word dir]
  (fn [s]
    (need s 2 word)
    (let [target (peek s) x (peek (pop s))]
      (when-not (and (number? target) (<= 0 target 100))
        (e/mu-error (str word ":") "needs a target volume 0-100 on top, got" (p/show target)))
      (when-not (musical? x) (e/mu-error (str word ":") "needs material under the target, got" (p/show x)))
      (conj (pop (pop s)) (mt/hairpin (mt/->material x) dir target)))))

(word! "kernel" "cr" "( material target -- crescendo to target )  a crescendo over the material, up to a volume 0-100" (hairpin "cr" "<"))
(word! "kernel" "decr" "( material target -- decrescendo to target )  a decrescendo over the material, down to a volume 0-100" (hairpin "decr" ">"))

(doseq [w ["<" "<=" ">="]]
  (word! "kernel" w (str "( -- )  not a word: compare numbers with ." w)
         (fn [_] (e/mu-error (str w ":") "isn't a word; to compare numbers write" (str "." w)))))

;; -- bindings -------------------------------------------------------------

(word! "kernel" "=" "( 'name value -- )  bind; \\name is the value"
       (fn [s]
         (need s 2 "=")
         (let [x (peek s) n (peek (pop s))]
           (when-not (symbol? n)
             (e/mu-error "=: needs a name under the value, as 'name value =, got" (p/show n)))
           (swap! e/state assoc-in [:bindings (str n)] x)
           (pop (pop s)))))

;; -- the repo -------------------------------------------------------------

(word! "kernel" "R!" "( value [:id] -- :id )  store, silently; leaves the id it stored under"
       (fn [s]
         (need s 1 "R!")
         (let [[name s] (if (and (keyword? (peek s)) (> (count s) 1)) [(peek s) (pop s)] [nil s])
               id (mt/store! (peek s) name)]
           (conj (pop s) id))))

(word! "kernel" "R@" "( :id -- value )  fetch what the repo holds under :id"
       (fn [s]
         (need s 1 "R@")
         (let [id (peek s)]
           (when-not (keyword? id) (e/mu-error "R@: needs a name, like :motif, got" (p/show id)))
           (conj (pop s) (mt/fetch id)))))

;; -- inspecting the repo -------------------------------------------------

(defn- stored-id
  "The id on top of the stack -- a name, or material fetched with R@ --
   if something is stored under it."
  [s word]
  (need s 1 word)
  (let [x (peek s) id (cond (keyword? x) x (v/material? x) (:id x))]
    (when-not (and id (repo/current id))
      (e/mu-error (str word ":") "needs the name of something stored, like :motif, got" (p/show x)))
    id))

;; Each prints what its musics.core namesake gives at the REPL.

(word! "kernel" "ids" "( -- )  every stored id, as (musics.core/ids)"
       (fn [s] (prn (m/ids)) s))

(word! "kernel" "usages" "( :id -- )  the ids whose content uses it directly, as (musics.core/usages id)"
       (fn [s] (prn (m/usages (stored-id s "usages"))) (pop s)))

(word! "kernel" "ctx" "( :id -- )  a part's context chain, as (musics.core/ctx id)"
       (fn [s] (m/ctx (stored-id s "ctx")) (pop s)))

;; -- playback -------------------------------------------------------------

(defn- context? [x] (and (v/material? x) (= :CONTEXT (:type (v/node x)))))

(word! "kernel" "play" "( material [^{ }] -- )  play it now, stopping what plays (also >); under a context on top, if given"
       (fn [s]
         (need s 1 "play")
         (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
           (need s 1 "play")
           (mt/play! (peek s) ctx)
           (pop s))))

(word! "kernel" "play+" "( material [^{ }] -- )  play it now, joining what plays (also >>>)"
       (fn [s]
         (need s 1 "play+")
         (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
           (need s 1 "play+")
           (mt/play! (peek s) ctx true)
           (pop s))))

(word! "kernel" ">" "( material [^{ }] -- )  play" (:fn (get-in @e/state [:ns "kernel" "play"])))
(word! "kernel" ">>>" "( material [^{ }] -- )  play+, joining what plays" (:fn (get-in @e/state [:ns "kernel" "play+"])))

(defn- staged-word
  "( material [^{ }] [|…||||] -- ): stage! from the next boundary."
  [word join?]
  (fn [s]
    (let [[n s] (if (v/mark? (peek s)) [(:count (peek s)) (pop s)] [nil s])]
      (need s 1 word)
      (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
        (need s 1 word)
        (mt/stage! (peek s) ctx n join?)
        (pop s)))))

(word! "kernel" ">>" "( material [^{ }] [|…||||] -- )  in place of what plays, from the next boundary: a bar line of the mark's strength, else the end of what plays or of its repeat's pass"
       (staged-word ">>" false))
(word! "kernel" ">>+" "( material [^{ }] [|…||||] -- )  joining what plays, from the next boundary, as >> finds it"
       (staged-word ">>+" true))

(word! "kernel" "stop" "( -- )  stop everything that plays (also [.])" (fn [s] (mt/stop-all!) s))
(word! "kernel" "[.]" "( -- )  stop" (fn [s] (mt/stop-all!) s))
(word! "kernel" "pause" "( -- )  hold what plays; resume goes on from there ([,] does both)" (fn [s] (m/pause!) s))
(word! "kernel" "resume" "( -- )  go on after pause" (fn [s] (m/resume!) s))
(word! "kernel" "[,]" "( -- )  pause what plays, or resume it if paused"
       (fn [s]
         (let [eng engine/*engine*]
           (cond
             (and eng (engine/paused? eng)) (m/resume!)
             (engine/busy? eng)             (m/pause!)
             :else                          (println "nothing plays")))
         s))

;; -- synthesizers: Fluidsynth through VirMIDI, or Gervill, Java's own --------

(defn echo-line
  "What echo prints for a note or drum as it sounds: a drum's key and
   name, a note's instrument, its name and the pitches."
  [{:keys [channel pitches program]}]
  (if (= 9 channel)
    (str/join "  " (for [k pitches] (str "x\\" k " " (get-in data/drums [k :name] "(no GM drum)"))))
    (str "\\i:" program " " (some-> (data/program->name program) name) "  "
         (str/join " " (for [p pitches]
                         (let [{:keys [letter accidental octave]} (lp/midi->spelling p)]
                           (str (str/upper-case letter) accidental octave)))))))

(defonce ^:private echo-agent (agent nil :error-mode :continue))

(word! "kernel" "echo" "( -- )  print each note as it sounds (its instrument or drum), or stop printing"
       (fn [s]
         (if @engine/echo
           (do (reset! engine/echo nil) (println "echo off"))
           (do (reset! engine/echo (fn [ev] (send-off echo-agent (fn [_] (@e/print-above (echo-line ev)) nil))))
               (println "echo on")))
         s))

(word! "kernel" "fluidsynth" "( -- )  play through Fluidsynth (qsynth, via VirMIDI)"
       (fn [s] (mt/cancel-staged!) (m/connect :fluidsynth) s))
(word! "kernel" "gervill" "( -- )  play through Gervill, Java's own synthesizer, with FluidR3_GM"
       (fn [s] (mt/cancel-staged!) (m/connect :gervill) s))
(word! "kernel" "soundfonts" "( -- )  the .sf2 files soundfont takes by number"
       (fn [s]
         (doseq [[i f] (map-indexed vector (m/soundfonts))] (println (str i "> " f)))
         s))
(word! "kernel" "soundfont" "( \"file.sf2\"|n -- )  Gervill with that soundfont, or the n-th of soundfonts; what plays goes on"
       (fn [s]
         (need s 1 "soundfont")
         (let [x (peek s)]
           (when-not (or (string? x) (integer? x))
             (e/mu-error "soundfont: needs a file in quotes or a number from soundfonts, got" (p/show x)))
           (println (m/soundfont x))
           (pop s))))

(defn- context-text
  "vals ({key value}, keys as a context holds them) as a ^{ } literal,
   those keep? accepts."
  [vals keep?]
  (let [fmt (fn [k x]
              (cond
                (instance? musics.common.music_elements.Key x) (str "!key:" (el/key->str x))
                (instance? musics.common.music_elements.Meter x) (str "!Meter:" (el/meter->str x))
                (number? x) (str "!" k ":" (if (ratio? x) x (pr-str x)))
                (keyword? x) (str "!" k ":" (name x))
                :else nil))]
    (str "^{ " (str/join " " (keep (fn [[k x]] (when (keep? k) (fmt k x))) (sort-by key vals))) " }")))

(defn defaults-text
  "The current defaults (:ROOT's played keys) as a ^{ } literal."
  []
  (context-text (some-> (repo/current :ROOT) :context :envelopes-atom deref)
                (set (map name r/played-keys))))

(word! "kernel" "defaults" "( -- ^{ } )  the context every part starts from (:ROOT's), as a ^{ }"
       (fn [s]
         (conj s (mt/read-text (defaults-text)))))

;; -- namespaces, help, files --------------------------------------------------

(defn- string-arg [s word]
  (need s 1 word)
  (let [x (peek s)]
    (when-not (string? x) (e/mu-error (str word ":") "needs a name in quotes, like \"tool\", got" (p/show x)))
    x))

(word! "kernel" "use" "( \"ns\" -- )  make namespace ns's words usable without ns/: \"pitch\" use"
       (fn [s] (let [n (string-arg s "use")] (swap! e/state update :use #(vec (distinct (conj % n)))) (pop s))))

(word! "kernel" "in" "( \"ns\" -- )  put new definitions into namespace ns"
       (fn [s] (let [n (string-arg s "in")] (swap! e/state assoc :in n) (pop s))))

(word! "kernel" "clear" "( -- )  clear the screen; the stack stays"
       (fn [s] (print "\u001b[H\u001b[2J") (flush) s))

(word! "kernel" "words" "( -- )  list the words in use, then the other namespaces"
       (fn [s]
         (doseq [ns (:use @e/state)
                 :let [ws (sort (keys (get-in @e/state [:ns ns])))]
                 :when (seq ws)]
           (println (str ns ":") (str/join " " ws)))
         (let [others (for [[ns m] (sort-by key (:ns @e/state)) :when (not (some #{ns} (:use @e/state)))]
                        (str ns "/ (" (count m) ")"))]
           (when (seq others)
             (println "also, qualified (pitch/lorenz) or after \"pitch\" use:" (str/join " " others))))
         s))

(word! "kernel" "see" "( \"word\" -- )  show a word: its stack effect and what it does"
       (fn [s]
         (let [n (string-arg s "see") entry (e/lookup @e/state n)]
           (println (cond (nil? entry) (str "unknown word: " n)
                          (:source entry) (:source entry)
                          :else (let [[_ eff what] (re-matches #"(?s)(\(.*?\))\s\s(.*)" (str (:effect entry)))]
                                  (if eff
                                    (str n " " eff "\n  " what)
                                    (str n " " (:effect entry))))))
           (pop s))))

(word! "kernel" "load" "( \"file.mus\" -- )  read a .mus file into the repo"
       (fn [s]
         (let [path (string-arg s "load")]
           (when-not (.exists (java.io.File. ^String path)) (e/mu-error "load: no such file:" path))
           (if-let [{:keys [ids]} (m/parse-file path)]
             (println "stored" (str/join " " (map pr-str ids)))
             (e/mu-error "load: the file didn't parse:" path))
           (pop s))))
