;; Pitch-class helpers: build-scale and from-key work in plain 0-11 pitch classes, the representation
;; musics.algo.common.gate and zfilter also use, and are not specific to melodies.

(ns musics.algo.melodic.pitch
  (:require [musics.common.music-elements :as el]))

(defn build-scale
  "root (a pitch class, 0-11) + intervals (semitone offsets from root, e.g. [0 2 4 5 7 9 11]) -> pitch classes wrapped into 0-11. For a named key and scale use from-key; this is for custom patterns.
     (build-scale 0 [0 2 4 5 7 9 11])  ;=> [0 2 4 5 7 9 11] -- C major
     (build-scale 9 [0 2 3 5 7 8 10])  ;=> [9 11 0 2 4 5 7]  -- A minor"
  [root intervals]
  (mapv #(mod (+ root %) 12) intervals))

(defn- key->pcs
  "A Key record's own :pitches, wrapped into 0-11 -- the one place both
   from-key and from-key-spec below actually do the wrapping, so it's
   never typed out twice."
  [k]
  (mapv #(mod % 12) (el/key-pitches k)))

(defn from-key
  "A pitch-class scale (0-11) from a named key and scale (musics.common.music-elements/key).
     (from-key :C :major)            ;=> [0 2 4 5 7 9 11]
     (from-key :A :minor)            ;=> [9 11 0 2 4 5 7]
     (from-key :C :pentatonic-major) ;=> [0 2 4 7 9]"
  [key-kw scale-kw]
  (key->pcs (el/key key-kw scale-kw)))

(defn from-key-spec
  "from-key from one spec string -- \"F#.major\", \"B&.minor\", \"C.dorian\" (musics.common.music-elements/parse-key). nil when it does not parse.
     (from-key-spec \"G.dorian\") ;=> [7 9 10 0 2 4 5], rooted on G"
  [spec]
  (when-let [k (el/parse-key spec)]
    (key->pcs k)))

(defn resolve-scale
  "scale-spec as a 0-11 pitch-class vector: a scale vector (as it is), a [key-kw scale-kw] pair of keywords (from-key) or a \"F#.major\" string (from-key-spec). A 2-element vector counts as a key pair only when both elements are keywords."
  [scale-spec]
  (cond
    (string? scale-spec) (from-key-spec scale-spec)
    (and (vector? scale-spec) (= 2 (count scale-spec)) (every? keyword? scale-spec))
    (apply from-key scale-spec)
    :else (vec scale-spec)))

(comment
  (build-scale 0 [0 2 4 5 7 9 11])
  (build-scale 9 [0 2 3 5 7 8 10])
  (from-key :C :major)
  (from-key :A :minor)
  (from-key :C :pentatonic-major)
  (from-key-spec "G.dorian")
  (resolve-scale [0 2 4 5 7 9 11])
  (resolve-scale [:C :major])
  (resolve-scale "G.dorian")
  )
