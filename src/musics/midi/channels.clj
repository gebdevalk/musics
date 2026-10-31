(ns musics.midi.channels
  "The MIDI channel pool, shared by the live engine (musics.engine) and
   the file writer (musics.midi.file): a pure value, taken and released
   per voice layer ([path layer]).

   A channel carries one timbre, [program cc] (cc {10 pan}), and voices
   playing exactly that share it. A voice whose timbre changes stays on
   its channel when it is alone there, sending only what changed -- a
   program change, a CC -- before its next note; one sharing its channel
   moves to a channel already carrying the new timbre, else to a free
   one. 15 channels (9 is percussion); with all of them carrying other
   timbres a note gets none, rather than take another voice's.")

(def channels (vec (remove #{9} (range 16))))

(def empty-pool
  "{:claims {channel {:key [program cc] :refcount n}} :voices {[path layer] [channel key]}}"
  {:claims {} :voices {}})

(defn- carrying [claims key]
  (some (fn [[ch {k :key}]] (when (= k key) ch)) claims))

(defn- release [pool ch]
  (if-let [{:keys [refcount]} (get-in pool [:claims ch])]
    (if (<= refcount 1)
      (update pool :claims dissoc ch)
      (update-in pool [:claims ch :refcount] dec))
    pool))

(defn- changes
  "What to send moving a channel from timbre from to timbre to:
   {:program p :cc {n v}}, each only when it differs."
  [[from-program from-cc] [program cc]]
  (let [cc (into {} (remove (fn [[n v]] (= v (get from-cc n))) cc))]
    (cond-> {}
      (not= from-program program) (assoc :program program)
      (seq cc)                    (assoc :cc cc))))

(defn take-channel
  "[pool' channel sends] for the voice layer path playing key, [program
   cc]: sends what the channel still needs before the note ({:program
   :cc}, all of it on a fresh channel), nil when nothing; channel nil
   when every channel carries another timbre."
  [pool path [program cc :as key]]
  (let [[ch k] (get-in pool [:voices path])
        shared (carrying (:claims pool) key)]
    (cond
      (and ch (= k key))
      [pool ch nil]

      shared
      [(-> pool (release ch) (update-in [:claims shared :refcount] inc) (assoc-in [:voices path] [shared key]))
       shared nil]

      ;; alone on its channel: it stays, the channel carrying the new timbre
      (and ch (= 1 (get-in pool [:claims ch :refcount])))
      [(-> pool (assoc-in [:claims ch :key] key) (assoc-in [:voices path] [ch key]))
       ch (not-empty (changes k key))]

      :else
      (let [pool (-> pool (release ch) (update :voices dissoc path))]
        (if-let [free (some #(when-not (contains? (:claims pool) %) %) channels)]
          [(-> pool (assoc-in [:claims free] {:key key :refcount 1}) (assoc-in [:voices path] [free key]))
           free {:program program :cc cc}]
          [pool nil nil])))))

(defn release-voice
  "pool with every channel the voice at path holds (one per layer) released."
  [pool path]
  (reduce (fn [p [[vp :as k] [ch]]]
            (if (= vp path) (-> p (release ch) (update :voices dissoc k)) p))
          pool (:voices pool)))
