(ns musics.mu.inputs
  "The inputs of a live voice: the named, changeable values its word was
   started with (the `$` locals of 5 8 42 'riff :bass ->). :bass/onsets is a
   reference (musics.mu.value/Ref) to one: @ reads it, ! stores into it. A
   voice has inputs from the moment it starts until it ends."
  (:require [clojure.string :as str]
            [musics.mu.value :as v]))

(def ^:private voices
  "Voice id -> {:names [\"onsets\" …] :atom a}: the order the word's stack effect
   names them in, and an atom holding {\"onsets\" 5 …}. The atom is also the
   voice's tctx, {:params-atom a}, which the live machinery watches: a store is
   heard on the voice's next note."
  (atom {}))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {:mu/error true})))

(def ^:private alive?
  "A fn of a voice id, true while it plays; set by musics.mu.live (the engine
   is not this namespace's business). Inputs of a voice that is gone are not
   reachable."
  (atom (constantly true)))

(defn alive!
  "Say how to tell a voice is playing: f of a voice id. The one before is back."
  [f]
  (let [old @alive?] (reset! alive? f) old))

(defn set-inputs!
  "Give voice (a keyword id) the inputs [[name value] …], in that order;
   its tctx back, {:params-atom a}. specs, key -> spec, ride on the atom
   (musics.algo.tree/specs) so settings and the GUI know each input's range."
  ([voice pairs] (set-inputs! voice pairs {}))
  ([voice pairs specs]
  (let [a (atom (into {} (map (fn [[n x]] [(str n) x]) pairs)) :meta {:specs specs})]
    (swap! voices assoc voice {:names (mapv (comp str first) pairs) :atom a})
    {:params-atom a})))

(defn clear! [voice] (swap! voices dissoc voice) voice)

(defn has? [voice] (contains? @voices voice))

(defn names [voice] (:names (get @voices voice)))

(defn tctx [voice] (when-let [a (:atom (get @voices voice))] {:params-atom a}))

(defn value [voice input] (some-> (get @voices voice) :atom deref (get (str input))))

(defn store!
  "Make input of voice hold x, heard on the voice's next note."
  [voice input x]
  (swap! (:atom (get @voices voice)) assoc (str input) x)
  x)

(defn ref-of
  "The reference :voice/input, refused with what there is when voice has no
   inputs or none called input."
  [voice input]
  (let [label (str ":" (name voice) "/" input)]
    (cond
      (and (has? voice) (not (@alive? voice)))
      (do (clear! voice)
          (fail (str label ":") "the voice" (str ":" (name voice)) "is not playing"))
      (not (has? voice))
      (fail (str label ":") "no voice" (str ":" (name voice)) "has inputs --"
            (if (seq @voices)
              (str "voices with inputs: " (str/join ", " (map #(str ":" (name %)) (sort (keys @voices)))))
              "start one: 5 8 'riff :bass ->"))
      (not (some #{input} (names voice)))
      (fail (str label ":") (str ":" (name voice)) "has no input" (str input) "--"
            (str/join ", " (names voice)))
      :else
      (v/->Ref label #(value voice input) #(store! voice input %)))))
