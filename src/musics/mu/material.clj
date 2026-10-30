(ns musics.mu.material
  "Working with mu's musical values: their elements, building a value
   from elements (by printing them and reading the text back, which
   printing's isomorphism makes safe), storing, fetching and playing.
   Shared by the kernel and the tools."
  (:require [clojure.string :as str]
            [musics.domain :as d]
            [musics.conductor :as conductor]
            [musics.domain.context :as c]
            [musics.engine :as engine]
            [musics.events :as ev]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.grammar :as g]
            [musics.mu.print :as p]
            [musics.common.music-elements :as el]
            [musics.mu.value :as v]
            [musics.input.reader.leaf-parser :as lp]
            [musics.common.music-data :as data]
            [musics.common.context-keys :as ck]
            [musics.common.music-tools :as mtools]
            [musics.domain.ornaments :as orn]
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
    (first (:values (v/read-literals nodes text (e/current-repo) (:auto-ids @reg/session))))))

(defn- node-value
  "A child node as a value of its own: an id read in repo, or a node held
   inline (a repeat, its body) put into repo under its id."
  [repo base k]
  (if (keyword? k)
    (assoc (v/->Material k repo) :base base)
    (assoc (v/->Material (:id k) (assoc repo (:id k) k)) :base base)))

(declare repetition? repeat-parts endings-of repeated)

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
                      (mapv (fn [k] (cond (and (not data?) (or (keyword? k) (d/iterator? k))) (node-value repo base k)
                                          (= :LOOP (:type k)) (repeated (apply list (:items k)) :infinite) ; '[60 62] [~]
                                          :else k))
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

(defn bundle-columns
  "[[part column] …] of a bundle -- data written as columns, a :part
   before each stream, '[:pitch '[60 62] :volume '[90 70]], what zip
   gives without what a note needs -- else nil."
  [x]
  (when (and (v/material? x) (= :DATA (kind x)))
    (let [els (elements x)]
      (when (and (seq els) (even? (count els))
                 (every? keyword? (take-nth 2 els))
                 (every? sequential? (take-nth 2 (rest els))))
        (mapv vector (take-nth 2 els) (take-nth 2 (rest els)))))))

(defn sounding!
  "x, refused when it is a bundle: it can't sound until zip gives it
   durations and pitches (or drums)."
  [word x]
  (when-let [cols (bundle-columns x)]
    (let [has (set (map (comp #({:durations :duration :pitches :pitch :drums :drum} % %) first) cols))]
      (e/mu-error (str word ":") "a bundle of parts can't sound -- it has no"
                  (if (has :duration) "pitches or drums" "durations") "yet: zip it with some")))
  x)

(defn repetition? [x] (and (v/material? x) (d/iterator? (v/node x))))

(defn as-line
  "x as a line: itself if it is one, else a line holding it (a shuffle
   [ … ] too, whose order is its own)."
  [x]
  (let [m (->material x)]
    (if (and (= :SEQ (kind m)) (not-any? (v/node m) [:shuffle :rotate :choose :alternate])) m (line [x]))))

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
  (let [els  (vec (elements body))
        part (select-keys (meta body) [:mu/part])]
    (if (= n :infinite)
      (with-meta (cycle els) (merge part {:mu/endless true :mu/repeat [body n]}))
      (with-meta (apply concat (repeat n els)) (merge part {:mu/repeat [body n]})))))

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

(defn map-nodes
  "x with f applied to every leaf and g to every instruction, at any
   depth, keeping its form: nesting, repeats, instructions, a shuffle's
   or rotate's mark. The value's nodes are changed and read back as
   text, so what is baked on them (lengths, pitch sums) is computed
   anew; a bar line, no leaf, stays as it is."
  [f g x]
  (cond
    (leaf? x)       (f x)
    (or (d/bar? x) (d/jump? x)) x
    (v/material? x) (if (= :DATA (kind x))
                      x
                      (let [repo (volatile! (:repo x))
                            node (fn node [n]
                                   (cond
                                     (keyword? n)        (do (when-let [m (get @repo n)] (vswap! repo assoc n (node m))) n)
                                     (leaf? n)           (f n)
                                     (instruction? n)    (g n)
                                     (d/iterator? n)     (cond-> (update n :source node)
                                                           (get-in n [:params :alternative]) (update-in [:params :alternative] node))
                                     (= :DATA (:type n)) n
                                     (:children n)       (update n :children #(mapv node %))
                                     :else               n))
                            top  (node (v/node x))]
                        (read-text (p/show (v/->Material (:id x) (assoc @repo (:id x) top))))))
    (sequential? x) (map #(map-nodes f g %) x)
    :else (e/mu-error "not material:" (p/show x))))

(defn map-leaves
  "x with f applied to every leaf, at any depth, keeping its form (see
   map-nodes)."
  [f x]
  (map-nodes f identity x))

(defn- with-key
  "x with a !K: at the start of its line (a repeat's body, a { }), the
   key it is in there -- its own, else its context's (the defaults') --
   unless one is written there already."
  [x]
  (let [put (fn [n]
              (if (some #(and (instruction? %) (= :Key (:key %))) (take-while instruction? (:children n)))
                n
                (let [k (c/ctx-value-chain (keep :context [n (repo/current :ROOT)]) :Key 0)]
                  (update n :children #(into [{:type :assignment :key :Key :val (el/key->str k) :raw (str "!K:" (el/key->str k))}] %)))))
        {:keys [repo id]} x
        n (get repo id)]
    (cond
      (#{:SEQ :PAR} (:type n)) (assoc-in x [:repo id] (put n))
      (d/iterator? n)          (let [src (:source n)]
                                 (if (keyword? src)
                                   (update-in x [:repo src] put)
                                   (assoc-in x [:repo id :source] (put src))))
      :else x)))

(defn transposed
  "x moved by n semitones, its key with it: every note, a chord's root,
   the key each note was written in, every !K: and -- at the start of
   its line -- the key it is in (not for whole octaves, the same key),
   so its notes stay degrees of their key wherever it plays. A drum
   stays."
  [x n]
  (let [shift (fn [k] (el/transpose-key k n))]
    (map-nodes (fn [l]
                 (if (and (= :LEAF (:type l)) (seq (:pitches l)))
                   (cond-> (update l :pitches #(mapv (partial + n) %))
                     (:key l)      (update :key shift)
                     (:key-root l) (update :key-root + n))
                   l))
               (fn [i]
                 (if-let [k (and (= :Key (:key i)) (el/parse-key (str (:val i))))]
                   (let [k' (el/key->str (shift k))] (assoc i :val k' :raw (str "!K:" k')))
                   i))
               (if (and (v/material? x) (not (zero? (mod n 12)))) (with-key x) x))))

;; -- the repo -------------------------------------------------------------

(defn- recipe-id
  "The next free :r<n> id for a recipe that names none."
  []
  (loop []
    (let [n  (get (swap! reg/session update-in [:auto-ids :RECIPE] (fnil inc 0)) :auto-ids)
          id (keyword (str "r" (:RECIPE n)))]
      (if (repo/current id) (recur) id))))

(defn store-all!
  "Store each [x name] of pairs at once, as store! does one; returns the
   ids, in order. Warns (without refusing) when an id it redefines is
   also used by a part it doesn't store."
  [pairs]
  (let [parts (for [[x name] pairs]
                (if (v/recipe? x)
                  ;; a recipe is stored as its text, rebuilt when read (musics.recipe/stored)
                  (let [id (or name (:id x) (recipe-id))]
                    {:id id :edits {id {:type :RECIPE :id id :text (:source x)}}})
                  (let [mat (->material (if (repetition? x) (line [x]) x)) ; a bare repeat is stored in a line
                        id  (or name (:id mat))
                        top (assoc (v/node mat) :id id)]
                    {:id id :top top :edits (-> (v/own-nodes mat) (dissoc (:id mat)) (assoc id top))})))
        parts (doall parts)
        edits (apply merge (map :edits parts))
        ours  (conj (set (keys edits)) :ROOT)]
    (doseq [k (keys edits)
            :let [affected (remove ours (repo/usages k))]
            :when (and (repo/current k) (seq affected))]
      (println "[musics] Redefining" k "also affects" (vec affected)
               "-- give it a new id instead if that's not intended."))
    (repo/commit-many! edits)
    (let [root (or (repo/current :ROOT) (get (:repo (builder/empty-session)) :ROOT))
          new  (for [{:keys [id top]} parts
                     :when (and top (not= :CONTEXT (:type top)) (not (some #{id} (:children root))))]
                 id)]
      (when (seq new)
        (repo/commit-node! :ROOT (update root :children (fnil into []) (distinct new)))))
    (reg/log! :parse)
    (mapv :id parts)))

(defn store!
  "Store x in the repo under name if given, else its own id or an auto
   one; returns the id (see store-all!)."
  [x name]
  (first (store-all! [[x name]])))

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
   when it has none -- then the engine gives it an id (:TA …)."
  [mat]
  (let [named #(let [id (:id %)] (when (and (keyword? id) (not (p/auto-id? id))) id))]
    (or (named mat)
        (when (repetition? mat)
          (let [body (nth (repeat-parts mat) 2)]
            (or (named body)
                ;; a repeat holds its music in a line of its own (as-line)
                (let [[x & more] (elements body)] (when (and x (not more) (v/material? x)) (named x)))))))))

(defn events
  "What playing x would perform, as musics.events gives it: a lazy seq
   of timed events, read against what is stored plus x's own nodes."
  [x]
  (let [mat (->material x)]
    (ev/events (merge (into {} @(repo/registry)) (v/own-nodes mat)) (:id mat))))

(defn- prepared
  "[engine form mat] for playing x, under a context if given, without
   storing either: the engine reads a repo of what is stored plus their
   own nodes."
  [x ctx]
  (let [mat  (->material (sounding! "play" x))
        _    (engine/ensure-connected!)
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

(defn leaves
  "x's leaves as written, at any depth -- a repeat's body and endings
   once, a loop's once -- without rebuilding anything: for looking at
   them, not changing them (map-leaves)."
  [x]
  (letfn [(walk [repo n]
            (cond
              (keyword? n)      (walk repo (get repo n))
              (leaf? n)         [n]
              (d/iterator? n)   (concat (walk repo (:source n)) (some->> (get-in n [:params :alternative]) (walk repo)))
              (= :DATA (:type n)) nil
              (map? n)          (mapcat #(walk repo %) (:children n))
              :else             nil))]
    (cond
      (leaf? x)       [x]
      (v/material? x) (walk (:repo x) (v/node x))
      (sequential? x) (mapcat leaves (if-let [[body] (:mu/repeat (meta x))] [body] x))
      :else           nil)))

(defn mean-pitch
  "x's mean pitch (a note's, a line's, a repeat's or loop's as written),
   nil without pitch."
  [x]
  (let [ps (mapcat :pitches (leaves x))]
    (when (seq ps) (/ (reduce + ps) (count ps)))))

(defn- lowest-drum
  "The lowest drum key x plays, nil when it plays no drum."
  [x]
  (some->> (seq (for [l (leaves x) :when (= :DRUM (:type l))
                      ;; no key plays 35, as musics.domain.resolve plays it
                      k (let [k (:program l)] (if (vector? k) k [k]))]
                  (or k 35)))
           (apply min)))

(defn ranked
  "[pitched drums]: the voice ids mu plays with pitch, lowest mean pitch
   first -- index 1..n -- and those without (drums), lowest drum key
   first -- index 0, -1, -2 …"
  []
  (let [ms (map (fn [[id {:keys [x]}]] [id (mean-pitch x) x]) (live-voices))]
    [(mapv first (sort-by (fn [[id m]] [m (str id)]) (filter second ms)))
     (mapv first (sort-by (fn [[id _ x]] [(or (lowest-drum x) 128) (str id)]) (remove second ms)))]))

(defn voice-id
  "A voice's id from what addresses it: its id, or its index -- 1 the
   lowest voice with pitch, n the highest; 0 the drum voice with the
   lowest drum key, -1 the next, …"
  [word x]
  (if (integer? x)
    (let [[pitched drums] (ranked)]
      (if (pos? x)
        (or (get pitched (dec x))
            (e/mu-error (str word ":") "no voice" x "--" (count pitched) "with pitch play, 1 the lowest; voices lists them"))
        (or (get drums (- x))
            (e/mu-error (str word ":") "no drum voice" x "--"
                        (if (seq drums) (str (count drums) " play, 0 to " (- 1 (count drums))) "none plays")))))
    x))

(defn- keep! [id x ctx mat]
  (when (keyword? id) (swap! voices assoc id {:x x :ctx ctx :ends (ends-of mat)}))
  id)

(defn play!
  "Play x, under a context if given, without storing either; its voice
   id back. Played as a voice -- target, else its own name ([bass: …]) --
   it replaces only that voice, or adds it. Otherwise everything playing
   stops first, or with join? true x joins it under an id of its own.
   With algo, a live algo's name (musics.mu.live), it plays through it."
  [x ctx & [join? target algo]]
  (reset! last-played x)
  (let [[eng form mat] (prepared x ctx)
        name (or target (voice-name mat))]
    (reg/log! :play)
    (binding [engine/*engine* eng]
      (cond
        name  (do (cancel-staged! name) (engine/play-at! eng [name] form nil algo) (keep! name x ctx mat))
        join? (keep! (if algo (engine/play-add form :algo algo) (engine/play-add form)) x ctx mat)
        :else (do (cancel-staged!) (reset! voices {})
                  (keep! (if algo (engine/play form :algo algo) (engine/play form)) x ctx mat))))))

(defn stop-all!
  "Stop what plays, a staged >> and whatever stoppers hold."
  []
  (cancel-staged!)
  (reset! voices {})
  (doseq [f (vals @stoppers)] (f))
  (when engine/*engine* (engine/stop!)))

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
   with join? true joins it. Plays at once when nothing plays, or when
   no boundary is known: no count, and none of what plays started by mu
   (an audition, a voice from Clojure)."
  [x ctx count & [join? target algo]]
  (let [live (live-voices)]
    (if (or (not (engine/busy?)) (and (nil? count) (empty? live)))
      (play! x ctx join? target algo)
      (let [[eng form mat] (prepared x ctx)
            name   (or target (voice-name mat))
            ends   (if-let [v (and name (get live name))] (:ends v) (into #{} (mapcat :ends) (vals live)))
            ks     (if count [[[:mark count] :enter]] (mapv (fn [id] [id :exit]) ends))
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
                 (do (engine/play-at! eng [name] form at algo) (keep! name x ctx mat))
                 (let [id (engine/play-from! eng at form join? algo)]
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

;; -- what the words share ---------------------------------------------------

(defn endless [xs] (with-meta xs {:mu/endless true}))

(defn endless? [x] (and (sequential? x) (:mu/endless (meta x))))

(defn as-part
  "Stream xs marked as the part of a leaf it holds (:pitch, :duration,
   :volume …), so zip knows it without a name."
  [part xs]
  (if (instance? clojure.lang.IObj xs) (vary-meta xs assoc :mu/part part) xs))

(defn streamed
  "x, or an endless repeat (a loop) as the endless stream of what it
   plays, so the tools take from it as from cycle's."
  [x]
  (if (and (repetition? x) (= :infinite (repeat-count x))) (elements x) x))

(defn finite!
  "xs, refused if it is an endless stream: word needs all of it."
  [word xs]
  (when (endless? xs) (e/mu-error (str word ":") "needs all of a stream, and this one is endless -- take some first"))
  xs)

(defn reshape
  "f on x's elements, giving a value of x's form: rebuilt for a value,
   a stream for a stream."
  [x f]
  (let [x   (streamed x)
        els (f (elements x))]
    (cond
      (or (v/material? x) (leaf? x)) (rebuild (if (leaf? x) (line [x]) x) els)
      (endless? x) (endless els)
      :else els)))

(defn music? [x] (or (leaf? x) (and (v/material? x) (not= :DATA (kind x)))))

(defn result
  "What quotation q leaves for x: exactly one value."
  [word q x]
  (let [out (e/call [x] q)]
    (when-not (= 1 (count out))
      (e/mu-error (str word ":") "the quotation must leave one value for each element, left" (count out)))
    (peek out)))

(defn key? [x] (instance? musics.common.music_elements.Key x))

(defn key-line
  "Pitches as a line of quarter notes, spelled in ks."
  [ks pitches]
  (read-text (str "[" (str/join " " (map #(str (lp/spell-pitch % nil ks) "4") pitches)) "]")))

(defn par-of [xs]
  (read-text (str "{" (apply str (interpose " " (map #(p/show (->material (sounding! "par" %))) xs))) "}")))

(defn marker? [x] (or (d/bar? x) (d/jump? x)))

;; -- what play shares with the prompt ----------------------------------------

(defn toggle-pause!
  "Pause what plays, or resume it if paused. Says which: \"paused\",
   \"resumed\" or \"nothing plays\"."
  []
  (let [eng engine/*engine*]
    (cond
      (and eng (engine/paused? eng)) (do (engine/resume!) "resumed")
      (engine/busy? eng)             (do (engine/pause!) "paused")
      :else                          "nothing plays")))

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

(defonce echo-agent (agent nil :error-mode :continue))

;; -- leaves from their parts: zip ---------------------------------------------

(def articulation-order
  [:staccatissimo :stopped :staccato :marcato :portato :accent :tenuto :legato :sfz :fermata :ghost])

(defn articulate
  "Note l with articulation a (a name): its length and its weight, as
   c4-> sets them; a drum takes the weight."
  [word l a]
  (if-let [{:keys [duration dynamic]} (data/articulations (keyword a))]
    (cond-> (assoc l :dynamic dynamic) (and duration (not= :DRUM (:type l))) (assoc :articulation duration))
    (e/mu-error (str word ":") (pr-str a) "is not an articulation --" (str/join " " (map name articulation-order)))))

(defn set-instrument
  "Note l with its own instrument i: a program, a General MIDI name or
   short name. A drum has none."
  [word l i]
  (when (= :DRUM (:type l))
    (e/mu-error (str word ":") "a drum has no instrument, its sound is a key of the kit -- zip a :drum stream onto it"))
  (assoc-in l [:overrides :instrument]
            (or (when (number? i) (int i)) (data/program-of i)
                (e/mu-error (str word ":") (pr-str i) "is not a program or General MIDI name"))))

(defn volume-value
  "v as a note's own volume: a number 0..100 (resolve maps it to MIDI)."
  [word v]
  (if (and (number? v) (<= 0 v 100))
    v
    (e/mu-error (str word ":") "a volume is 0..100, got" (p/show v))))

(defn drum-key [word i]
  (or (when (number? i) (int i)) (data/resolve-drum (name i))
      (e/mu-error (str word ":") (pr-str i) "is not a drum -- a key 35-81 or a name (kick, bd1, hhc)")))

(def part-ranks
  "A leaf's parts in the order musics.domain/leaf holds them: a drum in
   a pitch's place, an ornament and a tremolo its modifiers. An override
   (a context key: :volume, :instrument, :pan …) comes after them all."
  {:duration 1 :pitch 2 :drum 2 :articulation 3 :dynamic 4 :ornament 5 :tremolo 5 :tied 6})

(def ^:private plural-parts
  {:durations :duration :pitches :pitch :drums :drum :articulations :articulation :dynamics :dynamic
   :ornaments :ornament :tremolos :tremolo :volumes :volume :instruments :instrument})

(defn- label-part
  "The part a :name written before a stream names: one of part-ranks, or
   a context key the note overrides."
  [k]
  (let [k (get plural-parts k k)]
    (cond
      (part-ranks k) k
      (ck/canonical-key k) (if (try (ck/context-key k) (catch clojure.lang.ExceptionInfo _ nil))
                             (ck/canonical-key k)
                             (e/mu-error "zip:" (str k) "is no part of a note -- :duration :pitch :drum :articulation :dynamic :ornament :tremolo :tied, or a context key (:volume :instrument :pan …)")))))

(defn- rank [part] (part-ranks part 7))

(defn- zip-items
  "What zip takes from x: a line's notes as they play (no bar lines, a
   nested line's notes), else x's elements."
  [x]
  (if (music? x) (played-leaves x) (elements (streamed x))))

(defn- leaf-value
  "What a note of a line gives the part it is taken as."
  [part l]
  (case part
    :duration (if (= :REST (:type l)) l (:duration l))
    :pitch    (when (= :LEAF (:type l)) (let [ps (sort (:pitches l))] (if (next ps) (vec ps) (first ps))))
    :drum     (when (= :DRUM (:type l)) (:program l))
    (e/mu-error "zip: a line gives durations, pitches or drums -- for the" (name part) "give its stream (x volumes …)")))

(defn- rest-value? [v] (and (map? v) (= :REST (:type v))))

(defn- write-part
  "Leaf l with part's value v written on it, a nil writing nothing: its
   duration (a rest's makes it a rest), pitch (a chord's pitches), drum
   key, articulation, dynamic, ornament, tremolo or tie, or a context
   key it overrides."
  [l part v]
  (if (nil? v)
    l
    (case part
      :duration (let [dur (if (rest-value? v) (:duration v) v)]
                  (when-not (and (number? dur) (pos? dur))
                    (e/mu-error "zip: a duration is a length, got" (p/show v)))
                  (if (rest-value? v) (d/rest* nil nil dur) (-> l (assoc :duration dur) (dissoc :ratio))))
      :pitch (cond (= :DRUM (:type l)) (e/mu-error "zip: a drum has no pitch")
                   (not (or (number? v) (coll? v))) (e/mu-error "zip: a pitch is a MIDI number, got" (p/show v))
                   :else (-> l (assoc :pitches (if (coll? v) (vec v) [v])) (dissoc :key :key-shift :key-root)))
      :drum (if (= :DRUM (:type l))
              (assoc l :program (drum-key "zip" v))
              (e/mu-error "zip: a note is no drum -- as-drums turns notes into drums"))
      :articulation (articulate "zip" l v)
      :dynamic (let [w (when (number? v) (long v))
                     l' (assoc l :dynamic w)]
                 (if (and w (if (= :DRUM (:type l))
                              (lp/drum-accents w)
                              (lp/articulation-spelling [(:articulation l) w])))
                   l'
                   (e/mu-error "zip: a dynamic is the weight an articulation gives, one the text writes"
                               (if (= :DRUM (:type l)) (str "(a drum's: " (str/join " " (sort (keys lp/drum-accents))) ")")
                                   "(-20 alone, a ghost note; else the articulation's own)")
                               "-- got" (p/show v))))
      :ornament (let [o (str/replace (name v) #"^\\" "")]
                  (cond (= :DRUM (:type l)) (e/mu-error "zip: a drum has no ornament")
                        (orn/ornament-map o) (update l :modifiers (fnil conj []) ["ornament" o])
                        :else (e/mu-error "zip:" (pr-str v) "is not an ornament --" (str/join " " (sort (keys orn/ornament-map))))))
      :tremolo (if (#{8 16 32 64 128} v)
                 (update l :modifiers (fnil conj []) ["tremolo" v])
                 (e/mu-error "zip: a tremolo is 8 16 32 64 or 128, got" (p/show v)))
      :tied (if (= :DRUM (:type l)) (e/mu-error "zip: a drum isn't tied") (assoc l :tied (boolean v)))
      :instrument (set-instrument "zip" l v)
      :volume (assoc-in l [:overrides :volume] (volume-value "zip" v))
      (assoc-in l [:overrides part] v))))

(defn- written
  "Leaf l with each part's value of vals {part value} written on it in
   rank order; a rest stays a rest, every value unused."
  [l vals]
  (reduce (fn [l [part v]] (if (= :REST (:type l)) l (write-part l part v)))
          l (sort-by (comp rank key) vals)))

(defn- note-of
  "The leaf of one position's values {part value}: a rest when the
   duration is one or the pitch (the drum) is nil -- every other value
   is then left unused -- else the note, each part written in rank order,
   a nil writing nothing."
  [vals]
  (let [d    (get vals :duration)
        dur  (if (rest-value? d) (:duration d) d)
        _    (when-not (and (number? dur) (pos? dur))
               (e/mu-error "zip: a duration is a length, got" (p/show d)))
        drum (contains? vals :drum)
        tone (get vals (if drum :drum :pitch))]
    (if (or (rest-value? d) (nil? tone))
      (d/rest* nil nil dur)
      (written (if drum
                 (d/drum nil nil dur (drum-key "zip" tone))
                 (d/leaf nil nil dur (if (coll? tone) (vec tone) [tone])))
               (dissoc vals :duration :pitch :drum)))))

(defn- parts-of
  "[base [[part x] …]] of what zip takes: an unnamed line (or note) is
   the base, written on; a bundle gives its columns; a :name before a
   stream (or a line) names its part, a stream a reader or an algo word
   gave knows its own, and without a base the rest take the free ranks
   in order."
  [xs]
  (let [[base named]
        (loop [xs (seq xs) base nil out []]
          (if-let [[x & more] xs]
            (cond
              (keyword? x) (if (seq more)
                             (recur (next more) base (conj out [(label-part x) (first more)]))
                             (e/mu-error "zip:" (str x) "names no stream -- write it before one"))
              (bundle-columns x) (recur more base (into out (map (fn [[k c]] [(label-part k) c]) (bundle-columns x))))
              (music? x) (if base
                           (e/mu-error "zip: two lines to write on -- name the part the other gives (:pitch x)")
                           (recur more x out))
              :else (recur more base (conj out [(:mu/part (meta x)) x])))
            [base out]))
        _     (when (and base (some (comp nil? first) named))
                (e/mu-error "zip: onto a line, name each stream's part (:volume '[…]) -- a reader or an algo word names its own"))
        taken (set (map (comp rank first) (filter first named)))
        free  (remove taken [1 2 3 4 5 6])
        order {1 :duration 2 :pitch 3 :articulation 4 :dynamic 5 :ornament 6 :tied}
        parts (first (reduce (fn [[out free] [part x]]
                               (cond part        [(conj out [part x]) free]
                                     (seq free)  [(conj out [(order (first free)) x]) (rest free)]
                                     :else       (e/mu-error "zip: more streams than a note has parts -- name the overrides (:volume …)")))
                             [[] free] named))
        slots (map (fn [[part]] (if (#{:pitch :drum} part) :pitch part)) parts)]
    (when (and (seq slots) (not (apply distinct? slots)))
      (e/mu-error "zip: two streams for one part --" (str/join " " (map (comp str first) parts))))
    [base parts]))

(defn- streams-of
  "{:part :loop :endless :els} of each [part x]: a loop's elements are
   what it repeats, a line's what its notes give the part."
  [parts]
  (for [[part x] parts
        :let [lp  (loop? x)
              src (if lp (first (repeat-of x)) x)
              els (zip-items src)]]
    {:part part :loop lp :endless (and (not lp) (endless? (streamed x)))
     :els (if (music? src) (map #(leaf-value part %) els) els)}))

(defn- onto-base
  "base with each part's values written on its notes in written order,
   each part cycling and stepping once per note, a rest using its values
   unwritten; base keeps its form (map-leaves)."
  [base ps]
  (let [cur (volatile! (into {} (for [{:keys [part els endless]} ps]
                                  (let [s (seq els)]
                                    (when-not s (e/mu-error "zip: no" (name part) "to take"))
                                    [part (if endless s (cycle (vec s)))]))))]
    (map-leaves (fn [l]
                  (let [vals (update-vals @cur first)]
                    (vswap! cur update-vals rest)
                    (written l vals)))
                base)))

(defn- notes-of
  "Leaves from their parts: one note per duration, every other part
   cycling under the durations; durations in a loop give a loop, as long
   as all the parts take to line up again."
  [ps]
  (let [dur  (first (filter #(= :duration (:part %)) ps))
        _    (when (:endless dur) (e/mu-error "zip: endless durations, not a loop -- take some first"))
        n    (when-not (:loop dur) (count (vec (:els dur))))
        ps   (for [{:keys [part els endless] :as p} ps]
               (cond
                 (and endless (:loop dur)) (e/mu-error "zip: the" (name part) "are endless, not a loop -- take some first")
                 :else (let [els (vec (if endless (take n els) els))]
                         (when (empty? els) (e/mu-error "zip: no" (name part) "to take"))
                         (assoc p :els els))))
        n    (or n (apply mtools/lcm-multiple (map (comp count :els) ps)))
        notes (line (for [i (range n)]
                      (note-of (into {} (for [{:keys [part els]} ps] [part (els (mod i (count els)))])))))]
    (if (:loop dur) (repetition notes :infinite []) notes)))

(defn- bundle-of
  "The parts as a bundle, columns in rank order, each as it is -- they
   cycle on their own once durations join them; a loop stays a loop
   ('[60 62] [~]); an endless stream that isn't one is taken to the
   longest finite part's length."
  [ps]
  (let [fin  (remove #(or (:endless %) (:loop %)) ps)
        _    (when (empty? ps) (e/mu-error "zip: nothing to zip"))
        _    (when-let [p (first (filter #(empty? (:els %)) fin))] (e/mu-error "zip: no" (name (:part p)) "to take"))
        _    (when (and (empty? fin) (some :endless ps))
               (e/mu-error "zip: an endless part and nothing finite beside it -- take some first, or make it a loop"))
        n    (when (seq fin) (apply max (map (comp count vec :els) fin)))
        col  (fn [els] (str "'[" (str/join " " (map p/show els)) "]"))]
    (read-text (str "'[" (str/join " " (for [{:keys [part els loop endless]} (sort-by (comp rank :part) ps)]
                                         (str part " " (cond loop    (str (col els) " [~]")
                                                             endless (col (take n els))
                                                             :else   (col els)))))
                    "]"))))

(defn zip-parts
  "What zip gives of xs, as zip takes them: onto a line (the base), its
   notes with the parts written on them; else notes from durations and
   pitches (or drums); else a bundle, the parts as columns. Every part
   steps once per note, a rest using its value unwritten; a part given
   again replaces the base's."
  [xs]
  (let [[base parts] (parts-of xs)
        ps  (streams-of parts)
        has (set (map :part ps))]
    (cond
      base (onto-base base ps)
      (and (has :duration) (or (has :pitch) (has :drum))) (notes-of ps)
      :else (bundle-of ps))))

;; -- >ctx: a control as a context ------------------------------------------------

(def ^:private curve-marks
  "A :curve column's names: how the value glides to the next point -- a
   ramp's direction is the way the values go, these its curve letter."
  {:step nil :lin "" :smooth "s" :in "i" :out "o"})

(defn- ctx-columns
  "[[part x] …] of what >ctx takes: a :name before each stream -- :duration,
   :curve, or a context key (:volume, :Tempo, :pan, :Key …)."
  [xs]
  (loop [xs (seq xs) out []]
    (if-let [[x & more] xs]
      (cond
        (not (keyword? x)) (e/mu-error ">ctx: name each stream -- :duration '[1/1 1/1] :volume '[50 90] -- got" (p/show x))
        (empty? more)      (e/mu-error ">ctx:" (str x) "names no stream -- write it before one")
        :else (let [part (if (#{:curve :curves} x) :curve (label-part x))]
                (when (and (part-ranks part) (not= :duration part))
                  (e/mu-error ">ctx: makes a context, the music's ! keys --" (str x) "is a part of a note (zip writes those)"))
                (recur (next more) (conj out [part (first more)]))))
      out)))

(defn- instruction-text
  "The instruction setting context key k to v, as text writes it."
  [k v]
  (let [text #(cond (keyword? %) (name %)
                    (string? %)  %
                    (sequential? %) (str/join "," %)
                    :else (str %))]
    (if (= :Key k)
      (str "!K:" (if (instance? musics.common.music_elements.Key v) (el/key->str v) (text v)))
      (str "!" (name k) ":" (text v)))))

(defn control->ctx
  "The ^{ } of a control, as >ctx takes it: :duration and one or more
   context keys, each a stream, and an optional :curve (step lin smooth
   in out). The steps start at 0 and follow each other by the durations;
   each key's value holds until the next (step) or glides to it. The
   longest finite column sets how many steps there are, a shorter one
   cycling, a loop or an endless stream taken to that length."
  [xs]
  (let [cols  (ctx-columns xs)
        ps    (streams-of cols)
        _     (when-not (some #(= :duration (:part %)) ps) (e/mu-error ">ctx: needs :duration -- the time each value holds"))
        _     (when-not (some #(not (#{:duration :curve} (:part %))) ps)
                (e/mu-error ">ctx: needs a context key beside the durations -- :volume '[50 90] …"))
        fin   (remove #(or (:endless %) (:loop %)) ps)
        _     (when (empty? fin) (e/mu-error ">ctx: every column loops or is endless -- take some first"))
        _     (when-let [p (first (filter #(empty? (:els %)) fin))] (e/mu-error ">ctx: no" (name (:part p)) "to take"))
        n     (apply max (map (comp count vec :els) fin))
        col   (into {} (for [{:keys [part els]} ps]
                         [part (vec (take n (cycle (vec (take n els)))))]))
        durs  (mapv #(let [d (if (rest-value? %) (:duration %) %)]
                       (if (and (number? d) (pos? d)) d (e/mu-error ">ctx: a duration is a length, got" (p/show %))))
                    (col :duration))
        times (vec (reductions + 0 (butlast durs)))
        curve #(let [c (some-> (get (col :curve) %) name keyword)]
                 (if (contains? curve-marks (or c :step)) (curve-marks (or c :step))
                     (e/mu-error ">ctx: a curve is step lin smooth in or out, got" (p/show (get (col :curve) %)))))
        at    #(when (pos? %) (str "@" %))
        instrs (for [[k vs] col :when (not (#{:duration :curve} k))
                     i (range n) :let [v (vs i)] :when (some? v)]
                 (let [c    (curve i)
                       nxt  (some #(when (some? %) %) (map vs (range (inc i) n)))
                       mark (when (and c (number? v) (number? nxt)) (str (if (< nxt v) ">" "<") c))]
                   (str (instruction-text k v) mark (at (times i)))))]
    (read-text (str "^{ " (str/join " " instrs) " }"))))
