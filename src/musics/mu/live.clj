(ns musics.mu.live
  "Live voices: a play word given a quotation '( … ), or a word written with $
   inputs, plays live (doc/md/mu-repl.md). Each note the voice reaches is the
   next note of what the quotation, or the word, gives now -- run on the music
   under it, or on nothing, or on the inputs it holds -- and a change (an
   input stored, a new quotation by retree) runs it once more, the voice going
   on at its position (musics.mu.voice)."
  (:require [musics.compose :as compose]
            [musics.domain.context :as c]
            [musics.engine :as engine]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.mu.voice :as voice]
            [musics.registries :as reg]
            [musics.repo :as repo]))

(defn recipe?
  "A quotation '( … ): what a play word plays live."
  [x]
  (v/quotation? x))

(defn- music? [x] (or (v/material? x) (mt/leaf? x) (mt/endless? x) (and (sequential? x) (every? mt/leaf? x))))

(defn- as-leaves
  "What a quotation or a word left, as the leaves it plays, a loop's endlessly."
  [x]
  (cond
    (mt/loop? x) (mapcat mt/played-leaves (mt/elements x))
    (music? x)   (mt/played-leaves x)
    :else        (e/mu-error "a live voice plays music, not" (p/show x))))

(defn- result
  "What quotation q gives on x (nil: on nothing): a mu value."
  [q x]
  (let [left (e/call (if (some? x) [x] []) q)]
    (when (empty? left) (e/mu-error "the quotation leaves nothing to play"))
    (peek left)))

(def ^:private sources
  "Live name -> {:x x :recipe q}: the quotation and the music it runs on (nil: none)."
  (atom {}))

(def ^:private word-voices
  "Voice id -> the word it plays (play-word!), to start it again."
  (atom {}))

(def ^:private counter (atom 0))

(defn- fresh-name []
  (keyword (str "live" (swap! counter inc))))

(defn- run-fn [q x]
  (fn [_vals _chain] (as-leaves (result q x))))

(defn play!
  "A play word given a quotation: bind a live name (the voice's own, or a fresh
   one) and play x -- or, on nothing, the quotation's first result -- through it.
   staged: [count] when it waits for a boundary (->>)."
  [x q ctx join? target staged]
  (let [name (or target (when (v/material? x) (mt/voice-name x)) (fresh-name))
        ;; run once now, so a quotation that fails is refused here, not heard
        first-run (doto (result q x) as-leaves)
        form (if (some? x) x first-run)]
    (inputs/clear! name)
    (inputs/set-inputs! name [] {})
    (swap! word-voices dissoc name)
    (swap! sources assoc name {:x x :recipe q})
    (voice/follow! name (run-fn q x) (p/show q))
    (if staged
      (mt/stage! form ctx (first staged) join? name name)
      (mt/play! form ctx join? name name))))

