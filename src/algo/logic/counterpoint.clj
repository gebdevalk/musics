(ns algo.logic.counterpoint
  "Species counterpoint after Knud Jeppesen's Kontrapunkt: two to four
   voices against a given cantus prius factus, in any of the five
   kinds, searched with core.logic (see doc/counterpoint.md).

     (counterpoint {:cantus [62 65 64 62 67 65 69 67 65 64 62]
                    :voices 2 :kind 1})
     ;; => {:voices [[[pitch dur] ...] ...] :mode :dorian :penalty 9 ...}

   Bars are filled left to right, each added voice's notes for the bar
   chosen from its candidates (and, in the 4th and 5th kinds, its
   rhythm from the kind's bar patterns) -- every choice a core.logic
   choice point, every hard rule (algo.logic.counterpoint.rules)
   pruning at once, so the search backtracks out of dead ends instead
   of breaking a rule. Several searches with differently shuffled
   candidates give several solutions; the one with the fewest soft-rule
   penalties is kept."
  (:refer-clojure :exclude [==])
  (:require [clojure.core.logic :refer [== run fail]]
            [clojure.core.logic.protocols :refer [take*]]
            [clojure.string :as str]
            [algo.logic.counterpoint.intervals :as iv]
            [algo.logic.counterpoint.rules :as r]
            [algo.random :as rand]
            [algo.random.core :as rcore]
            [common.music-data :as data]
            [core.domain.flat-domain :as d]
            [input.reader.leaf-parser :as lp]))

;; ---------------------------------------------------------------------------
;; Voices
;; ---------------------------------------------------------------------------

(def ranges
  "Jeppesen's voice ranges, as MIDI (the clefs' compass)."
  {:soprano [60 79] :alto [53 74] :tenor [48 69] :bass [40 62]})

(def ^:private layouts
  {3 [:soprano :tenor :bass]
   4 [:soprano :alto :tenor :bass]})

(defn- fit-range
  "range moved by octaves until it holds the cantus best."
  [[lo hi] cantus]
  (let [clo (apply min cantus) chi (apply max cantus)
        off (fn [k] (+ (max 0 (- (+ lo k) clo)) (max 0 (- chi (+ hi k)))))
        ;; nearest shift first: min-key keeps the last of equal ones
        k   (apply min-key off (map #(* 12 %) [3 -3 2 -2 1 -1 0]))]
    [(+ lo k) (+ hi k)]))

(defn- layout
  "The parts top to bottom: [{:name :range :cantus?}], the cantus' part
   holding the cantus and the others placed around it; `rs` overrides
   ranges per part name."
  [cantus nv cantus-in rs]
  (let [clo (apply min cantus) chi (apply max cantus)
        ranges (merge ranges rs)]
    (if (= 2 nv)
      (if (contains? #{:soprano :alto} cantus-in)
        [{:name :cantus :cantus? true} {:name :lower :range (get rs :lower [(- clo 12) chi])}]
        [{:name :upper :range (get rs :upper [clo (+ chi 12)])} {:name :cantus :cantus? true}])
      (let [names (layouts nv)
            fits? (fn [nm] (let [[lo hi] (ranges nm)] (<= lo clo chi hi)))
            ;; by default the tenor, or else the first part whose range
            ;; holds the cantus
            ci    (or cantus-in
                      (first (filter #(and (some #{%} names) (fits? %)) [:tenor :alto :soprano :bass]))
                      :tenor)
            _     (when-not (some #{ci} names)
                    (throw (ex-info (str "counterpoint: :cantus-in " ci " is not one of " names) {})))
            shift (- (first (fit-range (ranges ci) cantus)) (first (ranges ci)))]
        (mapv (fn [nm] (if (= nm ci)
                         {:name nm :cantus? true}
                         {:name nm :range (mapv #(+ shift %) (ranges nm))}))
              names)))))

(defn- kinds-of
  "The kind of each added part, top to bottom: `kinds` when given, else
   `kind` for the top added part and 1 for the others."
  [parts kind kinds]
  (let [added (count (remove :cantus? parts))]
    (vec (or kinds (cons kind (repeat (dec added) 1))))))

;; ---------------------------------------------------------------------------
;; Rhythm: each kind's bar patterns, [len flag] in eighths
;; ---------------------------------------------------------------------------

(def ^:private florid-bars
  "5th-species bar rhythms (Jeppesen's florid counterpoint): eighths only
   in pairs on a weak quarter; a tie only from a half on the weak half."
  [[[4] [4]] [[4] [2] [2]] [[2] [2] [4]] [[2] [2] [2] [2]] [[6] [2]]
   [[2] [1 nil] [1 nil] [2] [2]] [[2] [2] [2] [1 nil] [1 nil]] [[4] [2] [1 nil] [1 nil]]
   [[4 :tied] [4]] [[4 :tied] [2] [2]]])

(defn- ties-ok?
  "A pattern opening with a tie needs a half struck on the previous
   bar's weak half."
  [st p b pat]
  (or (not= :tied (second (first pat)))
      (let [e (peek (get st p))]
        (and e (:n e) (= 4 (:len e)) (= (+ (* 8 (dec b)) 4) (:on e))))))

(defn- patterns
  "The bar patterns part p may take in bar b, in search order."
  [ctx st p b rng]
  (let [n (:n ctx) k (get-in ctx [:parts p :kind])
        last? (= b (dec n)) first? (zero? b)]
    (->> (cond
           last?    [[[8]]]
           (= k 1)  [[[8]]]
           (= k 2)  (if first? [[[4 :rest] [4]]] [[[4] [4]]])
           (= k 3)  [[[2] [2] [2] [2]]]
           (= k 4)  (if first? [[[4 :rest] [4]]] [[[4 :tied] [4]] [[4] [4]]])
           (= k 5)  (if first?
                      (rand/shuffle rng [[[4 :rest] [4]] [[4 :rest] [2] [2]]])
                      (rand/shuffle rng florid-bars)))
         (filter #(ties-ok? st p b %)))))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

(defn- final-notes
  "The notes part p may end on: the final (the bass, or both voices of
   two), else the final, its fifth or its major third."
  [ctx p]
  (let [fpc (mod (:m (:final ctx)) 12)
        ok  (if (or (= 2 (:nv ctx)) (= p (dec (:nv ctx))))
              #{fpc}
              #{fpc (mod (+ fpc 7) 12) (mod (+ fpc 4) 12)})]
    (filter #(ok (mod (:m %) 12)) (get-in ctx [:parts p :cands]))))

(defn- choices
  "The candidates for part p's note, in search order: an allowed
   melodic interval from the last note (steps first, then thirds, then
   larger leaps, shuffled within each), ficta only where it may stand;
   in the last bar only the notes it may end on, and as the last note
   of the bar before only a step from one of them -- the cadence, so
   the search doesn't find a dead end only at the end. (A bass of
   three or four voices may also leap a fourth or fifth to the final.)"
  [ctx st p b last-in-bar? rng]
  (let [cands (get-in ctx [:parts p :cands])
        n     (:n ctx)
        a1    (r/last-attack st p)
        fins  (final-notes ctx p)
        ok    (fn [c] (and (case (:ficta c)
                             :cadence (= b (- n 2))
                             :final   (= b (dec n))
                             true)
                           (cond (= b (dec n)) (some #(= (:m c) (:m %)) fins)
                                 (and (= b (- n 2)) last-in-bar? (= 2 (:nv ctx)))
                                 (some #(iv/step? c %) fins)
                                 ;; the bass of three or four voices: by step, or
                                 ;; the leap of a fourth or fifth (V-I)
                                 (and (= b (- n 2)) last-in-bar? (= p (dec (:nv ctx))))
                                 (some #(or (iv/step? c %)
                                            (and (zero? (:octaves (iv/interval c %)))
                                                 (contains? #{:P4 :P5} (iv/quality c %))))
                                       fins)
                                 :else true)))
        cs    (filter ok (if a1 (filter #(iv/melodic? (:n a1) %) cands) cands))
        size  (fn [c] (if a1 (min 3 (abs (long (iv/steps (:n a1) c)))) 0))]
    (->> (group-by size cs) (sort-by key) (mapcat #(rand/shuffle rng (val %))))))

(defn- place
  "st with e placed in part p, or nil when it breaks a hard rule (or the
   search's step budget is spent)."
  [ctx st p e]
  (when (<= (swap! (:steps ctx) inc) (:budget ctx))
    (let [[vs st'] (r/violations ctx st p e false)]
      (when (empty? vs) st'))))

(defn- firsto
  "A goal trying (f c) for each of cs in turn, depth first, keeping the
   first that succeeds. Like (fresh [x] (membero x cs) (project [x] (f
   x))) under run 1, but without core.logic's interleaving: that keeps
   a stream per alternative tried, and a long search runs out of
   stack."
  [cs f]
  (let [first-answer (fn [stream]
                       (let [t (take* stream)]
                         (if (instance? clojure.core.logic.Substitutions t) t (first t))))]
    (fn [a] (some (fn [c] (first-answer ((f c) a))) cs))))

(declare searcho)

(defn- eventso [ctx st b p pat i t tasks out]
  (if (= i (count pat))
    (searcho ctx st tasks out)
    (let [[len flag] (nth pat i)
          next-o (fn [st'] (eventso ctx st' b p pat (inc i) (+ t len) tasks out))]
      (case flag
        :rest (next-o (update st p (fnil conj []) {:n nil :on t :len len :bar b}))
        :tied (if-let [st' (place ctx st p {:n (:n (peek (get st p))) :on t :len len :bar b :tied? true})]
                (next-o st')
                fail)
        (firsto (choices ctx st p b (= i (dec (count pat))) (:rng ctx))
                (fn [n] (if-let [st' (place ctx st p {:n n :on t :len len :bar b})] (next-o st') fail)))))))

(defn- searcho [ctx st tasks out]
  (if (empty? tasks)
    ;; boxed: core.logic doesn't walk into a volatile, and it can't walk
    ;; the Clojure sets inside the score
    (== out (volatile! st))
    (let [[b p] (first tasks)]
      (firsto (patterns ctx st p b (:rng ctx))
              (fn [pat] (eventso ctx st b p pat 0 (* 8 b) (rest tasks) out))))))

(defn- with-big-stack
  "(f) on a thread with a 512 MB stack: core.logic nests a frame per
   choice, and a long search runs past the default stack."
  [f]
  (let [p (promise)
        t (Thread. nil #(deliver p (try {:ok (f)} (catch Throwable e {:err e}))) "counterpoint" (* 512 1024 1024))]
    (.start t)
    (let [{:keys [ok err]} @p] (if err (throw err) ok))))

(defn- order
  "Placing order within a bar: 1st-species parts bottom up, then the
   florid ones bottom up -- so a florid voice's dissonances are judged
   against everything else in its bar."
  [parts]
  (let [added (keep-indexed (fn [i pt] (when-not (:cantus? pt) i)) parts)
        {fl true pl false} (group-by #(not= 1 (:kind (nth parts %))) added)]
    (concat (sort > pl) (sort > fl))))

(defn- context
  [{:keys [cantus voices kind kinds cantus-in mode budget] rs :ranges
    :or {voices 2 kind 1 budget 10000}}]
  (when-not (<= 2 voices 4) (throw (ex-info "counterpoint: :voices must be 2, 3 or 4" {:voices voices})))
  (let [final (peek (vec cantus))
        mode  (or mode (iv/infer-mode cantus)
                  (throw (ex-info "counterpoint: the cantus fits no church mode on its final; give :mode" {})))
        parts (layout cantus voices cantus-in rs)
        ks    (iv/mode-key final mode)
        kinds (kinds-of parts kind kinds)
        parts (loop [ps parts i 0 out []]
                (if-let [pt (first ps)]
                  (if (:cantus? pt)
                    (recur (rest ps) i (conj out (assoc pt :kind :cantus)))
                    (recur (rest ps) (inc i)
                           (conj out (assoc pt :kind (nth kinds i)
                                               :cands (iv/candidates final mode (:range pt))))))
                  out))
        fnote (iv/note ks final)]
    {:parts parts :n (count cantus) :nv voices :mode mode :final fnote
     :lt (mod (dec final) 12) :ks ks :budget budget}))

(defn- cantus-state [ctx cantus]
  (let [ci (first (keep-indexed #(when (:cantus? %2) %1) (:parts ctx)))
        note (fn [m] (or (first (filter #(= m (:m %)) (iv/candidates (:m (:final ctx)) (:mode ctx) [m m])))
                         {:m m :d (:d (iv/note (:ks ctx) (dec m)))}))]
    {ci (vec (map-indexed (fn [b m] {:n (note m) :on (* 8 b) :len 8 :bar b}) cantus))}))

(defn- ->voices
  "The parts as [[pitch dur] ...], top to bottom: ties merged, durations
   as note values (1 = a whole note)."
  [ctx st]
  (vec (for [p (range (:nv ctx))]
         (reduce (fn [acc e]
                   (if (:tied? e)
                     (update-in acc [(dec (count acc)) 1] + (/ (:len e) 8))
                     (conj acc [(:m (:n e)) (/ (:len e) 8)])))
                 [] (get st p)))))

(defn counterpoint
  "Counterpoint against `cantus` (MIDI notes, one whole note each):
     :voices 2-4 (the cantus included)   :kind 1-5 for the florid voice
     :kinds  per added voice, top to bottom (overrides :kind; default:
             the top added voice in :kind, the others in 1st species)
     :cantus-in which part holds the cantus (2 voices: :soprano/:alto =
             above, else below; 3-4 voices: :soprano :alto :tenor :bass,
             default the tenor, or the first part whose range holds it)
     :mode   a church mode, :major or :minor (default: from the final)
     :ranges {part [lo hi]} (MIDI) replacing a default voice range: by
             part name, or :upper/:lower for the added voice of two
     :seed   for the shuffles (reproducible)   :tries searches (default 10)
     :budget notes tried per search (default 10000): many short searches
             find more, and better, than a few long ones
   Returns {:voices [[[pitch dur] ...] ...] (top to bottom) :mode
   :parts :penalty :penalties}, or nil when no search found a solution."
  [{:keys [cantus seed tries] :or {seed 1 tries 10} :as opts}]
  (let [ctx0  (context opts)
        tasks (for [b (range (:n ctx0)) p (order (:parts ctx0))] [b p])
        st0   (cantus-state ctx0 cantus)
        sols  (for [i (range tries)
                    :let [ctx (assoc ctx0 :steps (atom 0) :rng (doto (atom nil) (rcore/seed! (+ seed i))))
                          sol (some-> (with-big-stack #(first (run 1 [q] (searcho ctx st0 tasks q)))) deref)]
                    :when sol]
                [(r/penalty ctx0 sol) sol])]
    (when-let [[pen st] (first (sort-by first (doall sols)))]
      {:voices    (->voices ctx0 st)
       :mode      (:mode ctx0)
       :parts     (mapv (juxt :name :kind) (:parts ctx0))
       :penalty   pen
       :penalties (r/penalties ctx0 st)})))

;; ---------------------------------------------------------------------------
;; Checking given music
;; ---------------------------------------------------------------------------

(defn- events-of
  "[[pitch dur] ...] as events in eighths, notes split at bar lines into
   a strike and tied continuations."
  [ctx pairs]
  (let [cands (:all-cands ctx)
        note  (fn [m] (or (first (filter #(= m (:m %)) cands))
                          (try (iv/note (:ks ctx) m) (catch Exception _ {:m m :d (:d (iv/note (:ks ctx) (dec m)))}))))]
    (loop [ps pairs t 0 out []]
      (if-let [[m dur] (first ps)]
        (let [len (long (* 8 dur))
              pieces (loop [t t len len first? true acc []]
                       (if (pos? len)
                         (let [room (- 8 (mod t 8)) l (min len room)]
                           (recur (+ t l) (- len l) false
                                  (conj acc (cond-> {:n (when m (note m)) :on t :len l :bar (quot t 8)}
                                              (and (not first?) m) (assoc :tied? true)))))
                         acc))]
          (recur (rest ps) (+ t len) (into out pieces)))
        out))))

(defn check
  "Every hard rule a finished score breaks. Takes a result of
   counterpoint, or the same shape by hand: {:voices [[[pitch dur] ...]
   ...] top to bottom, :parts [[name kind] ...] with the cantus' kind
   :cantus, :mode (default: from the cantus' final)}. Each violation
   names its :rule, :bar and :part."
  [{:keys [voices parts mode]}]
  (let [ci     (first (keep-indexed (fn [i [_ k]] (when (= :cantus k) i)) parts))
        cantus (mapv first (nth voices ci))
        kinds  (vec (keep (fn [[_ k]] (when (not= :cantus k) k)) parts))
        ctx    (context {:cantus cantus :voices (count voices) :kinds kinds :mode mode
                         :cantus-in (if (= 2 (count voices)) (if (zero? ci) :soprano :bass)
                                        (nth (layouts (count voices)) ci))})
        ctx    (assoc ctx :steps (atom 0) :budget Long/MAX_VALUE
                      :all-cands (iv/candidates (:m (:final ctx)) (:mode ctx) [24 119]))
        evs    (into {} (map-indexed (fn [p v] [p (events-of ctx v)]) voices))
        ord    (order (:parts ctx))]
    (loop [st (cantus-state ctx cantus) b 0 found []]
      (if (= b (:n ctx))
        found
        (let [[st found]
              (reduce (fn [[st found] p]
                        (reduce (fn [[st found] e]
                                  (let [[vs st'] (r/violations ctx st p e)]
                                    [st' (into found (map #(assoc % :part (get-in ctx [:parts p :name])) vs))]))
                                [st found]
                                (filter #(= b (:bar %)) (evs p))))
                      [st found] ord)]
          (recur st (inc b) found))))))

(defn check-cantus
  "Jeppesen's rules for a cantus firmus that it breaks, as warnings: it
   begins and ends on the final, ends by step, moves mostly by step
   within a tenth, with no repeated notes, one climax, and only
   singable intervals."
  [cantus]
  (let [mode (iv/infer-mode cantus)
        cands (when mode (iv/candidates (peek (vec cantus)) mode [24 119]))
        ns   (mapv (fn [m] (or (first (filter #(= m (:m %)) cands)) {:m m :d 0})) cantus)
        pairs (map vector ns (rest ns))]
    (cond-> []
      (nil? mode) (conj {:rule :mode :doc "no church mode on its final holds every note"})
      (not= (mod (first cantus) 12) (mod (peek (vec cantus)) 12)) (conj {:rule :begin-on-final})
      (and mode (not (iv/step? (peek (pop ns)) (peek ns)))) (conj {:rule :end-by-step})
      (and mode (some (fn [[a b]] (not (iv/melodic? a b))) pairs)) (conj {:rule :melodic-intervals})
      (some (fn [[a b]] (iv/same? a b)) pairs) (conj {:rule :repetition})
      (and mode (> (- (apply max (map :d ns)) (apply min (map :d ns))) 9)) (conj {:rule :span})
      (> (count (filter #{(apply max cantus)} cantus)) 1) (conj {:rule :climax})
      (and mode (> (count (filter (fn [[a b]] (iv/leap? a b)) pairs)) (quot (count pairs) 2)))
      (conj {:rule :mostly-steps}))))

;; ---------------------------------------------------------------------------
;; Output
;; ---------------------------------------------------------------------------

(defn- spell
  "Pitch m as musics text in the mode (\"B&4/\" for the B-flat of D
   dorian, \"C#5/\" for its leading tone), from the note's own letter;
   a pitch the mode doesn't know is spelled the generic way."
  [cands m]
  (if-let [{:keys [d]} (first (filter #(= m (:m %)) cands))]
    (let [letter (nth data/letter-order (mod d 7))
          oct    (quot d 7)
          acc    (- m (+ (* 12 (inc oct)) (data/diatonic-pcs letter)))]
      (str (str/upper-case (str letter)) ({-2 "&&" -1 "&" 0 "" 1 "#" 2 "##"} acc) oct "/"))
    (lp/pitch->mus m)))

(defn ->mus
  "A result as musics text: the voices in parallel, { [..] [..] },
   pitches absolute and spelled in the mode, ready for
   musics.core/parse (optionally named id)."
  ([result] (->mus result nil))
  ([{:keys [voices parts mode]} id]
   (let [ci    (first (keep-indexed (fn [i [_ k]] (when (= :cantus k) i)) parts))
         final (first (peek (nth voices ci)))
         cands (iv/candidates final mode [24 119])
         part  (fn [[m dur]]
                 (let [rest (lp/part->mus (d/rest* nil nil dur))]
                   (if m (str (spell cands m) (subs rest 1)) rest)))]
     (str "{" (when id (str (name id) ": "))
          (str/join " " (for [v voices] (str "[ !acc:explicit " (str/join " " (map part v)) " ]")))
          " }"))))

;; ---------------------------------------------------------------------------
;; As a tree algo
;; ---------------------------------------------------------------------------

(defn species
  "Species counterpoint after Jeppesen against the child, a cantus prius
   factus of whole notes: :voices parts (the cantus included), the top
   added one in :kind and the others in 1st species. [pitch dur] layers
   top to bottom, durations as note values (1 = a whole note) -- or []
   when no solution is found. See algo.logic.counterpoint/counterpoint."
  {:algo {:short :species :category "melodic" :in [:pitches] :out :layers
          :params {:voices    {:type :int :min 2 :max 4 :default 2 :doc "parts, the cantus included"}
                   :kind      {:type :int :min 1 :max 5 :default 1 :doc "species (1st to 5th) of the florid voice"}
                   :cantus-in {:type :keyword :default :auto :doc "the part holding the cantus"
                               :choices [:auto :soprano :alto :tenor :bass]}
                   :mode      {:type :keyword :default :auto :doc "church mode, :major or :minor"
                               :choices [:auto :dorian :phrygian :lydian :mixolydian :aeolian :ionian :major :minor]}
                   :seed      {:type :int :min 0 :max 1000000 :default 1 :doc "search order (reproducible)"}}}}
  [cantus voices kind cantus-in mode seed]
  (or (:voices (counterpoint {:cantus (vec cantus) :voices voices :kind kind :seed seed
                              :cantus-in (when (not= :auto cantus-in) cantus-in)
                              :mode (when (not= :auto mode) mode)}))
      []))
