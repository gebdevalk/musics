(ns musics.mu.print
  "Printing in mu is isomorphic to reading: what is printed can be typed
   back and gives the same value (doc/mu-repl.md). Musical values print
   as musics text, absolutely and with explicit durations (C4/4,
   <C4 E4 G4>4); a container prints its children and the instructions
   written among them (the walker keeps each one's text); everything
   else prints as Clojure prints it."
  (:require [clojure.string :as str]
            [musics.domain :as d]
            [musics.input.reader.leaf-parser :as lp]
            [musics.mu.value :as v]))

(defn- auto-id?
  "An id the walker assigned (s1, p2, c1 …), not one that was written."
  [id]
  (boolean (re-matches #"(?:[a-z]|repeat|tremolo|transpose|reverse|decorated)[0-9]+" (name id))))

(defn- id-prefix [node]
  (when-let [id (:id node)]
    (when-not (auto-id? id) (str (name id) ": "))))

(defn- data-element [data-type x]
  (case data-type
    :pitch    (str/replace (lp/pitch->mus x) #"/$" "")
    :duration (str "/" (lp/duration->mus (rationalize x)))
    :string   (pr-str x)
    (str (if (keyword? x) (name x) x))))

(def ^:private typed-data
  "Data types whose elements need their type written to read back."
  #{:pitch :name :articulation :keyword})

(declare show-node)

(defn- show-children [repo node]
  (str/join " " (keep #(show-node repo %) (d/children repo node))))

(defn- show-node [repo node]
  (cond
    (nil? node)        nil
    (d/bar? node)      (apply str (repeat (:count node) "|"))
    (d/iterator? node) (let [{n :count :keys [repeat-type alternative]} (:params node)]
                         (str "\\repeat " (if (= :TREMOLO (:type node)) "tremolo" (name (or repeat-type :unfold)))
                              " " n " " (show-node repo (:source node))
                              (when alternative (str " \\alternative [" (show-node repo alternative) "]"))))
    (:raw node)        (:raw node)
    (d/container? node)
    (case (:type node)
      :PAR     (str "{" (id-prefix node) (show-children repo node) "}")
      :CONTEXT (str "^{ " (id-prefix node) (show-children repo node) " }")
      :DATA    (let [t (:data-type node)]
                 (str "'[" (when (typed-data t) (str (name t) " "))
                      (str/join " " (map #(data-element t %) (:children node))) "]"))
      (str "[" (id-prefix node) (show-children repo node) "]"))
    :else (lp/part->mus node)))

(defn show
  "x as mu text."
  [x]
  (cond
    (v/material? x)  (show-node (:repo x) (v/node x))
    (v/quotation? x) (:source x)
    (and (map? x) (#{:LEAF :REST :DRUM} (:type x))) (lp/part->mus x)
    :else (pr-str x)))
