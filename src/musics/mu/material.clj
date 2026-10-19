(ns musics.mu.material
  "Working with mu's musical values: their elements, building a value
   from elements (by printing them and reading the text back, which
   printing's isomorphism makes safe), storing, fetching and playing.
   Shared by the kernel and the tools."
  (:require [clojure.string :as str]
            [musics.core :as m]
            [musics.domain :as d]
            [musics.conductor :as conductor]
            [musics.domain.context :as c]
            [musics.engine :as engine]
            [musics.mu.eval :as e]
            [musics.mu.grammar :as g]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.registries :as reg]
            [musics.repo :as repo]))

(def leaf? v/leaf?)

(defn kind
  "A Material's container type (:SEQ :PAR :CONTEXT :DATA), else nil."
  [x]
  (when (v/material? x) (:type (v/node x))))

(defn- instruction? [x] (and (map? x) (contains? #{:assignment :instruction} (:type x))))

(defn read-text
  "The value of one literal written as text, read against the current
   repo -- not stored."
  [text]
  (let [nodes (filterv v/literal? (rest (g/parse text)))]
    (when (not= 1 (count nodes)) (e/mu-error "can't build a value from" (pr-str text)))
    (first (:values (v/read-literals nodes text (e/current-repo) (:auto-ids @m/session))))))

(defn- node-value
  "A child node as a value of its own: an id read in repo, or a node held
   inline (a repeat, its body) put into repo under its id."
  [repo base k]
  (if (keyword? k)
    (assoc (v/->Material k repo) :base base)
    (assoc (v/->Material (:id k) (assoc repo (:id k) k)) :base base)))

(declare repetition? repeat-parts endings-of)

(defn elements
  "The elements of a value: a container's children without its
   instructions (leaves as they are, nested containers and repeats as
   values of their own), a data container's values, a stream's items. A
   repeat's are what it plays, pass after pass with its endings; an
   endless repeat's an endless stream."
  [x]
  (cond
    (repetition? x) (let [[_ n body alt] (repeat-parts x)
                          b (elements body)]
                      (if (= n :infinite)
                        (with-meta (cycle b) {:mu/endless true})
                        (let [ends (endings-of alt) k (count ends)]
                          (vec (mapcat #(concat b (when (pos? k) (elements (nth ends (d/ending-for k n %)))))
                                       (range n))))))
    (v/material? x) (let [{:keys [repo base]} x
                          data? (= :DATA (:type (v/node x)))]
                      (mapv (fn [k] (if (and (not data?) (or (keyword? k) (d/iterator? k))) (node-value repo base k) k))
                            (remove instruction? (:children (v/node x)))))
    (leaf? x)       [x]
    (sequential? x) x
    :else (e/mu-error "has no elements:" (p/show x))))

(defn- leading-instructions
  "The instructions written before a container's first element: its
   settings from the start, kept when the elements are reshaped."
  [x]
  (when (v/material? x)
    (map :raw (take-while instruction? (:children (v/node x))))))

(defn rebuild
  "A value of the same form as like (a line, a parallel, data), holding
   elements, with like's leading instructions."
  [like elements]
  (let [shown (str/join " " (concat (leading-instructions like) (map p/show elements)))]
    (read-text
     (case (kind like)
       :PAR  (str "{" shown "}")
       :DATA (str "'[" (str/join " " (map p/show elements)) "]")
       (str "[" shown "]")))))

(defn line
  "elements as a line (a :SEQ)."
  [elements]
  (read-text (str "[" (str/join " " (map p/show elements)) "]")))

(defn hairpin
  "x with a crescendo (dir \"<\") or decrescendo (\">\") across it, from its
   volume at the start (its own, else the defaults') to target (0-100):
   inside a line, after its leading instructions, as [!vol:60< … !vol:90]
   would be written; anything else is wrapped in a line first."
  [x dir target]
  (let [mat   (if (leaf? x) (line [x]) x)
        node  (v/node mat)
        start (or (c/ctx-value-chain (keep :context [node (repo/current :ROOT)]) :volume 0) 50)
        open  (str "!vol:" start dir)
        close (str "!vol:" target)]
    (read-text
     (if (= :SEQ (kind mat))
       (str "[" (str/join " " (concat (leading-instructions mat) [open] (map p/show (elements mat)) [close])) "]")
       (str "[" open " " (p/show mat) " " close "]")))))

(defn ->material
  "x as a Material, without storing it: a leaf or a finite stream of
   musical elements is read as a line."
  [x]
  (cond
    (v/material? x) x
    (leaf? x)       (line [x])
    (sequential? x) (if (:mu/endless (meta x))
                      (e/mu-error "an endless stream can't become material -- take some first")
                      (line x))
    :else (e/mu-error "not material:" (p/show x))))

(defn repetition? [x] (and (v/material? x) (d/iterator? (v/node x))))

(defn as-line
  "x as a line: itself if it is one, else a line holding it (a shuffle
   [ … ] too, whose order is its own)."
  [x]
  (let [m (->material x)] (if (and (= :SEQ (kind m)) (not (:shuffle (v/node m))) (not (:rotate (v/node m))) (not (:choose (v/node m))) (not (:alternate (v/node m)))) m (line [x]))))

(defn- read-repeat
  "The repeat written as text (repeat 2 [ … ] …, repeat tremolo 2 [ … ],
   or [: … :] when bars), as a value; type is nil or \"tremolo\"."
  ([type n body alt] (read-repeat type n body alt false))
  ([type n body alt bars]
   (let [b (p/show body)]
     (first (elements (read-text (str "[" (if bars
                                            (str "[: " (subs b 1 (dec (count b))) " :]")
                                            (str "repeat " (when type (str type " ")) (if (= n :infinite) "~" n) " " b))
                                      (when alt (str " volta " (p/show alt))) "]")))))))

(defn repeat-parts
  "A repeat's type, count, body and alternative, the last two as values."
  [rep]
  (let [{:keys [repo base]} rep
        it (v/node rep)
        {n :count alt :alternative bars :bars} (:params it)]
    [(when (= :TREMOLO (:type it)) "tremolo") n
     (node-value repo base (:source it)) (some->> alt (node-value repo base)) bars]))

(defn repetition
  "x played n times, as the text's repeat n [ x ]: one repeat,
   with endings (lines) as its alternative -- one ending as itself,
   several as volta [[e1] [e2]], LilyPond's grouping."
  ([x n endings] (repetition x n endings false))
  ([x n endings bars]
   (read-repeat nil n (as-line x)
                (case (count endings) 0 nil 1 (first endings) (line endings))
                bars)))

(defn- endings-of
  "A repeat's endings as values, grouped as d/volta-endings groups them."
  [alt]
  (cond (nil? alt) []
        (every? #(= :SEQ (kind %)) (elements alt)) (vec (elements alt))
        :else [alt]))

(defn repeat-count [rep] (second (repeat-parts rep)))

(defn repeated
  "body's elements n times (:infinite: endlessly) as a stream that
   remembers body, so it prints as body n times (body [~])."
  [body n]
  (let [els (vec (elements body))]
    (if (= n :infinite)
      (with-meta (cycle els) {:mu/endless true :mu/repeat [body n]})
      (with-meta (apply concat (repeat n els)) {:mu/repeat [body n]}))))

(defn repeat-of
  "[body n] when x is a repeat or loop of music without endings, or a
   stream made by repeated; else nil."
  [x]
  (cond
    (and (repetition? x) (nil? (nth (repeat-parts x) 3))) (let [[_ n b] (repeat-parts x)] [b n])
    (sequential? x) (:mu/repeat (meta x))))

(defn loop?
  "True when x is a loop: of music, or a stream made by repeated."
  [x]
  (= :infinite (second (repeat-of x))))

(defn like-repeat
  "x repeated as rep is: a repeat of music keeps its kind (tremolo,
   [: :]) and count, a repeated stream its count."
  [rep x]
  (if (repetition? rep)
    (let [[t n _ _ bars] (repeat-parts rep)] (read-repeat t n (as-line x) nil bars))
    (repeated x (second (repeat-of rep)))))

(defn played-leaves
  "x's leaves in the order they play: a repeat inside written out pass
   after pass, with its endings; a loop inside refused."
  [x]
  (cond
    (leaf? x) [x]
    (and (map? x) (or (d/bar? x) (d/jump? x))) nil
    (and (v/material? x) (= :DATA (kind x))) nil
    (loop? x) (e/mu-error "a loop inside a line has no end -- read the loop on its own")
    (or (v/material? x) (sequential? x)) (mapcat played-leaves (elements x))))

(defn with-endings
  "rep, a repeat with no endings yet, with endings (lines) after its
   passes."
  [rep endings]
  (let [[t n body alt bars] (repeat-parts rep)]
    (when (= t "tremolo") (e/mu-error "volta: a tremolo takes no endings"))
    (when alt (e/mu-error "volta: the repeat has its endings already"))
    (when (= n :infinite) (e/mu-error "volta: an endless repeat has no endings"))
    (repetition body n endings bars)))

(defn passes
  "rep written out as a line of its passes: pass i is (f i body), body
   as written, each followed by its volta ending as a volta repeat plays
   them (d/volta-endings' rule, d/ending-for)."
  [rep f]
  (let [[_ n body alt] (repeat-parts rep)
        _ (when (= n :infinite) (e/mu-error "vary: an endless repeat can't be written out"))
        endings (endings-of alt)
        k (count endings)]
    (line (mapcat (fn [i] (cons (as-line (f i body))
                                (when (pos? k) [(as-line (nth endings (d/ending-for k n i)))])))
                  (range n)))))

(defn map-leaves
  "x with f applied to every leaf, at any depth, keeping its form; a bar
   line, no leaf, stays as it is."
  [f x]
  (cond
    (leaf? x)       (f x)
    (or (d/bar? x) (d/jump? x)) x
    (repetition? x) (let [[t n body alt bars] (repeat-parts x)]
                      (read-repeat t n (map-leaves f body) (some->> alt (map-leaves f)) bars))
    (v/material? x) (if (= :DATA (kind x))
                      x
                      (rebuild x (map #(map-leaves f %) (elements x))))
    (sequential? x) (map #(map-leaves f %) x)
    :else (e/mu-error "not material:" (p/show x))))

;; -- the repo -------------------------------------------------------------

(defn store!
  "Store x in the repo under name if given, else its own id or an auto
   one; returns the id."
  [x name]
  (if (leaf? x)
    (first (:ids (m/parse (str "[" (when name (str (clojure.core/name name) ": ")) (p/show x) "]"))))
    (let [mat  (->material (if (repetition? x) (line [x]) x)) ; as parse stores a bare repeat
          id   (or name (:id mat))
          top  (assoc (v/node mat) :id id)
          root (repo/current :ROOT)]
      (repo/commit-many! (-> (v/own-nodes mat) (dissoc (:id mat)) (assoc id top)))
      (when (and (not= :CONTEXT (:type top)) (not (some #{id} (:children root))))
        (repo/commit-node! :ROOT (update root :children (fnil conj []) id)))
      (reg/log! :parse)
      id)))

(defn fetch
  "The value stored as id."
  [id]
  (when-not (repo/current id) (e/mu-error "R@: nothing is stored as" (pr-str id)))
  (let [now (into {} @(repo/registry))]
    (assoc (v/->Material id now) :base now)))

(def ^:private voices
  "Voice id -> {:x :ctx :ends}: what each voice mu started plays (as
   given) and the section ids whose :exit ends a pass of it -- its own
   id, and a repeat's body, which exits every pass."
  (atom {}))

(def ^:private staged
  "target -> {:keys [[k phase] ...] :action id}: a >> waiting for its
   boundary, per voice name (or ::all for one replacing everything)."
  (atom {}))

(defn cancel-staged!
  "Forget a >> still waiting for its boundary: one target's, or all."
  ([] (doseq [t (keys @staged)] (cancel-staged! t)))
  ([target]
   (when-let [{:keys [keys action]} (get @staged target)]
     (doseq [[k phase] keys] (conductor/unschedule! k phase))
     (conductor/unregister-action! action)
     (swap! staged dissoc target))))

(defn- ends-of [mat]
  (let [node (v/node mat)]
    (cond-> #{(:id mat)} (d/iterator? node) (conj (:id (:source node))))))

(defn voice-name
  "The voice a value plays as: its own name ([bass: c d] is :bass), a
   repeat's or loop's that of the line it repeats ([bass: c d] [~]); nil
   when it has none -- then the engine gives it an id (:TAA …)."
  [mat]
  (let [named #(let [id (:id %)] (when (and (keyword? id) (not (p/auto-id? id))) id))]
    (or (named mat)
        (when (repetition? mat)
          (let [body (nth (repeat-parts mat) 2)]
            (or (named body)
                ;; a repeat holds its music in a line of its own (as-line)
                (let [[x & more] (elements body)] (when (and x (not more) (v/material? x)) (named x)))))))))

(defn- prepared
  "[engine form mat] for playing x, under a context if given, without
   storing either: the engine reads a repo of what is stored plus their
   own nodes."
  [x ctx]
  (let [mat  (->material x)
        _    (when (nil? @m/receiver) (m/connect))
        snap (merge (into {} @(repo/registry)) (v/own-nodes mat) (when ctx (v/own-nodes ctx)))]
    [(assoc engine/*engine* :repo (atom snap)) (if ctx [(:id ctx) (:id mat)] (:id mat)) mat]))

(defonce ^{:doc "What play! played last (as given: a leaf, a value), for rating it."}
  last-played (atom nil))

(defonce ^{:doc "{key (fn [])} run by stop-all!, so a word that keeps
   something playing (an audition) stops with what plays."}
  stoppers (atom {}))

(defn live-voices
  "Voice id -> {:x :ctx :ends} of the voices mu started that still play."
  []
  (into {} (filter (fn [[id _]] (engine/voice-at id))) @voices))

(defn mean-pitch
  "x's mean pitch (a note's, or a line's as written), nil without pitch."
  [x]
  (d/mean-pitch (if (leaf? x) x (v/node (->material x)))))

(defn roles
  "role -> voice id among what mu plays, by mean pitch: the highest
   :soprano, the lowest :bass, the next highest :alto and the next
   lowest :tenor -- as there are voices for them (two: soprano and bass;
   three: and alto). A voice without pitch (drums) has none."
  []
  (let [ranked (->> (live-voices)
                    (keep (fn [[id {:keys [x]}]] (when-let [m (mean-pitch x)] [id m])))
                    (sort-by (fn [[id m]] [(- m) (str id)]))
                    (mapv first))
        n      (count ranked)]
    (cond-> {}
      (pos? n)  (assoc :soprano (ranked 0))
      (> n 1)   (assoc :bass (ranked (dec n)))
      (> n 2)   (assoc :alto (ranked 1))
      (> n 3)   (assoc :tenor (ranked (- n 2))))))

(defn- keep! [id x ctx mat]
  (when (keyword? id) (swap! voices assoc id {:x x :ctx ctx :ends (ends-of mat)}))
  id)

(defn play!
  "Play x, under a context if given, without storing either; its voice
   id back. Played as a voice -- target, else its own name ([bass: …]) --
   it replaces only that voice, or adds it. Otherwise everything playing
   stops first, or with join? true x joins it under an id of its own."
  [x ctx & [join? target]]
  (reset! last-played x)
  (let [[eng form mat] (prepared x ctx)
        name (or target (voice-name mat))]
    (reg/log! :play)
    (binding [engine/*engine* eng]
      (cond
        name  (do (cancel-staged! name) (engine/play-at! eng [name] form) (keep! name x ctx mat))
        join? (keep! (engine/play-add form) x ctx mat)
        :else (do (cancel-staged!) (reset! voices {}) (keep! (engine/play form) x ctx mat))))))

(defn stop-all!
  "Stop what plays, a staged >> and whatever stoppers hold."
  []
  (cancel-staged!)
  (reset! voices {})
  (doseq [f (vals @stoppers)] (f))
  (when engine/*engine* (m/stop!)))

(defn stop-voice!
  "Stop only the voice id."
  [id]
  (when-not (engine/voice-at id) (e/mu-error "stop:" (pr-str id) "isn't playing -- voices lists what plays"))
  (cancel-staged! id)
  (swap! voices dissoc id)
  (engine/stop-voice! id))

(defn playing
  "What the voice id plays, as it was given."
  [id]
  (or (:x (get (live-voices) id))
      (e/mu-error "playing:" (pr-str id) "isn't a voice mu plays -- voices lists them")))

(defn stage!
  "Play x from the next boundary: with count (1-4) the next bar line of
   that strength (| … ||||), else the end of a pass -- of its own voice
   when named music replaces it, else of everything mu plays -- through
   the conductor, starting exactly when the boundary is due. Named
   music replaces only its voice; otherwise x replaces everything, or
   with join? true joins it. Plays at once when nothing plays."
  [x ctx count & [join? target]]
  (let [live (live-voices)]
    (if-not (engine/busy?)
      (play! x ctx join? target)
      (let [[eng form mat] (prepared x ctx)
            name   (or target (voice-name mat))
            ends   (if-let [v (and name (get live name))] (:ends v) (into #{} (mapcat :ends) (vals live)))
            ks     (if (or count (empty? ends)) [[[:mark (or count 1)] :enter]] (mapv (fn [id] [id :exit]) ends))
            target (or name (if join? (keyword "mu" (str (gensym "join"))) ::all))
            action (keyword "mu" (str (gensym "staged")))]
        (cancel-staged! target)
        (conductor/register-action!
         action
         (fn [event]
           (cancel-staged! target)
           (let [at (or (:at event) (System/nanoTime))]
             (binding [engine/*engine* eng]
               (if name
                 (do (engine/play-at! eng [name] form at) (keep! name x ctx mat))
                 (let [id (engine/play-from! eng at form join?)]
                   (when-not join? (reset! voices {}))
                   (keep! id x ctx mat)))))))
        (doseq [[k phase] ks] (conductor/schedule! k phase action))
        (swap! staged assoc target {:keys ks :action action})
        (reg/log! :play)))))

;; -- written out, barred ----------------------------------------------------

(defn- children-of
  "A container's children as values, instructions included (unlike
   elements), in written order."
  [x]
  (let [{:keys [repo base]} x]
    (mapv (fn [k] (if (or (keyword? k) (d/iterator? k)) (node-value repo base k) k))
          (:children (v/node x)))))

(defn- show-child [c] (if (instruction? c) (:raw c) (p/show c)))

(defn- line-of [children] (read-text (str "[" (str/join " " (map show-child children)) "]")))

(defn written-out
  "x as it plays, written out: a repeat as a line of its passes (each
   followed by its ending), a line's jumps ($ DS DC Fine Coda) and ./.
   followed and dropped, at every depth. An endless repeat can't be."
  [x]
  (cond
    (repetition? x) (do (when (= :infinite (second (repeat-parts x)))
                          (e/mu-error "[!]: an endless repeat can't be written out"))
                        (passes x (fn [_ body] (written-out body))))
    (= :SEQ (kind x))
    (let [cs (children-of x)]
      (line-of (for [[a b] (d/play-segments cs), c (subvec cs a b) :when (not (d/jump? c))]
                 (if (v/material? c) (written-out c) c))))
    (= :PAR (kind x)) (rebuild x (map written-out (elements x)))
    :else x))

(defn- note-values
  "d as note values, longest first (1/4 + 1/16 for 5/16): powers of two."
  [d]
  (loop [d d out []]
    (if (zero? d)
      out
      (let [v (/ 1 (Long/highestOneBit (long (Math/ceil (/ 1 d)))))
            v (if (> v d) (/ v 2) v)]
        (recur (- d v) (conj out v))))))

(defn- length-of [c]
  (cond (v/material? c) (d/duration (:repo c) (v/node c))
        (map? c)        (or (:duration c) 0)
        :else           0))

(defn- splittable? [c]
  (and (map? c) (#{:LEAF :REST :DRUM} (:type c)) (nil? (:ratio c))
       (let [r (rationalize (:duration c))] (or (integer? r) (= 1 (Long/bitCount (denominator r)))))))

(defn- pieces
  "The first d of leaf c, as note values summing to d, each tied to
   what follows (a rest isn't tied; a tied drum is struck once)."
  [c d]
  (map (fn [v] (cond-> (assoc c :duration v :ratio nil) (#{:LEAF :DRUM} (:type c)) (assoc :tied true)))
       (note-values d)))

(defn- barred-children
  "x's elements with a bar line (|) at every bar of its meter (its own
   !Meter:, else the defaults'), its single bar lines replaced; a note,
   drum or rest that crosses one is cut there and tied. A nested part or
   a tuplet note that crosses one keeps whole, and that bar line is left
   out."
  [x]
  (let [mat   (as-line x)
        meter (or (c/ctx-value-chain (keep :context [(v/node mat) (repo/current :ROOT)]) :Meter 0)
                  {:num 4 :den 4})
        bar   (/ (:num meter) (:den meter))]
    (loop [cs (remove #(and (d/bar? %) (= 1 (:count %))) (children-of mat)) pos 0 out []]
      (if-let [[c & more] (seq cs)]
        (let [len (length-of c)
              ;; a bar line at the boundary: before the next note, and
              ;; before a setting written there, so it opens its measure
              at? (and (= pos bar) (or (pos? len) (some #(pos? (length-of %)) more)))
              out (if at? (conj out (d/bar 1)) out)
              pos (if at? 0 pos)
              room (- bar pos)]
          (cond
            (<= len room)        (recur more (+ pos len) (conj out c))
            (splittable? c)      (let [head (pieces c room)
                                       tail (assoc c :duration (- len room))]
                                   (recur (cons tail more) bar (into out head)))
            :else                (recur more (let [p (mod (+ pos len) bar)] (if (zero? p) bar p)) (conj out c))))
        out))))

(defn barred
  "x as a line with a bar line (|) at every bar of its meter -- see
   barred-children."
  [x]
  (line-of (barred-children x)))

(defn- bar-line? [c] (and (d/bar? c) (= 1 (:count c))))

(defn measures
  "x as a line of its measures, each a line of its own: barred by its
   meter as barred does, then cut at those bar lines. A setting written
   before a measure's first note goes in the outer line, in front of it;
   one written inside a measure stays there and is written again in the
   outer line after it, so either way it carries on into the measures
   that follow, as it did in the line. A ramp (!vol<) inside a measure
   stays in it only."
  [x]
  (let [groups (remove #(every? bar-line? %) (partition-by bar-line? (barred-children x)))
        ramp?  #(re-find #"[<>]" (str (:raw %)))]
    (line-of (mapcat (fn [g]
                       (let [[lead body] (split-with instruction? g)]
                         (concat lead
                                 (when (seq body) [(line-of body)])
                                 (filter #(and (instruction? %) (not (ramp? %))) body))))
                     groups))))
