(ns musics.mu.print
  "Printing in mu is isomorphic to reading: what is printed can be typed
   back and gives the same value (doc/md/mu-repl.md). Musical values print
   as musics text, absolutely and with explicit durations (C4/4,
   <C4 E4 G4>4); a container prints its children and the instructions
   written among them (the walker keeps each one's text); everything
   else prints as Clojure prints it. A stream prints as a line (leaves)
   or as data (anything else); one made by repeating prints as what it
   repeats ('[60 62] [~], '[60 62] 3 times), another endless one shows
   its first 16 elements and an ellipsis."
  (:require [clojure.string :as str]
            [musics.common.music-elements :as el]
            [musics.domain :as d]
            [musics.input.reader.leaf-parser :as lp]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

(defn auto-id?
  "An id the walker assigned (s1, p2, c1 …), not one that was written."
  [id]
  (boolean (re-matches #"(?:[a-z]|repeat|tremolo|transpose|reverse|decorated)[0-9]+" (name id))))

(def ^:dynamic *names*
  "false: print music without its names (eq compares it so)."
  true)

(defn- id-prefix [node]
  (when-let [id (and *names* (:id node))]
    (when-not (auto-id? id) (str (name id) ": "))))

(defn- data-element [data-type x]
  (case data-type
    :pitch    (str/replace (lp/pitch->mus x) #"/$" "")
    :duration (str "/" (lp/duration->mus (rationalize x)))
    :string   (pr-str x)
    (cond (keyword? x)    (if (= data-type :name) (name x) (str x))
          (nil? x)        "nil"
          (= :LOOP (:type x)) (str (data-element nil (:items x)) " [~]")
          (sequential? x) (str "'[" (str/join " " (map #(data-element nil %) x)) "]")
          :else           (str x))))

(def ^:private typed-data
  "Data types whose elements need their type written to read back."
  #{:pitch :name :articulation})

(declare show-node)

(defn- committed?
  "Is the child id k the very node the repo holds under k? Then it is
   printed as its reference (:k), not expanded -- reading it back refers
   to the same node instead of defining it again."
  [repo k]
  (and (keyword? k) (some? (get repo k)) (identical? (get repo k) (repo/current k))))

(def ^:dynamic ^:private *last-dur*
  "While a value prints: the duration written last, as text, in reading
   order -- a note whose duration is the same leaves it out, as the
   reader then inherits it."
  nil)

(def ^:dynamic ^:private *last-drum*
  "While a value prints: the drum key written last -- a drum with the
   same key leaves it out, as the reader then inherits it."
  nil)

(defn- leaf-text [part]
  (let [d    (lp/duration-text part)
        part (if (= :DRUM (:type part))
               (if (= (:program part) @*last-drum*)
                 (dissoc part :program)
                 (do (reset! *last-drum* (:program part)) part))
               part)]
    (if (= d @*last-dur*)
      (lp/part->mus part false)
      (do (reset! *last-dur* d) (lp/part->mus part)))))

(defn- tuplet-of [x]
  (when (and (map? x) (#{:LEAF :REST :DRUM} (:type x))) (lp/tuplet-factor x)))

(defn- show-children
  "A container's children as text; consecutive notes whose length is no
   note value, sharing a tuplet factor, as one tuplet, 3:2(C4/8 D4 E4).
   Eager, run by run, so durations are written in reading order."
  [repo node]
  (str/join " "
            (into [] (mapcat (fn [run]
                               (if-let [f (tuplet-of (first run))]
                                 [(str (lp/tuplet-count f) "("
                                       (str/join " " (mapv #(leaf-text (assoc % :duration (/ (rationalize (:duration %)) f) :ratio nil)) run))
                                       ")")]
                                 (into [] (keep (fn [k] (if (committed? repo k)
                                                          (str k)
                                                          (show-node repo (if (keyword? k) (get repo k) k)))))
                                       run))))
                  (partition-by tuplet-of (:children node)))))

(defn- show-node [repo node]
  (cond
    (nil? node)        nil
    (d/bar? node)      (apply str (repeat (:count node) "|"))
    (d/jump? node)     (d/jump-text node)
    (d/iterator? node) (let [{n :count :keys [alternative bars]} (:params node)
                             src (:source node)]
                         (str (if bars
                                (str "[: " (id-prefix src) (show-children repo src) " :]")
                                (str "repeat " (when (= :TREMOLO (:type node)) "tremolo ")
                                     (if (= n :infinite) "~" n) " " (show-node repo src)))
                              (when alternative (str " volta " (show-node repo alternative)))))
    (:raw node)        (:raw node)
    (d/container? node)
    (case (:type node)
      :PAR     (str "{" (id-prefix node) (show-children repo node) "}")
      :CONTEXT (str "^{ " (id-prefix node) (show-children repo node) " }")
      :TCONTEXT (if (seq (:text node)) (str "@^{ " (:text node) " }") "@^{ }")
      :DATA    (let [t (:data-type node)]
                 (str "'[" (when (typed-data t) (str (name t) " "))
                      (str/join " " (map #(data-element t %) (:children node))) "]"))
      (str (when (:shuffle node) "shuffle ") (when (:choose node) (str "choose " (some->> (:weights node) (str/join ",") (#(str % " "))))) (when (:alternate node) "alternate ") (when-let [k (:rotate node)] (str "rotate " k " "))
           "[" (id-prefix node) (show-children repo node) "]"))
    :else (leaf-text node)))

(declare show*)

(defn show
  "x as mu text. A duration or drum key the reader would inherit from
   the note before it is left out; the first one is always written."
  [x]
  (if *last-dur*
    (show* x)
    (binding [*last-dur* (atom nil) *last-drum* (atom nil)] (show* x))))

(defn- show*
  [x]
  (cond
    (v/recipe? x)         (str "@( " (some-> (:id x) name (str ": ")) (:source x) (when (seq (:source x)) " ") ")")
    (:mu/repeat (meta x)) (let [[body n] (:mu/repeat (meta x))]
                            (str (show body) " " (if (= n :infinite) "[~]" (str n " times"))))
    (v/material? x)  (show-node (:repo x) (v/node x))
    (fn? x)          "#fn"
    (v/quotation? x) (:source x)
    (v/mark? x)      (apply str (repeat (:count x 1) "|"))
    (symbol? x)      (str "'" x)
    (instance? musics.common.music_elements.Key x) (el/key->str x)
    (and (map? x) (#{:LEAF :REST :DRUM} (:type x))) (leaf-text x)
    (and (map? x) (d/bar? x)) (apply str (repeat (:count x) "|"))
    (and (map? x) (d/jump? x)) (d/jump-text x)
    (and (sequential? x) (not (vector? x)) (not (string? x)))
    (let [shown (take 17 x)
          more  (> (count shown) 16)
          items (str/join " " (map show (take 16 shown)))
          leaves? (every? #(and (map? %) (#{:LEAF :REST :DRUM} (:type %))) (take 16 shown))]
      (str (if leaves? "[" "'[") items (when more " …") "]"))
    :else (pr-str x)))
