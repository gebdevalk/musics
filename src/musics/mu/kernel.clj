(ns musics.mu.kernel
  "mu's kernel words that read the stack itself (namespace kernel): the
   stack, quotations, printing the stack, binding; also the play words
   that read the stack's shape (play, >, play-next, >>, …)
   (doc/md/mu-repl.md, section 8). The kernel's other words are
   musics.kernel, the rest of play musics.play, the music words
   musics.music."
  (:require [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.live :as live]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

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

(word! "kernel" "spread" "( x -- a b … )  x's elements on the stack, one after another: a line's notes (bar lines and jump marks left out), a parallel's parts, data's values -- [c d e] 2 [/] spread (also **)"
       (fn [s]
         (need s 1 "spread")
         (let [xs (try (mt/elements (peek s))
                       (catch clojure.lang.ExceptionInfo ex (e/mu-error "spread:" (ex-message ex))))]
           (into (pop s) (remove mt/marker? (mt/finite! "spread" xs))))))

(word! "kernel" "**" "( x -- a b … )  spread: x's elements on the stack (also spread)" (e/same-as "kernel" "spread"))

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

(defn- finite
  "xs, refused if it is an endless stream: word would never end."
  [word xs]
  (when (:mu/endless (meta xs)) (e/mu-error (str word ":") "an endless stream never ends -- take some first"))
  xs)

(word! "kernel" "each" "( things q -- … )  run q on each element in turn"
       (fn [s]
         (need s 2 "each")
         (let [q (peek s) xs (finite "each" (mt/elements (peek (pop s))))]
           (reduce (fn [st x] (e/call (conj st x) q)) (pop (pop s)) xs))))

(word! "kernel" "reduce" "( things init q -- r )  fold the elements into one value with q, from init: '[1 2 3] 0 '( add ) reduce gives 6"
       (fn [s]
         (need s 3 "reduce")
         (let [n (count s) [xs init q] (subvec s (- n 3))]
           (reduce (fn [st x] (e/call (conj st x) q)) (conj (subvec s 0 (- n 3)) init) (finite "reduce" (mt/elements xs))))))

;; -- printing -------------------------------------------------------------

(word! "kernel" "." "( a -- )  print the top and drop it"
       (fn [s] (need s 1 ".") (println (p/show (peek s))) (pop s)))

(word! "kernel" "clear" "( … -- )  clear the stack" (fn [_] []))

(word! "kernel" ".s" "( -- )  the stack, one item a line, top first, numbered as pick counts, ---- under the last"
       (fn [s]
         (doseq [[i x] (map-indexed vector (rseq s))] (println (str i "> " (p/show x))))
         (when (seq s) (println "----"))
         s))

(word! "kernel" "=" "( -- )  not a word: bind with :=, compare with eq"
       (fn [_] (e/mu-error "=: isn't a word; bind a name with 'name value :=, compare with eq")))

(doseq [[w num] [["<=" "le"] [">=" "ge"]]]
  (word! "kernel" w (str "( -- )  not a word: compare numbers with " num)
         (fn [_] (e/mu-error (str w ":") "isn't a word; to compare numbers write" num))))

;; -- bindings -------------------------------------------------------------

(word! "kernel" ":=" "( 'name value -- )  bind; \\name is the value"
       (fn [s]
         (need s 2 ":=")
         (let [x (peek s) n (peek (pop s))]
           (when-not (symbol? n)
             (e/mu-error ":=: needs a name under the value, as 'name value :=, got" (p/show n)))
           (swap! e/state assoc-in [:bindings (str n)] x)
           (pop (pop s)))))


;; -- playback -------------------------------------------------------------

(defn- context? [x] (and (v/material? x) (= :CONTEXT (:type (v/node x)))))

(defn- target-of
  "[voice-id stack]: a voice's id (:bass) or index (1 the lowest) on top
   is the voice to play as; else nil. With nothing under it, the voice
   plays its own music again, from the start (1 >, :bass >>)."
  [s word]
  (if (or (keyword? (peek s)) (integer? (peek s)))
    (let [k (peek s)]
      (when (and (keyword? k) (= 1 (count s)) (not (get (mt/live-voices) k)))
        (e/mu-error (str word ":") (pr-str k) "on top is the voice to play as, and plays no music;"
                    "to play what is stored as" (pr-str k) "fetch it first:" (pr-str k) "R@ >"))
      (let [id (mt/voice-id word k) s (pop s)]
        (if-let [{:keys [x ctx]} (and (empty? s) (get (mt/live-voices) id))]
          [id (cond-> (or (live/replay id) [x]) ctx (conj ctx))]
          [id s])))
    [nil s]))

(defn- music-and-recipe
  "[x recipe stack]: under the context, a recipe (a quotation, or a
   composer's tree) and the music under it -- none when a mark | is
   under the recipe (taken off) or nothing is -- else just the music."
  [s word]
  (if (and (seq s) (live/recipe? (peek s)))
    (let [r (peek s) s (pop s)]
      (cond (empty? s)          [nil r s]
            (v/mark? (peek s))  [nil r (pop s)]
            :else               [(peek s) r (pop s)]))
    (do (need s 1 word) [(peek s) nil (pop s)])))

(defn- play-now
  "( material [recipe] [^{ }] [voice] -- ): play! now, or live through a recipe."
  [word join?]
  (fn [s]
    (let [[target s] (target-of s word)
          _          (need s 1 word)
          [ctx s]    (if (context? (peek s)) [(peek s) (pop s)] [nil s])
          [x r s]    (music-and-recipe s word)]
      (if r
        (live/play! x r ctx join? target nil)
        (mt/play! x ctx join? target))
      s)))

(word! "play" "play" "( material [recipe] [^{ }] [voice] -- )  play it now, stopping what plays (also >); as a voice -- a voice's id on top, or the music's own name ([bass: …]) -- replacing only that voice; under a context, if given; a voice alone (1 play, :bass >) starts its own music again; with a recipe '( … ), live: each note the next of what the recipe gives now, on the music or, with | or nothing under it, from nothing"
       (play-now "play" false))

(word! "play" "play+" "( material [recipe] [^{ }] [voice] -- )  play it now, joining what plays (also >+)"
       (play-now "play+" true))

(word! "play" ">" "( material [^{ }] -- )  play" (e/same-as "play" "play"))
(word! "play" ">+" "( material [^{ }] -- )  play+, joining what plays" (e/same-as "play" "play+"))

(defn- staged-word
  "( material [recipe] [^{ }] [|…||||] [voice] -- ): stage! from the next boundary."
  [word join?]
  (fn [s]
    (let [[target s] (target-of s word)
          [n s] (if (v/mark? (peek s)) [(:count (peek s)) (pop s)] [nil s])]
      (need s 1 word)
      (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])
            [x r s] (music-and-recipe s word)]
        (if r
          (live/play! x r ctx join? target [n])
          (mt/stage! x ctx n join? target))
        s))))

(word! "play" "play-next" "( material [^{ }] [|…||||] -- )  in place of what plays, from the next boundary: a bar line of the mark's strength, else the end of what plays or of its repeat's pass (also >>)"
       (staged-word "play-next" false))
(word! "play" "play-next+" "( material [^{ }] [|…||||] -- )  joining what plays, from the next boundary, as play-next finds it (also >>+)"
       (staged-word "play-next+" true))
(word! "play" ">>" "( material [^{ }] [|…||||] -- )  play-next, in place of what plays from the next boundary"
       (staged-word ">>" false))
(word! "play" ">>+" "( material [^{ }] [|…||||] -- )  play-next+, joining what plays from the next boundary"
       (staged-word ">>+" true))

