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

(defn- algo-params
  "[algo-name param-name spec] of each parameter in the :algo metadata of the functions of vars."
  [vars]
  (for [var vars
        :let [params (get-in (meta var) [:algo :params])]
        :when (map? params)
        [pname spec] params]
    [(keyword (name (:name (meta var)))) (name pname) spec]))

(defn- param-owner-algos
  "Every parameter of the algorithms loaded now, [algo-name param-name spec]: each
   public function of a musics.algo namespace whose :algo metadata lists :params."
  []
  (algo-params (for [ns (all-ns)
                     :when (re-find #"^musics\.algo\." (str (ns-name ns)))
                     [_ var] (ns-publics ns)]
                 var)))

(defn- word-names
  "The names of the words a body (parse nodes) runs, at any depth: a quotation's too."
  [body]
  (->> body
       (mapcat #(tree-seq (fn [n] (and (vector? n) (keyword? (first n)))) rest %))
       (filter #(and (vector? %) (= :Word (first %))))
       (map (fn [node] (let [x (second node)] (if (vector? x) (second x) x))))
       distinct))

(defn called-algos
  "The functions of musics.algo the word called name runs, following the words it defines: the
   vars of those with :algo :params. The algos a word is made of, so an input of it is read as
   they read it."
  ([name] (called-algos name #{}))
  ([name seen]
   (if (seen name)
     []
     (let [entry (try (e/lookup @e/state name) (catch Exception _ nil))]
       (cond
         (:var entry)  (let [v (:var entry)]
                         (if (and (re-find #"^musics\.algo\." (str (ns-name (:ns (meta v)))))
                                  (map? (get-in (meta v) [:algo :params])))
                           [v] []))
         (:body entry) (vec (distinct (mapcat #(called-algos % (conj seen name)) (word-names (:body entry)))))
         :else         [])))))

(defn- param-spec
  "One spec for the algo params called input: when several algos read a param
   by that name, the most common type, its range the widest of them (so no
   value any of them takes is refused), the rest from the first by name. The
   spec names its owner, :algo, only when one algo reads that name. The algos are
   those word calls (see called-algos) when any of them reads the name, else all
   that are loaded."
  [input word]
  (let [mine  (filter #(= input (second %)) (algo-params (called-algos word)))
        found (if (seq mine) mine (filter #(= input (second %)) (param-owner-algos)))]
    (when (seq found)
      (let [type   (key (apply max-key val (frequencies (map (comp :type #(nth % 2)) found))))
            same   (map #(nth % 2) (filter #(= type (:type (nth % 2))) found))
            lo     (keep :min same)
            hi     (keep :max same)
            owners (distinct (map first found))]
        (cond-> (select-keys (first same) [:type :default :doc :choices :min-key :max-key])
          (seq lo) (assoc :min (apply min lo))
          (seq hi) (assoc :max (apply max hi))
          (= 1 (count owners)) (assoc :algo (first owners)))))))

(defn- input-spec
  "The spec of one input of word: that of the algo params called the same (its
   range, default and doc), else one made from the value it starts with. It
   belongs to the algo that owns the param, else to the word."
  [word order input value]
  (let [base (or (param-spec input word)
                 {:type (cond (integer? value) :int (float? value) :double (string? value) :string
                              (keyword? value) :keyword (boolean? value) :bool :else :any)
                  :default value :doc ""})]
    (merge {:algo (keyword word)} base {:own input :param (keyword input) :at [input] :order order})))

(def tctx
  "The session's tctx (musics.registries/tctx): a Context whose keys are Clojure
   keywords (:reps, :steps), first in the chain every voice fed by a function of
   onset reads, filled by mu's T!, and stored in the repo as
   :tctx so a saved session keeps it."
  reg/tctx)

(defn tctx-node!
  "The tctx, put in the repo as the :CONTEXT node :tctx when it is not there (a
   reset empties the repo), and returned."
  []
  (when-not (identical? tctx (:context (repo/current :tctx)))
    (repo/commit-node! :tctx {:type :CONTEXT :id :tctx :context tctx :children []}))
  tctx)

(defn tctx-scope
  "The scope of voice (a keyword or string) in the tctx, a Context to put in front of it:
   the entries written :voice/key, read as :key (musics.domain.context/tctx-scope)."
  [voice]
  (c/tctx-scope (tctx-node!) voice))

(defn set-tctx!
  "Make settings (a mu Settings, @{ … }) the tctx's entries, all at once: a key it
   does not write is gone, a quotation is a computed key. Voices holding the tctx
   read the new entries at their next note."
  [settings]
  (let [node (tctx-node!)
        tmp  (c/context)]
    (doseq [[k x] (:entries settings)]
      (if (v/quotation? x)
        (c/tctx-derive tmp k (e/derived-fn x) (p/show x))
        (c/tctx! tmp k x)))
    (reset! (:envelopes-atom node) @(:envelopes-atom tmp))
    node))

(defn- settings-value
  "What a tctx entry is in a @{ }: a quotation for a computed key, the value itself, nil when
   there is no text to give (a function of time, a computed entry from no quotation)."
  [entry]
  (cond
    (instance? musics.domain.context.Derived entry)
    (when-let [text (:text entry)] (peek (e/run-text [] text)))
    (instance? musics.domain.context.Generator entry) nil
    :else entry))

(defn- nest-pairs
  "pairs [[k x] …] with a key written for a voice (:bass/steps) gathered under the voice,
   :bass @{ :steps 5 }, a dotted voice (:drums.kick/steps) nested, :drums @{ :kick @{ :steps 8 } };
   the keys of the tctx itself first, by name, then the voices in order."
  [pairs]
  (let [plain  (remove (comp namespace first) pairs)
        scoped (filter (comp namespace first) pairs)
        groups (group-by (fn [[k _]] (first (clojure.string/split (namespace k) #"\."))) scoped)]
    (into (vec (sort-by (comp name first) plain))
          (for [seg (sort (keys groups))]
            [(keyword seg)
             (v/->Settings
              (nest-pairs (for [[k x] (groups seg)
                                :let [more (rest (clojure.string/split (namespace k) #"\."))]]
                            [(if (seq more) (keyword (clojure.string/join "." more) (name k)) (keyword (name k))) x])))]))))

(defn- own-keys
  "The entries of the tctx written for voice, that voice's own and those of the voices nested
   in it (:drums.kick for :drums)."
  [voice]
  (let [n (name voice)]
    (filter (fn [[k _]] (when-let [ns (namespace k)] (or (= ns n) (clojure.string/starts-with? ns (str n "."))))) 
            @(:envelopes-atom (tctx-node!)))))

(defn tctx->settings
  "The tctx as a mu Settings, the shared keys first, in key order, then the voices, each
   as :bass @{ … }: a computed key as its quotation. An entry made in Clojure, with no text
   to give (a function of time, a computed entry from no quotation), is left out."
  []
  (v/->Settings
   (nest-pairs (for [[k entry] @(:envelopes-atom (tctx-node!))
                     :let [x (settings-value entry)] :when (some? x)]
                 [k x]))))

(defn scope->settings
  "What the tctx holds for voice as a mu Settings, its keys without its name, or nil when
   it holds nothing for it."
  [voice]
  (let [n (name voice)
        pairs (for [[k entry] (own-keys voice)
                    :let [x (settings-value entry)] :when (some? x)
                    :let [ns (namespace k)
                          rest-ns (when (not= ns n) (subs ns (inc (count n))))]]
                [(if rest-ns (keyword rest-ns (name k)) (keyword (name k))) x])]
    (when (seq pairs) (v/->Settings (nest-pairs pairs)))))

(defn own-keys-of
  "The keys the tctx holds for voice (a keyword), as a seq; empty for none."
  [voice]
  (map first (own-keys voice)))

(defn forget-scope!
  "Take everything the tctx holds for voice out of it. The number of entries taken out."
  [voice]
  (let [ks (map first (own-keys voice))]
    (swap! (:envelopes-atom (tctx-node!)) #(apply dissoc % ks))
    (count ks)))

(defn- onset-word?
  "Does word take the onset as an input -- $onset in its stack effect? Then the
   voice it plays is a function of onset: the word gives one leaf for the onset the
   voice is at, and the stack gives it the other inputs."
  [names]
  (boolean (some #{"onset"} names)))

(defn- onset-source
  "The function of a voice fed by a function of onset (compose/source): (onset lookup)
   -> the one leaf word gives at onset, the other inputs as the lookup gives them at that
   onset (its scope in the tctx, :bass/onsets, else the shared tctx, so an input can be a
   function of time too), and any keyword argument of a word it runs the value lookup gives
   for it -- also :onset. A word that leaves [] (a seq with nothing in it) has finished: the
   voice ends there."
  [word _voice all-names]
  (fn [onset lk]
    (let [args   (mapv (fn [n] (if (= n "onset") onset (lk (keyword n)))) all-names)
          lookup (fn [k] (if (= k :onset) onset (lk k)))]
      (e/with-lookup lookup
        #(let [left (e/run-word args word)]
           (when (empty? left) (e/mu-error word "leaves nothing to play"))
           (let [x (peek left)]
             (or (first (as-leaves x))
                 (when-not (music? x) (e/mu-error word "gave no leaf for the onset")))))))))

(defn- start-moment
  "The moment (System/nanoTime) the voices of the input being run start: its first voice's
   start, so they share it (eval/*start-moment*); now outside an input."
  []
  (if-let [a e/*start-moment*]
    (or @a (reset! a (System/nanoTime)))
    (System/nanoTime)))

(defn- start-origin
  "When a voice that starts delay whole notes (none: 0) after the input's voices has its
   time 0 (System/nanoTime): the delay at the tempo it plays under -- ctx's at its start,
   else the default's."
  [ctx delay]
  (let [tempo (double (c/ctx-value-chain (keep :context [(when ctx (v/node ctx)) (repo/current :ROOT)])
                                         "Tempo" 0))]
    (+ (start-moment) (long (* 1e9 4 60 (/ (double (or delay 0)) tempo))))))

(defn- play-onset-word!
  [word voice values ctx staged all-names delay]
  (when staged (e/mu-error (str word ":") "a voice that is a function of onset starts now: -> , not -|>"))
  (let [names (vec (remove #{"onset"} all-names))
        specs (into {} (map-indexed (fn [i [n x]] [(keyword n) (input-spec word i n x)]) (map vector names values)))]
    (inputs/clear! voice)
    (inputs/set-inputs! voice (map vector names values) specs)
    (let [f (onset-source word voice all-names)]
      ;; ask once now, at the start, so a word that fails is refused here, not heard
      (try (f 0 (c/getter [(tctx-scope voice) (tctx-node!)] 0))
           (catch Throwable t (inputs/clear! voice) (throw t)))
      (swap! word-voices assoc voice word)
      (swap! sources dissoc voice)
      (mt/play-source! (compose/source f [(tctx-scope voice) (tctx-node!)]) (mt/read-text "repeat ~ [r8]") voice ctx
                       (start-origin ctx delay)))
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
  [word voice values ctx join? staged & [delay]]
  (reg/log! :live-word)
  (if (onset-word? (map first (e/live-inputs word)))
    (play-onset-word! word voice values ctx staged (mapv first (e/live-inputs word)) delay)
    (do (when delay
          (e/mu-error "later:" "only a voice that is a function of onset (a word with $onset) starts later"))
        (play-plain-word! word voice values ctx join? staged))))

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
