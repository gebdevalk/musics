(ns core.domain.persist
  "Serialize/deserialize a session (repo + auto-ids) to/from EDN.

   Two obstacles to a naive (spit (pr-str repo)):
   - Leaf/Rest/Drum/Bar/Iterator have custom print-method overrides (for
     terse REPL display -- they print as just their :id), so pr-str never
     emits their actual field data.
   - Context/Envelope hold atoms (:envelopes-atom/:points-atom); pr-str of
     a live atom isn't readable back at all.

   So every part is 'frozen' into a plain, fully-readable tagged map before
   writing, and 'thawed' back into the real record/atom shape after
   reading -- no custom EDN readers needed, just plain data."
  (:require [clojure.edn :as edn]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.music-elements :as el])
  (:import (common.music_elements Meter Key)))

;; ============================================================
;; Context freeze/thaw
;; ============================================================

;; A Point's :value is usually a plain scalar (number/string/keyword), but
;; world context keys (Meter, Key) hold real records -- pr-str would print
;; them fine (as #ns.Record{...} tagged literals) but edn/read-string can't
;; read an arbitrary record tag back, so they need the same explicit
;; freeze/thaw tagging as leaves/containers below.
(defn- freeze-context-value [v]
  (cond
    (instance? Meter v)
    {:record-type :meter :num (:num v) :den (:den v) :subdivisions (:subdivisions v)}

    (instance? Key v)
    {:record-type :key :signature (:signature v) :scale (:scale v) :pitches (:pitches v)}

    :else v))

(defn- thaw-context-value [v]
  (if (map? v)
    (case (:record-type v)
      :meter (el/make-meter (:num v) (:den v) (:subdivisions v))
      :key   (el/->Key (:signature v) (:scale v) (:pitches v))
      v)
    v))

(defn- freeze-context [ctx]
  (when ctx
    {:envelopes (into {} (map (fn [[k env]]
                                [k (mapv (fn [pt] (update (into {} pt) :value freeze-context-value))
                                         @(:points-atom env))])
                              @(:envelopes-atom ctx)))
     :duration  (:duration ctx)}))

(defn- thaw-context [frozen]
  (when frozen
    (c/->Context (atom (into {} (map (fn [[k pts]]
                                       [k (c/->Envelope
                                            (atom (mapv (fn [pt]
                                                          (c/map->Point (update pt :value thaw-context-value)))
                                                        pts)))])
                                     (:envelopes frozen))))
                 (:duration frozen))))

;; ============================================================
;; Part freeze/thaw (leaves, containers, iterators -- recursive)
;;
;; Built on core.domain.flat-domain/fold-node. Containers and Leaf/Rest/
;; Drum are already plain, :type-tagged maps (see flat_domain.clj), so
;; freezing/thawing them is nothing more than snapshotting/restoring
;; their nested :context -- every other field is already real, readable
;; data, same as pr-str would emit for any plain map. Iterator is the one
;; kind that still needs real reconstruction (it's a record, not a map)
;; -- freeze tags it with :record-type :iterator so fold-node's own
;; node-kind classification can still find it once thaw is walking data
;; just read back from EDN, where `instance? Iterator` can never be true
;; (see node-kind's own docstring). Bar needs no handler at all in either
;; direction -- it has no :context and nothing else to touch, so
;; fold-node's default (return the node unchanged) is already correct.
;; ============================================================

(def ^:private freeze-handlers
  {:container (fn [node folded]
                (assoc node
                       :context  (freeze-context (:context node))
                       :children (mapv :result folded)))
   :iterator  (fn [node {:keys [source alternative]}]
                (cond-> {:record-type :iterator
                         :type    (:type node)
                         :id      (:id node)
                         :context (freeze-context (:context node))
                         :source  source
                         :params  (:params node)}
                  alternative (update :params assoc :alternative alternative)))
   :leaf      (fn [node] (update node :context freeze-context))
   :rest      (fn [node] (update node :context freeze-context))
   :drum      (fn [node] (update node :context freeze-context))
   ;; Plain printed instruction markers (:assignment, :string, etc.) --
   ;; not a real domain part (fold-node's node-kind classifies them nil),
   ;; but their own :val can independently hold a Meter/Key record too
   ;; (the same value also went through ctx-append into some context
   ;; above, but this is a second, separate copy kept for display/
   ;; round-trip of the instruction itself).
   nil        (fn [node]
                (cond-> node
                  (and (map? node) (contains? node :val))
                  (update :val freeze-context-value)))})

(def ^:private thaw-handlers
  {:container (fn [node folded]
                (assoc node
                       :context  (thaw-context (:context node))
                       :children (mapv :result folded)))
   :iterator  (fn [node {:keys [source alternative]}]
                (d/iterator (:type node) (:id node) (thaw-context (:context node))
                            source
                            (cond-> (:params node)
                              alternative (assoc :alternative alternative))))
   :leaf      (fn [node] (update node :context thaw-context))
   :rest      (fn [node] (update node :context thaw-context))
   :drum      (fn [node] (update node :context thaw-context))
   nil        (fn [node]
                (cond-> node
                  (and (map? node) (contains? node :val))
                  (update :val thaw-context-value)))})

(defn- freeze-part [part] (d/fold-node part freeze-handlers))
(defn- thaw-part [frozen] (d/fold-node frozen thaw-handlers))

;; ============================================================
;; Repo-level (public)
;; ============================================================

(defn repo->edn
  "Serialize a session's repo + auto-ids counters to an EDN string."
  [repo auto-ids]
  (pr-str {:repo     (into {} (map (fn [[id part]] [id (freeze-part part)]) repo))
           :auto-ids auto-ids}))

(defn edn->repo
  "Deserialize an EDN string (from repo->edn) back into {:repo :auto-ids}."
  [edn-str]
  (let [{:keys [repo auto-ids]} (edn/read-string edn-str)]
    {:repo     (into {} (map (fn [[id part]] [id (thaw-part part)]) repo))
     :auto-ids auto-ids}))
