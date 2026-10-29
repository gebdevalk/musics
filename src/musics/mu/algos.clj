(ns musics.mu.algos
  "The algos as mu words, by what they deliver (doc/md/mu-repl.md, section
   8): every algo on a half-product or product shelf
   (musics.algo.tree.registry/shelf) is a word in that shelf's namespace
   -- pitch/lorenz, duration/euclid, duration/emi, volume/walk,
   leaf/pocket, parallel/drums. The word is the algo's registry name: a
   specialised algo (musics.algo.tree.half-products) is keyed by its
   half product, :duration/euclid, and its shelf is that namespace. Raw
   material and the bridges stay in the trees' workshop; the tools are
   mu's own.

   A word takes as many inputs as its algo (a source none): a line is
   taken apart into what the algo wants -- durations (a rest as itself),
   pitches (a chord's lowest, nil for a rest) or leaves -- and a stream
   is taken as it is; a repeat or loop around the inputs is run on what
   it repeats and put back around the result. Its settings, first found
   wins: an @^{ k:5 n:16 } on top, its own; a live voice's (set); the
   head of the recipe it runs in, the @^{ } it opens with
   (musics.mu.eval/*head*); for a param that names a music key (key,
   meter), the music's (a live voice's chain, :ROOT's: !key:); the
   defaults. seed:7 among them runs the algo on that random seed, so two
   words with one seed and one source read the same run (pitch and
   volume of one walk). Streams come back as streams, leaves as a line,
   parts as { }.

     duration/euclid                    ( -- durations )
     @^{ k:5 n:16 } duration/euclid      ( settings -- durations )
     [c8 d e f g] durations duration/emi ( durations -- durations )"
  (:require [clojure.string :as str]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib]
            [musics.algo.tree.registry :as reg]
            [musics.algo.random.core :as rc]
            [musics.domain :as d]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

(def ^:dynamic *live*
  "While a live voice's recipe runs (musics.mu.live): {:tctx t :chain c
   :cache a :seen s} -- every algo word reads t's settings after its own
   @^{ } (t is fitted to its tree, so it knows every param the recipe
   reads), then the recipe's head, then -- for key and meter -- c (the
   music the voice plays in) and :ROOT's, then the defaults. a (an atom, kept across runs) holds each word's last
   result, by the word and how many times it ran before in this run (s,
   an atom, new each run): a word whose inputs and settings are as they
   were gives that result again, so a change reruns only the words it
   reaches, and the others keep their random values."
  nil)

(defn- same?
  "Inputs alike: the same value, or a finite one printing the same
   (printing is isomorphic to reading)."
  [a b]
  (or (identical? a b)
      (and (not (mt/endless? a)) (not (mt/endless? b)) (= (p/show a) (p/show b)))))

(defn- cached
  "What the word gives on args with settings vals, from the live run's
   cache when it gave it before on the same, else (f), remembered unless
   endless (the cache would hold its head)."
  [word args vals f]
  (if-let [{:keys [cache seen]} (when vals *live*)]
    (let [k   [word ((swap! seen update word (fnil inc 0)) word)]
          hit (get @cache k)]
      (if (and hit (= vals (:vals hit)) (= (count args) (count (:args hit))) (every? true? (map same? args (:args hit))))
        (:result hit)
        (let [r (f)]
          (when-not (mt/endless? r) (swap! cache assoc k {:args args :vals vals :result r}))
          r)))
    (f)))

(def shelves
  "The shelves whose algos are mu words."
  #{"pitch" "duration" "volume" "articulation" "instrument" "leaf" "parallel"})

(defn- input
  "A mu value as the stream an algo's input of `type` takes: a line
   taken apart, data as its values, a stream as it is."
  [type x]
  (cond
    (and (v/material? x) (= :DATA (mt/kind x))) (mt/elements x)
    (or (v/material? x) (mt/leaf? x))
    (let [ls (mt/played-leaves x)]
      (case type
        :duration (map #(if (d/rest? %) % (:duration %)) ls)
        :pitch    (map #(some->> (:pitches %) seq (apply min)) ls)
        :leaf     ls
        (mt/elements x)))
    :else x))

(defn- output
  "What an algo gave, as a mu value."
  [out v]
  (case out
    :leaf (mt/line v)
    :part (mt/read-text (str "{" (str/join " " (map #(p/show (mt/line %)) v)) "}"))
    (if (sequential? v) (mt/as-part out (apply list v)) v)))

(defn- settings
  "[stack local]: an @^{ } on top is the word's own settings, {name value};
   a ^{ } there is the music's, refused."
  [word s]
  (case (when (seq s) (mt/kind (peek s)))
    :TCONTEXT [(pop s) (into {} (:params (v/node (peek s))))]
    :CONTEXT  (e/mu-error (str word ":") "^{ } is the music's -- an algo's settings are @^{ k:5 }")
    [s nil]))

(defn- around
  "[args wrap]: the inputs, and what goes around the result -- when the
   inputs are repeats or loops, each by the same count, the algo runs on
   what they repeat and the result is repeated as they were (music as
   the first input's kind of repeat: a tremolo, [: :])."
  [word out args]
  (let [reps (map mt/repeat-of args)]
    (cond
      (not-any? some? reps) [args identity]
      (and (every? some? reps) (apply = (map second reps)))
      (let [n (second (first reps))]
        [(map first reps) #(cond (not (#{:leaf :part} out)) (mt/repeated % n)
                                 (mt/repetition? (first args)) (mt/like-repeat (first args) %)
                                 :else (mt/repetition % n []))])
      :else (e/mu-error (str word ":") "its inputs repeat differently -- give it lines, or repeats by the same count"))))

(defn- algo-word [word short {:keys [in out]}]
  (fn [s]
    (let [[s local] (settings word s)
          n       (count in)
          _       (need s n word)
          [args wrap] (around word out (subvec s (- (count s) n)))
          tree    (apply (t/constructor (reg/algo short)) (map input in args))
          {src :tctx live-chain :chain} *live*
          _       (when src (t/fit! src tree))
          srcs    [local src e/*head*]
          chain   (remove nil? (concat live-chain [(:context (repo/current :ROOT))]))
          seed    (some #(get (if (t/tctx? %) @(:params-atom %) %) "seed") (remove nil? srcs))
          run     #(try (output out (t/run tree srcs chain))
                        (catch clojure.lang.ExceptionInfo ex
                          (e/mu-error (str word ":") (str/replace (ex-message ex) #"^musics\.algo\.tree: " ""))))
          vals    (when *live* (try [(t/settings tree srcs chain) seed] (catch Exception _ nil)))
          result  (cached word args vals #(if (number? seed) (rc/with-seed (long seed) (run)) (run)))]
      (conj (subvec s 0 (- (count s) n)) (wrap result)))))

(def ^:private plural
  {:pitch "pitches" :duration "durations" :volume "volumes" :articulation "articulations" :instrument "instruments"
   :leaf "line" :part "parallel"})

(defn- effect
  "The stack effect and what the word is: ( durations [@^{ … }] --
   durations )  its doc  @^{ its params }."
  [{:keys [in out doc params]}]
  (str "( " (str/join " " (map #(plural % (name %)) in)) (when (seq in) " ") "[@^{ … }] -- " (plural out (name out)) " )  "
       doc "  @^{ " (str/join " " (map #(name (:name %)) params)) " }"))

(defn register-all!
  "A word for every algo on a shelf in `shelves`. Returns how many."
  []
  (let [ws (for [[short e] (t/algos) :when (shelves (:shelf e))] [(:shelf e) short e])]
    (doseq [[shelf short e] ws]
      (word! shelf (name short) (effect e) (algo-word (str shelf "/" (name short)) short e)))
    (count ws)))

(register-all!)