(defn- param-owner-algos
  "Every parameter of the algorithms loaded now, [algo-name param-name spec]: each
   public function of a musics.algo namespace whose :algo metadata lists :params."
  []
  (for [ns (all-ns)
        :when (re-find #"^musics\.algo\." (str (ns-name ns)))
        [sym var] (ns-publics ns)
        :let [params (get-in (meta var) [:algo :params])]
        :when (map? params)
        [pname spec] params]
    [(keyword (name sym)) (name pname) spec]))

(defn- param-spec
  "One spec for the algo params called input: when several algos read a param
   by that name, the most common type, its range the widest of them (so no
   value any of them takes is refused), the rest from the first by name. The
   spec names its owner, :algo, only when one algo reads that name."
  [input]
  (let [found (filter #(= input (second %)) (param-owner-algos))]
    (when (seq found)
      (let [type   (key (apply max-key val (frequencies (map (comp :type #(nth % 2)) found))))
            same   (map #(nth % 2) (filter #(= type (:type (nth % 2))) found))
            lo     (keep :min same)
            hi     (keep :max same)
            owners (distinct (map first found))]
        (cond-> (select-keys (first same) [:type :default :doc :choices])
          (seq lo) (assoc :min (apply min lo))
          (seq hi) (assoc :max (apply max hi))
          (= 1 (count owners)) (assoc :algo (first owners)))))))

(defn- input-spec
  "The spec of one input of word: that of the algo params called the same (its
   range, default and doc), else one made from the value it starts with. It
   belongs to the algo that owns the param, else to the word."
  [word order input value]
  (let [base (or (param-spec input)
                 {:type (cond (integer? value) :int (float? value) :double (string? value) :string
                              (keyword? value) :keyword (boolean? value) :bool :else :any)
                  :default value :doc ""})]
    (merge {:algo (keyword word)} base {:own input :param (keyword input) :at [input] :order order})))

(inputs/alive! (fn [voice] (boolean (engine/voice-at voice))))

(def tctx
  "The session's tctx (musics.registries/tctx): a Context whose keys are Clojure
   keywords (:reps, :steps), first in the chain every voice fed by a function of
   beat reads, filled by mu's T! and tctx-derive, and stored in the repo as
   :tctx so a saved session keeps it."
  reg/tctx)

(defn tctx-node!
  "The tctx, put in the repo as the :CONTEXT node :tctx when it is not there (a
   reset empties the repo), and returned."
  []
  (when-not (identical? tctx (:context (repo/current :tctx)))
    (repo/commit-node! :tctx {:type :CONTEXT :id :tctx :context tctx :children []}))
  tctx)

(defn- beat-word?
  "Does word take the beat as an input -- $beat in its stack effect? Then the
   voice it plays is a function of beat: the word gives one leaf for the beat the
   voice is at, and the stack gives it the other inputs."
  [names]
  (boolean (some #{"beat"} names)))

(defn- beat-source
  "The function of a voice fed by a function of beat (compose/source): (beat lookup)
   -> the one leaf word gives at beat, the other inputs read as they are now
   (:voice/input stores one), and any keyword argument of a word it runs the
   value lookup gives for it -- also :beat."
  [word voice all-names]
  (fn [beat lk]
    (let [vals   (inputs/values voice)
          args   (mapv (fn [n] (if (= n "beat") beat (get vals n))) all-names)
          lookup (fn [k] (if (= k :beat) beat (lk k)))]
      (e/with-lookup lookup
        #(let [left (e/run-word args word)]
           (when (empty? left) (e/mu-error word "leaves nothing to play"))
           (or (first (as-leaves (peek left)))
               (e/mu-error word "gave no leaf for the beat")))))))

(defn- play-beat-word!
  [word voice values ctx staged all-names]
  (when staged (e/mu-error (str word ":") "a voice that is a function of beat starts now: -> , not -|>"))
  (let [names (vec (remove #{"beat"} all-names))
        specs (into {} (map-indexed (fn [i [n x]] [(keyword n) (input-spec word i n x)]) (map vector names values)))]
    (inputs/clear! voice)
    (inputs/set-inputs! voice (map vector names values) specs)
    (let [f (beat-source word voice all-names)]
      ;; ask once now, at the start, so a word that fails is refused here, not heard
      (try (f 0 (c/getter [(tctx-node!)] 0))
           (catch Throwable t (inputs/clear! voice) (throw t)))
      (swap! word-voices assoc voice word)
      (swap! sources dissoc voice)
      (mt/play-source! (compose/source f (tctx-node!)) (mt/read-text "repeat ~ [r8]") voice ctx))
    voice))

(defn- play-plain-word!
  [word voice values ctx join? staged]
  (let [names (mapv first (e/live-inputs word))
        specs (into {} (map-indexed (fn [i [n x]] [(keyword n) (input-spec word i n x)]) (map vector names values)))
        _     (inputs/clear! voice)
        _     (inputs/set-inputs! voice (map vector names values) specs)
        run   (fn [vals _chain]
                (let [left (e/run-word (mapv vals names) word)]
                  (when (empty? left) (e/mu-error word "leaves nothing to play"))
                  (as-leaves (peek left))))]
    (try
      ;; run once now, so a word that fails is refused here, not heard
      (doall (take 1 (run (inputs/values voice) nil)))
      (catch Throwable t (inputs/clear! voice) (throw t)))
    (swap! word-voices assoc voice word)
    (swap! sources dissoc voice)
    (voice/follow! voice run (str word))
    (let [form (mt/read-text "repeat ~ [r8]")]
      (if staged
        (mt/stage! form ctx (first staged) join? voice voice)
        (mt/play! form ctx join? voice voice)))
    voice))

(defn play-word!
  "Start voice (a keyword id) from the word `word`, written with : … ; and
   $ inputs, those inputs holding values (in the order of its stack effect).
   Each note the voice reaches is the next of the seq the word gives with
   the inputs it holds now; :voice/input reads or stores one. staged:
   [count] when it waits for a boundary (->>)."
  [word voice values ctx join? staged]
  (reg/log! :live-word)
  (if (beat-word? (map first (e/live-inputs word)))
    (play-beat-word! word voice values ctx staged (mapv first (e/live-inputs word)))
    (play-plain-word! word voice values ctx join? staged)))

(defn input-voice
  "The id of the live voice that word's voice argument (an id or index)
   addresses, or nil when it plays nothing live."
  [word voice]
  (let [id (try (mt/voice-id word voice) (catch Exception _ nil))
        nm (or (some-> id engine/voice-at :algo) id)]
    (when (and nm (inputs/has? nm)) nm)))

;; -- addressing a live voice ----------------------------------------------

(defn replay
  "What to put on the stack to play the live voice id again, as it
   started -- [x q], or [q] on nothing, or a word voice's inputs and its quoted
   name -- nil for a voice playing nothing live."
  [id]
  (let [nm (or (some-> (engine/voice-at id) :algo) id)]
    (if-let [w (get @word-voices nm)]
      (conj (mapv #(inputs/value nm %) (inputs/names nm)) (symbol w))
      (when-let [{:keys [x recipe]} (get @sources nm)]
        (if (some? x) [x recipe] [recipe])))))

(defn retree!
  "A new quotation for the live voice word addresses, the voice going on at its
   position."
  [word q voice]
  (let [nm (input-voice word voice)
        {:keys [x]} (get @sources nm)]
    (when-not (and nm (get @sources nm))
      (e/mu-error (str word ":") (pr-str voice) "plays no quotation -- give a play word one: x '( … ) :riff ->"))
    (swap! sources assoc nm {:x x :recipe q})
    (voice/retarget! nm (run-fn q x) (p/show q))
    nm))
