(ns musics.mu.voice
  "A live voice that follows a function of its inputs: each note the voice
   reaches is the next leaf of what (run values chain) gives now, a short result
   starting over; a change of an input runs it once more and the voice goes on
   at its position. The function is registered in musics.wall under the voice's
   id, so the engine plays through it (a wall fn is replaced, the engine notices
   on the next note). A run that fails keeps the last good material and says why."
  (:require [musics.domain :as d]
            [musics.mu.inputs :as inputs]
            [musics.registries :as reg]
            [musics.wall :as wall]))

(defn- material
  "What the spec's run gives with the inputs it was registered with and chain (the
   music the voice plays in), as a seq; nil, with a printed reason, when it
   throws or gives nothing."
  [{:keys [run vals label]} chain]
  (try
    (or (seq (run vals chain))
        (println "musics.mu.voice:" label "produced no material"))
    (catch Exception e
      (println "musics.mu.voice: keeping the previous material --" (.getMessage e)))))

(defn- at
  "`data` from element `pos` on; a finite `data` shorter than that wraps."
  [data pos]
  (or (seq (drop pos data))
      (drop (mod pos (count (take pos data))) data)))

(defn- next-part!
  "The next part for the voice at `path` from `spec`. The function runs only when
   `spec` is new to this voice; a run that fails is remembered and not retried,
   the voice going on with its last good spec. Holds a cursor, never the
   material's head, so an endless result doesn't accumulate in memory."
  [cursors path spec chain]
  (let [{:keys [current]}
        (-> (swap! cursors update path
                   (fn [{:keys [from tried pos] :or {pos 0} :as v}]
                     (let [m   (when-not (or (identical? spec from) (identical? spec tried))
                                 (material spec chain))
                           v   (cond m           (assoc v :from spec :cursor (at m pos))
                                     (nil? from) (assoc v :from spec)
                                     :else       (assoc v :tried spec))
                           cur (or (seq (:cursor v)) (material (:from v) chain))]
                       (assoc v :pos (inc pos) :current (first cur) :cursor (rest cur)))))
            (get path))]
    current))

(defn- wall-fn
  "The musics.wall fn for one spec. Parts it produces are tagged ::step so
   musics.wall's batch-then-singleton double call never handles a note twice."
  [spec cursors]
  (fn [nodes ctx-chain voice]
    (if (every? ::step nodes)
      nodes
      (map (fn [node]
             (if (or (::step node) (not (or (d/leaf? node) (d/rest? node))))
               node
               ;; no material at all (the only runs failed): the note as written
               (assoc (or (next-part! cursors (:path voice) spec ctx-chain) node)
                      :id (:id node) :context (:context node) ::step true)))
           nodes))))

(def ^:private runs
  "Voice id -> {:run f :label text :cursors atom}: what the voice follows now."
  (atom {}))

(defn- register!
  [voice]
  (let [{:keys [run label cursors]} (get @runs voice)
        spec {:run run :label label :vals (inputs/values voice)}]
    (wall/build-algo! voice (wall-fn spec cursors) label)
    (swap! reg/*algo-registry* update voice assoc :inputs voice :cursors cursors :label label)
    voice))

(defn follow!
  "Make voice (a keyword id with inputs, maybe none) play what (run values chain)
   gives, now and after every change of an input, from the start. values is
   {\"name\" value}. label names the voice in messages."
  [voice run label]
  (swap! runs assoc voice {:run run :label label :cursors (atom {})})
  (register! voice)
  (inputs/watch! voice ::follow (fn [_ _] (register! voice)))
  voice)

(defn retarget!
  "Make voice follow another run, going on at the position it has."
  [voice run label]
  (if (get @runs voice)
    (do (swap! runs update voice assoc :run run :label label)
        (register! voice))
    (follow! voice run label)))
