(ns musics.mu.material
  "Working with mu's musical values: their elements, building a value
   from elements (by printing them and reading the text back, which
   printing's isomorphism makes safe), storing, fetching and playing.
   Shared by the kernel and the tools."
  (:require [clojure.string :as str]
            [musics.core :as m]
            [musics.engine :as engine]
            [musics.mu.eval :as e]
            [musics.mu.grammar :as g]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.registries :as reg]
            [musics.repo :as repo]))

(defn leaf? [x] (and (map? x) (contains? #{:LEAF :REST :DRUM} (:type x))))

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
    (first (:values (v/read-literals nodes text (into {} @(repo/registry)) (:auto-ids @m/session))))))

(defn elements
  "The elements of a value: a container's children without its
   instructions (leaves as they are, nested containers as values of
   their own), a data container's values, a stream's items."
  [x]
  (cond
    (v/material? x) (let [{:keys [repo base]} x]
                      (mapv (fn [k] (if (keyword? k) (assoc (v/->Material k repo) :base base) k))
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

(defn map-leaves
  "x with f applied to every leaf, at any depth, keeping its form."
  [f x]
  (cond
    (leaf? x)       (f x)
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
    (let [mat  (->material x)
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
  (when-not (repo/current id) (e/mu-error "@@: nothing is stored as" (pr-str id)))
  (let [now (into {} @(repo/registry))]
    (assoc (v/->Material id now) :base now)))

(defn play!
  "Play x, under a context if given, without storing either: the engine
   reads a repo of what is stored plus their own nodes. Stops everything
   playing first; with join? true, x joins what is playing instead."
  [x ctx & [join?]]
  (when (nil? @m/receiver) (m/connect))
  (let [mat  (->material x)
        snap (merge (into {} @(repo/registry)) (v/own-nodes mat) (when ctx (v/own-nodes ctx)))
        form (if ctx [(:id ctx) (:id mat)] (:id mat))]
    (reg/log! :play)
    (binding [engine/*engine* (assoc engine/*engine* :repo (atom snap))]
      ((if join? engine/play-add engine/play) form))))
