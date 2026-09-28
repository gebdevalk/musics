(ns algo.tree.live
  "Play a tree (see algo.tree) live, as an endless voice whose params and
   tree can change while it sounds.

     (def h (live/play! (lib/notes (lib/gate (lib/euclid) (lib/cycled (lib/scale))))
                        {:k 3 :n 8 :root 60 :intervals [0 2 4 7 9] :dur 1/16}))
     (live/param! h :k 5)                       ; heard on the next note
     (live/retree! h (lib/notes (lib/shuffled (lib/scale))))
     (live/stop! h)

   The tree's :data must be a seq of Leaf/Rest maps (what lib/notes and
   lib/pair-notes produce) or of plain pitches/nil (turned into notes of
   the :dur param, 1/8 when absent). A finite seq loops from its start.

   How it plays: a :count :infinite placeholder voice whose core.wall
   algo swaps each placeholder note for the next element of the tree's
   :data. Any change to params or tree re-runs the tree and continues at
   the SAME position (the element count played so far), so a param
   change is heard on the very next note, without restarting the
   pattern. A tree that throws on re-run keeps the previous material
   playing and prints why."
  (:require [algo.tree :as tr]
            [algo.tree.lib :as lib]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.repo :as repo]
            [core.wall :as wall]))

(defn- source!
  "Commit an endless placeholder -- a one-rest sequence wrapped in a
   :count :infinite Iterator -- and return its id."
  [id]
  (let [body-id (keyword (str (name id) "-body"))
        body    {:type :SEQ :id body-id :context (c/context)
                 :children [(d/rest* (keyword (str (name id) "-tick")) (c/context) 1/8)]}]
    (repo/commit-many! {id (d/iterator :REPEAT id (c/context) body {:count :infinite})})
    id))

(defn- material
  "Run the tree; nil (with a printed reason) if it throws or is empty."
  [tree params]
  (try
    (let [data (tr/run tree params)]
      (if (seq data)
        data
        (do (println "algo.tree.live:" (pr-str (tr/show tree)) "produced no material") nil)))
    (catch Exception e
      (println "algo.tree.live: keeping the previous material --" (.getMessage e))
      nil)))

(defn- at
  "`data` from element `pos` on; a finite `data` shorter than that wraps."
  [data pos]
  (or (seq (drop pos data))
      (let [n (count (take pos data))]
        (drop (mod pos n) data))))

(defn- refresh!
  "Re-run the handle's tree against its current params and continue at
   the same position; keeps the old material when the new run fails.
   Only a cursor into the material is held, never its head, so an
   infinite source doesn't accumulate in memory while it plays."
  [{:keys [state]}]
  (swap! state (fn [{:keys [tree params pos] :as s}]
                 (if-let [data (material tree params)]
                   (assoc s :cursor (at data pos))
                   s))))

(defn- next-part!
  "The next element of the handle's material, as a Leaf/Rest map. An
   exhausted (finite) material re-runs the tree from its start."
  [{:keys [state]}]
  (let [{:keys [current params]}
        (swap! state (fn [{:keys [cursor tree params] :as s}]
                       (let [cur (if (seq cursor) cursor (seq (material tree params)))]
                         (-> s
                             (assoc :current (first cur) :cursor (rest cur))
                             (update :pos inc)))))]
    (if (and (map? current) (:type current))
      current
      (lib/->part current (get params :dur 1/8)))))

(defn- wall-fn
  "A core.wall fn replacing each placeholder note with the next part.
   Tagged ::step so core.wall's batch-then-singleton double call never
   advances the position twice for one note (see core.wall/
   stateful-generator)."
  [h]
  (fn [nodes _ctx-chain _voice]
    (map (fn [node]
           (cond
             (contains? node ::step) node
             (not (or (d/leaf? node) (d/rest? node))) node
             :else (let [p (next-part! h)]
                     (assoc p :id (:id node) :context (:context node) ::step true))))
         nodes)))

(defn- play-add [& args]
  (if engine/*engine*
    (apply engine/play-add args)
    (apply (requiring-resolve 'musics.core/play-add) args)))

(defn play!
  "Start `tree` as an endless voice alongside whatever's playing, against
   `params`. Returns a handle for param!/params!/retree!/stop!/status."
  [tree params]
  (let [tree (tr/as-node tree)
        id   (keyword (gensym "treeLive"))
        h    {:name id :state (atom {:tree tree :params params :pos 0})}]
    (when-let [ks (seq (tr/missing tree params))]
      (throw (ex-info (str "algo.tree.live: " (pr-str (tr/show tree)) " needs "
                           (apply str (interpose " " ks)))
                      {:missing (vec ks)})))
    (when-not (seq (:cursor (refresh! h)))
      (throw (ex-info "algo.tree.live: the tree produced no playable material"
                      {:tree (tr/show tree)})))
    (wall/build-algo! id (wall-fn h))
    (assoc h :path (play-add (source! id) :algo id))))

(defn param!
  "Change one param (or several: k v k v ...); heard on the next note."
  [h & kvs]
  (swap! (:state h) update :params #(apply assoc % kvs))
  (refresh! h)
  (:params @(:state h)))

(defn params!
  "Replace the whole params map; heard on the next note."
  [h params]
  (swap! (:state h) assoc :params params)
  (refresh! h)
  params)

(defn retree!
  "Swap in a different tree, continuing at the same position."
  [h tree]
  (swap! (:state h) assoc :tree (tr/as-node tree))
  (refresh! h)
  (tr/show tree))

(defn stop!
  "Stop this one voice (others keep playing)."
  [h]
  (when-let [path (:path h)]
    (if engine/*engine*
      (engine/play-change path [])
      ((requiring-resolve 'musics.core/play-change) path [])))
  (wall/unregister-algo! (:name h))
  nil)

(defn status
  "What the handle is playing: its tree, params and position."
  [h]
  (let [{:keys [tree params pos]} @(:state h)]
    {:tree (tr/show tree) :params params :pos pos :path (:path h)}))
