(ns musics.mu.kernel
  "mu's kernel words that read the stack itself (namespace kernel): the
   stack, quotations, printing the stack, binding; also the play words
   that read the stack's shape (play, ->, play-next, ->>, …)
   (doc/md/mu-repl.md, section 8). The kernel's other words are
   musics.kernel, the rest of play musics.play, the music words
   musics.music."
  (:require [musics.arith :as arith]
            [musics.compare :as cmp]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.live :as live]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

;; -- stack ------------------------------------------------------------

(defn- named
  "The name after variable, value or to, which the evaluator left as a symbol."
  [s word]
  (need s 2 word)
  (let [n (peek s)]
    (when-not (symbol? n) (e/mu-error (str word ":") "write the name after it, as in 5" word "five"))
    (str n)))

(word! "kernel" "variable" "( x 'name -- )  5 variable five: five is a word that pushes a place holding 5, so five @ reads it and 3 five ! stores, as Forth's variable does (but starting with a value); a second name for the place is a plain definition, : six five ;"
       (fn [s]
         (let [n (named s "variable") x (peek (pop s))]
           (e/check-name! n)
           (let [a (atom x) r (v/->Ref n #(deref a) #(reset! a %))]
             (e/define! (:in @e/state) n {:fn (fn [st] (conj st r)) :cell a :kind "variable" :effect (str "( -- " n " )  a place holding a value")})
             (pop (pop s))))))

(word! "kernel" "value" "( x 'name -- )  5 value five: five is a word that pushes 5, and 7 to five changes it, as Forth's value does"
       (fn [s]
         (let [n (named s "value") x (peek (pop s))]
           (e/check-name! n)
           (let [a (atom x)]
             (e/define! (:in @e/state) n {:fn (fn [st] (conj st @a)) :cell a :kind "value" :effect (str "( -- " n " )  a value, changed with to")})
             (pop (pop s))))))

(word! "kernel" "to" "( x 'name -- )  7 to five: change the value five names (made with value)"
       (fn [s]
         (let [n (named s "to") x (peek (pop s))
               entry (e/lookup @e/state n)
               a (when (= "value" (:kind entry)) (:cell entry))]
           (when-not a (e/mu-error "to:" n "is not a value -- 5 value" n "makes one"))
           (reset! a x)
           (pop (pop s)))))

(word! "kernel" "dup" "( x -- x x )  copy the top"
       (fn [s] (need s 1 "dup") (conj s (peek s))))

(word! "kernel" "drop" "( x -- )  discard the top"
       (fn [s] (need s 1 "drop") (pop s)))

(word! "kernel" "2dup" "( x1 x2 -- x1 x2 x1 x2 )  copy the top two"
       (fn [s] (need s 2 "2dup") (conj s (peek (pop s)) (peek s))))

(word! "kernel" "2drop" "( x1 x2 -- )  discard the top two"
       (fn [s] (need s 2 "2drop") (pop (pop s))))

(word! "kernel" "-rot" "( x1 x2 x3 -- x3 x1 x2 )  rotate the top three the other way: the top goes third"
       (fn [s]
         (need s 3 "-rot")
         (let [n (count s)]
           (conj (subvec s 0 (- n 3)) (peek s) (nth s (- n 3)) (nth s (- n 2))))))

(doseq [[w op] [["1+" arith/+] ["1-" arith/-]]]
  (word! "kernel" w (str "( n -- n' )  one " (if (= w "1+") "more" "less") ": a number, a list of numbers, or music (a semitone)")
         (fn [s]
           (need s 1 w)
           (conj (pop s) (try (op (peek s) 1)
                              (catch clojure.lang.ExceptionInfo ex (e/mu-error (str w ":") (ex-message ex))))))))

(word! "kernel" "true" "( -- flag )  the flag true" (fn [s] (conj s true)))
(word! "kernel" "false" "( -- flag )  the flag false" (fn [s] (conj s false)))

(word! "kernel" "swap" "( x1 x2 -- x2 x1 )  exchange the top two"
       (fn [s]
         (need s 2 "swap")
         (let [n (count s)] (assoc s (- n 1) (s (- n 2)) (- n 2) (s (- n 1))))))

(word! "kernel" "over" "( x1 x2 -- x1 x2 x1 )  copy the second over the top"
       (fn [s] (need s 2 "over") (conj s (s (- (count s) 2)))))

(word! "kernel" "rot" "( x1 x2 x3 -- x2 x3 x1 )  rotate the top three: the third comes on top"
       (fn [s]
         (need s 3 "rot")
         (let [n (count s)] (conj (subvec s 0 (- n 3)) (s (- n 2)) (s (- n 1)) (s (- n 3))))))

(word! "kernel" "nip" "( x1 x2 -- x2 )  drop the second"
       (fn [s] (need s 2 "nip") (conj (pop (pop s)) (peek s))))

(word! "kernel" "tuck" "( x1 x2 -- x2 x1 x2 )  copy the top under the second"
       (fn [s]
         (need s 2 "tuck")
         (let [b (peek s) a (peek (pop s))] (conj (pop (pop s)) b a b))))

(word! "kernel" "pick" "( xn ... x0 n -- xn ... x0 xn )  copy the n-th item to the top, the top being 0"
       (fn [s]
         (need s 1 "pick")
         (let [n (peek s) s (pop s)]
           (when-not (and (integer? n) (< -1 n (count s)))
             (e/mu-error "pick: needs an index 0 to" (dec (count s)) "under it, got" n))
           (conj s (s (- (count s) 1 n))))))

(word! "kernel" "roll" "( xn xn-1 ... x0 n -- xn-1 ... x0 xn )  move the n-th item to the top, the top being 0: 0 roll does nothing, 1 roll is swap, 2 roll is rot"
       (fn [s]
         (need s 1 "roll")
         (let [n (peek s) s (pop s)]
           (when-not (and (integer? n) (< -1 n (count s)))
             (e/mu-error "roll: needs an index 0 to" (dec (count s)) "under it, got" n))
           (let [i (- (count s) 1 n)]
             (conj (into (subvec s 0 i) (subvec s (inc i))) (s i))))))

(word! "kernel" "depth" "( -- n )  how many items the stack holds (the n of the mu<n> prompt)"
       (fn [s] (conj s (count s))))

(word! "kernel" "spread" "( x -- elem... )  x's elements on the stack, one after another: a seq's notes (bar lines and jump marks left out), a parallel's parts, data's values -- [c d e] 2 [/] spread (also **)"
       (fn [s]
         (need s 1 "spread")
         (let [xs (try (mt/elements (peek s))
                       (catch clojure.lang.ExceptionInfo ex (e/mu-error "spread:" (ex-message ex))))]
           (into (pop s) (mt/finite! "spread" xs)))))

(word! "kernel" "**" "( x -- elem... )  spread: x's elements on the stack (also spread)" (e/same-as "kernel" "spread"))

;; -- quotations ---------------------------------------------------------

(word! "kernel" "call" "( q -- ... )  run the quotation"
       (fn [s] (need s 1 "call") (e/call (pop s) (peek s))))

;; -- loop (Forth's do … loop) -------------------------------------------------------------

(def ^:private ^:dynamic *loops*
  "The indices of the do loops running now, outermost first."
  [])

(defn- counted [s name step]
  (let [q (peek s) start (peek (pop s)) limit (peek (pop (pop s)))]
    (when-not (v/quotation? q) (e/mu-error (str name ":") "the body is a quotation '( … ), got" (p/show q)))
    (when-not (and (integer? limit) (integer? start))
      (e/mu-error (str name ":") "needs whole numbers, limit and start, got" (p/show limit) "and" (p/show start)))
    (let [more? (if (pos? step) < >)]
      (loop [st (pop (pop (pop s))) n start]
        (if (more? n limit)
          (recur (binding [*loops* (conj *loops* n)] (e/call st q)) (+ n step))
          st)))))

(word! "kernel" "loop" "( limit start 'q -- ... )  Forth's do ... loop with a quotation for its body: run q once for each whole number from start up to, not including, limit, i giving the index and j the index of the loop around; start not below limit runs it no times: 3 0 '( i ) loop leaves 0 1 2"
       (fn [s] (need s 3 "loop") (counted s "loop" 1)))

(word! "kernel" "+loop" "( limit start step 'q -- ... )  loop by a step, a whole number but not 0: a negative step counts down, from start to, not including, limit: 0 5 -1 '( i ) +loop leaves 5 4 3 2 1; 10 0 3 '( i ) +loop leaves 0 3 6 9"
       (fn [s]
         (need s 4 "+loop")
         (let [step (peek (pop s))]
           (when-not (and (integer? step) (not (zero? step)))
             (e/mu-error "+loop:" "the step is a whole number but not 0, got" (p/show step)))
           (counted (conj (pop (pop s)) (peek s)) "+loop" step))))

(word! "kernel" "i" "( -- n )  the index of the do loop running now"
       (fn [s]
         (when (empty? *loops*) (e/mu-error "i:" "is the index of a do loop, and none is running: 3 0 '( i ) loop"))
         (conj s (peek *loops*))))

(word! "kernel" "j" "( -- n )  the index of the do loop around the one running now"
       (fn [s]
         (when (< (count *loops*) 2) (e/mu-error "j:" "is the index of the loop around the running one, and there are not two: 2 0 '( 3 0 '( i j ) loop ) loop"))
         (conj s (peek (pop *loops*)))))

(word! "kernel" "k" "( -- n )  the index of the do loop two around the one running now"
       (fn [s]
         (when (< (count *loops*) 3) (e/mu-error "k:" "is the index of the loop two around the running one, and there are not three: 2 0 '( 2 0 '( 2 0 '( i j k ) loop ) loop ) loop"))
         (conj s (peek (pop (pop *loops*))))))

(word! "kernel" "execute" "( 'word -- ... )  run the word a quoted name names (or a quotation, as call)"
       (fn [s]
         (need s 1 "execute")
         (let [x (peek s)]
           (cond
             (symbol? x)       (e/run-word (pop s) (str x))
             (v/quotation? x)  (e/call (pop s) x)
             :else (e/mu-error "execute: needs a quoted word, like 'dup, got" (p/show x))))))

(defn- quote? [x] (v/quotation? x))

(defn- flag-of
  "x, a flag, or an error naming the word."
  [word x]
  (try (cmp/flag! x)
       (catch clojure.lang.ExceptionInfo ex (e/mu-error (str word ":") (ex-message ex)))))

(word! "kernel" "if" "( flag q1 q2 -- ... )  run q1 if flag is true, else q2"
       (fn [s]
         (need s 3 "if")
         (let [n (count s) [f t el] (subvec s (- n 3))]
           (when-not (and (quote? t) (quote? el)) (e/mu-error "if: needs two quotations '( … ) on top"))
           (e/call (subvec s 0 (- n 3)) (if (flag-of "if" f) t el)))))

(word! "kernel" "when" "( flag q -- ... )  run q if flag is true"
       (fn [s]
         (need s 2 "when")
         (let [q (peek s) f (peek (pop s)) s (pop (pop s))]
           (if (flag-of "when" f) (e/call s q) s))))

(defn- finite
  "xs, refused if it is an endless stream: word would never end."
  [word xs]
  (when (:mu/endless (meta xs)) (e/mu-error (str word ":") "an endless stream never ends -- take some first"))
  xs)

(word! "kernel" "each" "( l q -- ... )  run q on each element in turn"
       (fn [s]
         (need s 2 "each")
         (let [q (peek s) xs (finite "each" (mt/elements (peek (pop s))))]
           (reduce (fn [st x] (e/call (conj st x) q)) (pop (pop s)) xs))))

(word! "kernel" "reduce" "( l x q -- r )  fold the elements into one value with q, from init: '[1 2 3] 0 '( + ) reduce gives 6"
       (fn [s]
         (need s 3 "reduce")
         (let [n (count s) [xs init q] (subvec s (- n 3))]
           (reduce (fn [st x] (e/call (conj st x) q)) (conj (subvec s 0 (- n 3)) init) (finite "reduce" (mt/elements xs))))))

;; -- printing -------------------------------------------------------------

(word! "kernel" "." "( x -- )  print the top and drop it"
       (fn [s] (need s 1 ".") (println (p/show (peek s))) (pop s)))

(word! "kernel" "clear" "( ... -- )  clear the stack" (fn [_] []))

(word! "kernel" ".s" "( -- )  the stack, one item a seq, top first, numbered as pick counts, ---- under the last"
       (fn [s]
         (doseq [[i x] (map-indexed vector (rseq s))] (println (str i "> " (p/show x))))
         (when (seq s) (println "----"))
         s))

(def edit-text
  "The text the next prompt starts with, or nil: what , left there."
  (atom nil))

(doseq [w ["," "edit"]]
  (word! "kernel" w "( x -- )  take the top off the stack into the next prompt as text, to change and run again"
         (fn [s] (need s 1 w) (reset! edit-text (p/show (peek s))) (pop s))))

;; -- bindings -------------------------------------------------------------

(word! "kernel" ":=" "( 'name x -- )  bind; \\name is the value"
       (fn [s]
         (need s 2 ":=")
         (let [x (peek s) n (peek (pop s))]
           (when-not (symbol? n)
             (e/mu-error ":=: needs a name under the value, as 'name value :=, got" (p/show n)))
           (swap! e/state assoc-in [:bindings (str n)] x)
           (pop (pop s)))))


;; -- places ---------------------------------------------------------------

(word! "kernel" "!" "( x n y -- x' ) or ( y ref -- )  put y at n, or store y in a reference (7 bass/onsets !)"
       (fn [s]
         (need s 2 "!")
         (if (v/ref? (peek s))
           (do (v/ref-set! (peek s) (peek (pop s))) (pop (pop s)))
           (do (need s 3 "!")
               (let [[x n y] (take-last 3 s)]
                 (conj (pop (pop (pop s))) ((requiring-resolve 'musics.tool/put) x n y)))))))


;; -- playback -------------------------------------------------------------

(defn- context? [x] (and (v/material? x) (= :CONTEXT (:type (v/node x)))))

(defn- target-of
  "[voice-id stack]: a voice's id (:bass) or index (1 the lowest) on top
   is the voice to play as; else nil. With nothing under it, the voice
   plays its own music again, from the start (1 ->, :bass ->>)."
  [s word]
  (if (or (keyword? (peek s)) (integer? (peek s)))
    (let [k (peek s)]
      (when (and (keyword? k) (= 1 (count s)) (not (get (mt/live-voices) k)))
        (e/mu-error (str word ":") (pr-str k) "on top is the voice to play as, and plays no music;"
                    "to play what is stored as" (pr-str k) "fetch it first:" (pr-str k) "R@ ->"))
      (let [id (mt/voice-id word k) s (pop s)]
        (if-let [{:keys [x ctx]} (and (empty? s) (get (mt/live-voices) id))]
          [id (cond-> (or (live/replay id) [x]) ctx (conj ctx))]
          [id s])))
    [nil s]))

(defn- music-and-recipe
  "[x recipe stack]: under the context, a quotation and the music under it --
   none when a mark | is under the quotation (taken off) or nothing is -- else
   just the music."
  [s word]
  (if (and (seq s) (live/recipe? (peek s)))
    (let [r (peek s) s (pop s)]
      (cond (empty? s)          [nil r s]
            (v/mark? (peek s))  [nil r (pop s)]
            :else               [(peek s) r (pop s)]))
    (do (need s 1 word) [(peek s) nil (pop s)])))

(defn- word-voice
  "[word values stack] when s ends in the quoted name of a word written with
   $ inputs, the values for them taken from under it; else nil. The voice
   needs a name: 5 8 42 'riff :bass ->"
  [s word target]
  (when (and (seq s) (symbol? (peek s)))
    (let [w (str (peek s))]
      (when-let [ins (e/live-inputs w)]
        (when-not (keyword? target)
          (e/mu-error (str word ":") "name the voice that plays" w "-- e.g. 5 8 42 '" (str w " :bass ->")))
        (when-not (every? second ins)
          (e/mu-error (str word ":") w "has inputs without $ -- a live word takes only $ inputs, which stay changeable"))
        (let [s (pop s) n (count ins)]
          (when (< (count s) n)
            (e/mu-error (str word ":") w "takes" n (if (= 1 n) "input" "inputs") "under its name, the stack has" (count s)))
          [w (subvec s (- (count s) n)) (subvec s 0 (- (count s) n))])))))

(defn- play-now
  "( material [recipe] [^{ }] [voice] -- ): play! now, or live through a recipe."
  [word join?]
  (fn [s]
    (let [[target s] (target-of s word)
          _          (need s 1 word)
          [ctx s]    (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
      (if-let [[w vals s'] (word-voice s word target)]
        (do (live/play-word! w target vals ctx join? nil) s')
        (let [[x r s] (music-and-recipe s word)]
          (if r
            (live/play! x r ctx join? target nil)
            (mt/play! x ctx join? target))
          s)))))

(word! "play" "play" "( material [recipe] [^{ }] [voice] -- )  play it now, stopping what plays (also ->); as a voice -- a voice's id on top, or the music's own name ([bass: …]) -- replacing only that voice; under a context, if given; a voice alone (1 play, :bass ->) starts its own music again; with a recipe '( … ), live: each note the next of what the recipe gives now, on the music or, with | or nothing under it, from nothing"
       (play-now "play" false))

(word! "play" "play+" "( material [recipe] [^{ }] [voice] -- )  play it now, joining what plays (also ->+)"
       (play-now "play+" true))

(word! "play" "->" "( material [^{ }] -- )  play" (e/same-as "play" "play"))
(word! "play" "->+" "( material [^{ }] -- )  play+, joining what plays" (e/same-as "play" "play+"))

(defn- staged-word
  "( material [recipe] [^{ }] [|...||||] [voice] -- ): stage! from the next boundary."
  [word join?]
  (fn [s]
    (let [[target s] (target-of s word)
          [n s] (if (v/mark? (peek s)) [(:count (peek s)) (pop s)] [nil s])]
      (need s 1 word)
      (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
        (if-let [[w vals s'] (word-voice s word target)]
          (do (live/play-word! w target vals ctx join? [n]) s')
          (let [[x r s] (music-and-recipe s word)]
            (if r
              (live/play! x r ctx join? target [n])
              (mt/stage! x ctx n join? target))
            s))))))

(word! "play" "play-next" "( material [^{ }] [|...||||] -- )  in place of what plays, from the next boundary: a bar line of the mark's strength, else the end of what plays or of its repeat's pass (also ->>)"
       (staged-word "play-next" false))
(word! "play" "play-next+" "( material [^{ }] [|...||||] -- )  joining what plays, from the next boundary, as play-next finds it (also ->>+)"
       (staged-word "play-next+" true))
(word! "play" "->>" "( material [^{ }] [|...||||] -- )  play-next, in place of what plays from the next boundary"
       (staged-word "->>" false))
(word! "play" "->>+" "( material [^{ }] [|...||||] -- )  play-next+, joining what plays from the next boundary"
       (staged-word "->>+" true))

;; the old signs: not words, and say what replaced them
(doseq [[w new] [[">+" "->+"] [">>" "->>"] [">>+" "->>+"] ["<<" "<<-"]
                 [".>" "->"] [".>+" "->+"] [".>>" "->>"] [".>>+" "->>+"] [".<" "<-"] [".<<" "<<-"]]]
  (word! "play" w (str "( -- )  not a word: write " new)
         (fn [_] (e/mu-error (str w ":") "isn't a word; write" new))))

