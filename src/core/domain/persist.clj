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
            [core.domain.flat-domain :as d]))

;; ============================================================
;; Context freeze/thaw
;; ============================================================

(defn- freeze-context [ctx]
  (when ctx
    {:envelopes (into {} (map (fn [[k env]] [k (mapv #(into {} %) @(:points-atom env))])
                              @(:envelopes-atom ctx)))
     :duration  (:duration ctx)}))

(defn- thaw-context [frozen]
  (when frozen
    (c/->Context (atom (into {} (map (fn [[k pts]]
                                       [k (c/->Envelope (atom (mapv c/map->Point pts)))])
                                     (:envelopes frozen))))
                 (:duration frozen))))

;; ============================================================
;; Part freeze/thaw (leaves, containers, iterators -- recursive)
;; ============================================================

(defn- freeze-part [part]
  (cond
    (d/iterator? part)
    (cond-> {:record-type :iterator
             :type    (:type part)
             :id      (:id part)
             :context (freeze-context (:context part))
             :source  (freeze-part (:source part))
             :params  (:params part)}
      (get-in part [:params :alternative])
      (update :params assoc :alternative (freeze-part (get-in part [:params :alternative]))))

    (d/leaf? part)
    {:record-type  :leaf
     :id           (:id part)
     :context      (freeze-context (:context part))
     :duration     (:duration part)
     :pitches      (:pitches part)
     :articulation (:articulation part)
     :dynamic      (:dynamic part)
     :modifiers    (:modifiers part)
     :tied         (:tied part)}

    (d/rest? part)
    {:record-type :rest :id (:id part) :context (freeze-context (:context part))
     :duration (:duration part)}

    (d/drum? part)
    {:record-type :drum :id (:id part) :context (freeze-context (:context part))
     :duration (:duration part) :program (:program part)}

    (d/bar? part)
    {:record-type :bar :count (:count part)}

    (d/container? part)
    {:record-type :container
     :type     (:type part)
     :id       (:id part)
     :context  (freeze-context (:context part))
     :children (mapv (fn [c] (if (keyword? c) c (freeze-part c))) (:children part))}

    :else part))

(defn- thaw-part [frozen]
  (case (:record-type frozen)
    :iterator
    (d/iterator (:type frozen) (:id frozen) (thaw-context (:context frozen))
                (thaw-part (:source frozen))
                (cond-> (:params frozen)
                  (get-in frozen [:params :alternative])
                  (assoc :alternative (thaw-part (get-in frozen [:params :alternative])))))

    :leaf
    (d/leaf (:id frozen) (thaw-context (:context frozen)) (:duration frozen)
            (:pitches frozen) (:articulation frozen) (:dynamic frozen)
            (:modifiers frozen) (:tied frozen))

    :rest
    (d/rest* (:id frozen) (thaw-context (:context frozen)) (:duration frozen))

    :drum
    (d/drum (:id frozen) (thaw-context (:context frozen)) (:duration frozen) (:program frozen))

    :bar
    (d/bar (:count frozen))

    :container
    {:type     (:type frozen)
     :id       (:id frozen)
     :context  (thaw-context (:context frozen))
     :children (mapv (fn [c] (if (keyword? c) c (thaw-part c))) (:children frozen))}

    frozen))

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
