(ns algo.tree.live
  "Algo trees as core.wall algos: a NAME holds a tree plus its params,
   and any voice playing with :algo name follows it.

     (live/install! :riff (lib/notes (lib/gate lib/euclid (lib/cycled lib/scale)))
                    {:k 3 :n 8 :root 60 :intervals [0 2 4 7 9] :dur 1/16})
     (live/play! :riff)                      ; an endless voice -- or (play :verse :algo :riff)
     (live/param! :riff :k 5)                ; heard on the next note
     (live/retree! :riff (lib/notes (lib/shuffled lib/scale)))
     (live/stop! :riff)                      ; every voice following :riff

   Two modes, chosen by what the tree reads:
   - GENERATOR (the tree doesn't read :nodes): each note the voice would
     play is replaced by the next element of the tree's :data -- Leaf/
     Rest maps (lib/notes) or plain pitches/nil (notes of :dur, 1/8
     default). A finite :data starts over when exhausted.
   - TRANSFORM (the tree reads :nodes, e.g. (up :nodes)): the voice's own
     notes arrive as :nodes and the tree's :data replaces them.

   Each voice keeps its own position, so two voices on one name never
   share a stream. param!/retree! re-register the name, which the engine
   notices on the next note; a voice then re-runs the tree once and
   continues at the same position. A tree that fails to run keeps the
   previous material and prints why. Per note, a generator costs one
   swap! on the voice's own cursor -- the tree only runs on a change."
  (:require [algo.tree :as tr]
            [algo.tree.lib :as lib]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.registries :as reg]
            [core.repo :as repo]
            [core.wall :as wall]))

(defn- material
  "Run the tree; nil (with a printed reason) if it throws or is empty."
  [{:keys [tree params]}]
  (try
    (or (seq (tr/run tree params))
        (println "algo.tree.live:" (pr-str (tr/show tree)) "produced no material"))
    (catch Exception e
      (println "algo.tree.live: keeping the previous material --" (.getMessage e)))))

(defn- at
  "`data` from element `pos` on; a finite `data` shorter than that wraps."
  [data pos]
  (or (seq (drop pos data))
      (drop (mod pos (count (take pos data))) data)))

(defn- next-part!
  "The next part for the voice at `path` from `spec` ({:tree :params}).
   The tree runs only when `spec` is new to this voice; a run that fails
   is remembered (:tried) and not retried, the voice going on with its
   last good spec (:from). Holds a cursor, never the material's head,
   so an infinite source doesn't accumulate in memory."
  [cursors path spec]
  (let [{:keys [current]}
        (-> (swap! cursors update path
                   (fn [{:keys [from tried pos] :or {pos 0} :as v}]
                     (let [m   (when-not (or (identical? spec from) (identical? spec tried))
                                 (material spec))
                           v   (cond m          (assoc v :from spec :cursor (at m pos))
                                     (nil? from) (assoc v :from spec)
                                     :else      (assoc v :tried spec))
                           cur (or (seq (:cursor v)) (material (:from v)))]
                       (assoc v :pos (inc pos) :current (first cur) :cursor (rest cur)))))
            (get path))]
    (if (and (map? current) (:type current))
      current
      (lib/->part current (get-in spec [:params :dur] 1/8)))))

(defn- wall-fn
  "The core.wall fn for one registered spec. Parts it produces are
   tagged ::step so core.wall's batch-then-singleton double call never
   handles a note twice."
  [spec cursors]
  (let [transform? (some #{:nodes} (map :key (tr/params (:tree spec))))]
    (fn [nodes _ctx-chain voice]
      (cond
        (every? ::step nodes) nodes
        transform? (map #(assoc % ::step true)
                        (tr/run (:tree spec) (assoc (:params spec) :nodes nodes)))
        :else (map (fn [node]
                     (if (or (::step node) (not (or (d/leaf? node) (d/rest? node))))
                       node
                       (assoc (next-part! cursors (:path voice) spec)
                              :id (:id node) :context (:context node) ::step true)))
                   nodes)))))

(defn- entry [name] (wall/registered name))

(defn- register!
  "(Re)register `name` in core.wall's registry: a fresh wall fn (which is
   what the engine compares to notice a change) plus the spec itself and
   the per-voice cursors, kept across re-registrations."
  [name spec]
  (let [cs (or (:cursors (entry name)) (atom {}))]
    (wall/build-algo! name (wall-fn spec cs) (pr-str (tr/show (:tree spec))))
    (swap! reg/*algo-registry* update name assoc :spec spec :cursors cs)
    name))

(defn spec
  "What `name` holds: {:tree expr :params map}."
  [name]
  (some-> (:spec (entry name)) (update :tree tr/show)))

(defn install!
  "Register `tree` + `params` under `name` (a keyword) as a core.wall
   algo. Throws, before registering anything, if params are missing."
  [name tree params]
  (let [tree (tr/as-node tree)]
    (when-let [ks (seq (remove #{:nodes} (tr/missing tree params)))]
      (throw (ex-info (str "algo.tree.live: " (pr-str (tr/show tree)) " needs "
                           (apply str (interpose " " ks)))
                      {:missing (vec ks)})))
    (register! name {:tree tree :params params})))

(defn param!
  "Change one param (or several: k v k v ...) of `name`; heard on the next note."
  [name & kvs]
  (register! name (apply update (:spec (entry name)) :params assoc kvs))
  (:params (:spec (entry name))))

(defn params!
  "Replace `name`'s whole params map; heard on the next note."
  [name params]
  (register! name (assoc (:spec (entry name)) :params params))
  params)

(defn retree!
  "Swap in a different tree under `name`, continuing at the same position."
  [name tree]
  (register! name (assoc (:spec (entry name)) :tree (tr/as-node tree)))
  (spec name))

(defn- source!
  "Commit an endless placeholder -- a one-rest sequence wrapped in a
   :count :infinite Iterator -- and return its id. One note per pass, so
   the engine never pre-computes (and discards) a generated note."
  []
  (let [id (keyword (gensym "treeLive"))]
    (repo/commit-many!
     {id (d/iterator :REPEAT id (c/context)
                     {:type :SEQ :id (keyword (str (name id) "-body")) :context (c/context)
                      :children [(d/rest* (keyword (str (name id) "-tick")) (c/context) 1/8)]}
                     {:count :infinite})})
    id))

(defn- engine-call [f & args]
  (if engine/*engine*
    (apply (ns-resolve 'core.async-engine f) args)
    (apply (requiring-resolve (symbol "musics.core" (name f))) args)))

(defn play!
  "Start an endless voice following `name` (installed first when a tree
   and params are given), alongside whatever's playing. Returns its path."
  ([name] (engine-call 'play-add (source!) :algo name))
  ([name tree params] (install! name tree params) (play! name)))

(defn stop!
  "Stop every voice following `name`; the name stays installed."
  [name]
  (when engine/*engine*
    (doseq [[path v] @(:voices engine/*engine*)
            :when (and (= name (:algo v)) (= path (:root-path v)))]
      (engine-call 'play-change path [])))
  (some-> (:cursors (entry name)) (reset! {}))
  nil)

(defn uninstall!
  "Stop `name`'s voices and forget it."
  [name]
  (stop! name)
  (wall/unregister-algo! name))
