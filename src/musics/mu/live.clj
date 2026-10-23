(ns musics.mu.live
  "Live voices: a play word given a recipe -- a quotation, or a tree from
   the composer -- plays live (doc/md/mu-repl.md). Each note the voice
   reaches is the next note of what the recipe gives now, run on the
   music under it, or on nothing; a change of the voice's settings, or a
   new recipe (retree), runs it once more, and the voice goes on at its
   position (musics.algo.tree.live/bind-run!). The settings are a tctx
   per live name, fitted to the algo words the recipe runs
   (musics.mu.algos/*live*), so they know every param it reads."
  (:require [clojure.string :as str]
            [musics.algo.tree :as t]
            [musics.algo.tree.live :as tree-live]
            [musics.domain.context :as c]
            [musics.engine :as engine]
            [musics.mu.algos :as algos]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.wall :as wall]))

(defn recipe?
  "A quotation, or a tree (the composer's)."
  [x]
  (or (v/quotation? x) (t/node? x)))

(defn- music? [x] (or (v/material? x) (mt/leaf? x) (mt/endless? x) (and (sequential? x) (every? mt/leaf? x))))

(defn- as-leaves
  "What a recipe left, as the leaves it plays, a loop's endlessly."
  [x]
  (cond
    (mt/loop? x) (mapcat mt/played-leaves (mt/elements x))
    (music? x)   (mt/played-leaves x)
    :else        (e/mu-error "a recipe leaves music, not" (p/show x))))

(defn- result
  "What recipe gives on x (nil: on nothing), run with tctx's settings
   first and chain (the music the voice plays in): a mu value."
  [recipe x tctx chain]
  (binding [algos/*live* {:tctx tctx :chain chain}]
    (if (t/node? recipe)
      (mt/line (t/run recipe tctx chain))
      (let [left (e/call (if (some? x) [x] []) recipe)]
        (when (empty? left) (e/mu-error "the recipe leaves nothing to play"))
        (peek left)))))

(defn- run-fn [recipe x]
  (fn [tctx chain] (as-leaves (result recipe x tctx chain))))

(defn- empty-tctx
  "A tctx with no params yet: the recipe's algo words fit it."
  []
  (t/tctx (t/as-node 0)))

(def ^:private sources
  "Live name -> {:x x :recipe r}: the recipe and the music it runs on
   (nil: none)."
  (atom {}))

(def ^:private counter (atom 0))

(defn- fresh-name []
  (keyword (str "live" (swap! counter inc))))

(defn bind!
  "Bind live name to recipe on x (nil: none), keeping the name's settings
   when it has some. Returns the name."
  [name recipe x]
  (let [tctx (or (:tctx (wall/registered name)) (empty-tctx))]
    (swap! sources assoc name {:x x :recipe recipe})
    (tree-live/bind-run! name (run-fn recipe x) (p/show recipe) tctx)
    name))

(defn play!
  "A play word given a recipe: bind a live name (the voice's own, or a
   fresh one) and play x -- or, on nothing, the recipe's first result --
   through it. staged: [count] when it waits for a boundary (>>)."
  [x recipe ctx join? target staged]
  (let [named (or target (when (v/material? x) (mt/voice-name x)))
        name  (or named (fresh-name))]
    (bind! name recipe x)
    (let [tctx (:tctx (wall/registered name))
          form (if (some? x) x (doto (result recipe nil tctx nil) as-leaves))]
      (if staged
        (mt/stage! form ctx (first staged) join? named name)
        (mt/play! form ctx join? named name)))))

;; -- addressing a live voice ----------------------------------------------

(defn replay
  "What to put on the stack to play the live voice id again, as it
   started -- [x recipe], or [recipe] on nothing -- nil for a voice
   playing no recipe."
  [id]
  (let [nm (or (some-> (engine/voice-at id) :algo) id)]
    (when-let [{:keys [x recipe]} (get @sources nm)]
      (if (some? x) [x recipe] [recipe]))))

(defn live-name
  "The live name of the voice word addresses (an id or index)."
  [word voice]
  (let [id (mt/voice-id word voice)
        nm (or (some-> (engine/voice-at id) :algo)
               (when (and (keyword? id) (:tctx (wall/registered id))) id))]
    (when-not (and nm (:tctx (wall/registered nm)))
      (e/mu-error (str word ":") (pr-str voice) "plays no recipe -- give a play word one: x '( … ) :riff >"))
    nm))

(defn tctx-of [word voice] (:tctx (wall/registered (live-name word voice))))

(defn settings-of
  "{\"?k\" 5 …}: the ? settings a context holds."
  [ctx]
  (let [cx (:context (v/node ctx))]
    (into {} (for [k (keys @(:envelopes-atom cx)) :when (and (string? k) (str/starts-with? k "?"))]
               [k (c/ctx-value-chain [cx] k Double/POSITIVE_INFINITY)]))))

(defn retree!
  "A new recipe for the voice's live name: its settings and its voices'
   positions kept."
  [word recipe voice]
  (let [nm (live-name word voice)]
    (bind! nm recipe (:x (get @sources nm)))
    nm))
