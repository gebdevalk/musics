(ns algo.tree.live
  "Live playback: a NAME binds a tree and a tctx, and any voice playing
   with :algo name follows them.

     (def riff (notes (gate (euclid) (cycled (scale)))))
     (def tctx  (t/tctx riff))
     (t/live! :riff riff tctx)        ; an endless voice -- or (play :verse :algo :riff)
     (t/setp! tctx :k 5)         ; heard on the next note
     (t/retree! :riff (notes (shuffled (scale))))   ; same tctx, fitted to the new tree
     (t/stop! :riff)

   The name watches its tctx: every change re-registers it in core.wall,
   which the engine notices on the next note. Two names may share one
   tctx.

   Two modes, chosen by what the tree reads:
   - GENERATOR (the tree doesn't read :nodes): each note the voice would
     play is replaced by the next element of the tree's data -- Leaf/Rest
     maps (lib/notes) or plain pitches/nil (notes of :dur, a :note-value).
     A finite result starts over when exhausted.
   - TRANSFORM (the tree reads :nodes, e.g. (transpose :nodes)): the
     voice's own notes arrive as :nodes and the tree's data replaces them.

   Each voice keeps its own position; a change re-runs the tree once and
   continues at the same position. A tree that fails to run keeps the
   previous material and prints why. Per note, a generator costs one
   swap! on the voice's own cursor."
  (:require [algo.tree :as tr]
            [algo.tree.lib :as lib]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.registries :as reg]
            [core.repo :as repo]
            [core.wall :as wall]
            [common.music-data :refer [quantity]]))

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
      (lib/->part current (get-in spec [:params :dur] (:default (quantity :note-value)))))))

(defn- wall-fn
  "The core.wall fn for one {:tree :params} spec. Parts it produces are
   tagged ::step so core.wall's batch-then-singleton double call never
   handles a note twice."
  [spec cursors]
  (let [transform? (some #{:nodes} (map :key (tr/param-keys (:tree spec))))]
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
  "(Re)register `name`: a fresh wall fn (what the engine compares to
   notice a change) built from its tree and its tctx's current params,
   plus the binding and the per-voice cursors, kept across changes."
  [name tree tctx]
  (let [cs   (or (:cursors (entry name)) (atom {}))
        spec {:tree tree :params (dissoc (:params @tctx) :nodes)}]
    (wall/build-algo! name (wall-fn spec cs) (pr-str (tr/show tree)))
    (swap! reg/*algo-registry* update name assoc :tree tree :tctx tctx :cursors cs)
    name))

(defn- bind!
  "Bind `name` to `tree` + `tctx` and follow the tctx from now on."
  [name tree tctx]
  (let [ks (set (map :key (tr/param-keys tree)))]
    (when-let [missing (seq (remove (conj (set (keys (:params @tctx))) :nodes) ks))]
      (throw (ex-info (str "algo.tree: this tctx has no " (apply str (interpose " " missing))
                           " -- (fit! tctx tree) adds them")
                      {:missing (vec missing)}))))
  (when-let [old (:tctx (entry name))] (remove-watch old [::live name]))
  (register! name tree tctx)
  (add-watch tctx [::live name]
             (fn [_ _ old new]
               (when (and (not= (:params old) (:params new)) (:tree (entry name)))
                 (register! name (:tree (entry name)) tctx))))
  name)

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

(defn live!
  "Bind `name` to `tree` + `tctx`. A generator tree also gets an
   endless voice of its own, alongside whatever's playing (returns its
   path); a transform tree (one reading :nodes) only binds the name
   (returns it) -- play material through it with (play form :algo name)."
  [name tree tctx]
  (let [tree (tr/as-node tree)]
    (bind! name tree tctx)
    (if (some #{:nodes} (map :key (tr/param-keys tree)))
      name
      (engine-call 'play-add (source!) :algo name))))

(defn retree!
  "Swap `name`'s tree, keeping its tctx (fitted to the new tree first);
   its voices continue at the same position."
  [name tree]
  (let [tctx (or (:tctx (entry name)) (throw (ex-info (str "algo.tree: " name " is not live") {:name name})))
        tree (tr/as-node tree)]
    (tr/fit! tctx tree)
    (bind! name tree tctx)
    (tr/show tree)))

(defn play!
  "Play `tree` once with `src` (a tctx or a map)."
  [tree src]
  (engine-call 'play-add (vec (tr/run tree src))))

(defn stop!
  "Stop every voice following `name`, and stop following its tctx."
  [name]
  (when engine/*engine*
    (doseq [[path v] @(:voices engine/*engine*)
            :when (and (= name (:algo v)) (= path (:root-path v)))]
      (engine-call 'play-change path [])))
  (some-> (:cursors (entry name)) (reset! {}))
  (some-> (:tctx (entry name)) (remove-watch [::live name]))
  nil)

(defn uninstall!
  "Stop `name`'s voices and forget it."
  [name]
  (stop! name)
  (wall/unregister-algo! name))
