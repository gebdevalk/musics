(ns musics.algo.melodic.slonimsky
  "Slonimsky's polations (Thesaurus of Scales and Melodic Patterns, 1947): tones
   put before, between or after the tones of a melody.

   Relative to a seq of principal tones:
     (infra p ins)                    ins before each tone
     (ultra p ins)                    ins after each tone
     (inter p ins)                    ins between neighbouring tones
     (slonimsky2 p infra inter ultra) all three at once, each optional

   And on the tones of an equal division of the octaves, slonimsky: a divisor d
   (2 3 4 5 6 8 9 10) is the interval, in semitones, stacked from C4 until the
   pitch lands on a C again: lcm(d, 12) semitones, 12 / gcd(d, 12) steps (6: C4 F#4
   C5; 8: two octaves, 9: three, 5 and 10: five). Those are the primary tones.
   Polated tones always follow a primary tone, one for each consecutive pair of
   primary tones a and b (a below b):
     infra  a - 1   below the lower tone of the pair
     inter  a + 1   within the pair
     ultra  b + 1   above the higher tone of the pair
   and when several are on they follow a in that order. The last primary tone
   has nothing after it.
     6 infra:        [C4 B3  F#4 F4  C5]
     6 inter:        [C4 C#4 F#4 G4  C5]
     6 ultra:        [C4 G4  F#4 C#5 C5]")

;; ---------------------------------------------------------------------------
;; polations of a given melody
;; ---------------------------------------------------------------------------

(defn slonimsky2
  "The general form of infra, inter and ultra, each optional (nil or empty switches it off): for each principal tone p in order, emit infra, then p, then ultra (after the last tone only when ultra-after-last? is true), then inter (never after the last tone). Slonimsky's most flexible general function."
  {:algo {:in [:pitch] :out :pitch :arity 5
          :params {:infra {:type :vector :default [] :doc "before each tone"}
                   :inter {:type :vector :default [] :doc "between tones"}
                   :ultra {:type :vector :default [] :doc "after each tone"}
                   :ultra-after-last? {:type :bool :default false :doc "ultra after the last tone too"}}}}
  ([principal] (slonimsky2 principal nil nil nil false))
  ([principal infra inter ultra] (slonimsky2 principal infra inter ultra false))
  ([principal infra inter ultra ultra-after-last?]
   (let [infra (or infra [])
         inter (or inter [])
         ultra (or ultra [])
         n     (count principal)]
     (vec
       (mapcat
         (fn [i p]
           (concat
             infra
             [p]
             (when (or ultra-after-last? (< i (dec n))) ultra)
             (when (< i (dec n)) inter)))
         (range)
         principal)))))

(defn infra
  "Insert insertion BEFORE each tone in principal."
  {:algo {:in [:pitch :pitch] :out :pitch}}
  [principal insertion]
  (slonimsky2 principal insertion nil nil false))

(defn ultra
  "Insert insertion AFTER each tone in principal (including the last,
   matching Slonimsky's own 'ultrapolation' as a standalone operation --
   contrast mixed-polations' own default of stopping after the last
   tone when used as part of a MIXED form)."
  {:algo {:in [:pitch :pitch] :out :pitch}}
  [principal insertion]
  (slonimsky2 principal nil nil insertion true))

(defn inter
  "Insert insertion BETWEEN each pair of consecutive tones in principal.
   A principal of fewer than 2 tones passes through unchanged -- nothing
   to interpolate between."
  {:algo {:in [:pitch :pitch] :out :pitch}}
  [principal insertion]
  (if (< (count principal) 2)
    (vec principal)
    (slonimsky2 principal nil insertion nil false)))

;; ---------------------------------------------------------------------------
;; polations of an equal division of the octaves
;; ---------------------------------------------------------------------------

(def start "The first primary tone, C4." 60)

(def divisors "The divisors that come back to a C within MIDI's range: 2 3 4 5 6 8 9 10." [2 3 4 5 6 8 9 10])

(defn- lcm [a b] (/ (* a b) (loop [x a y b] (if (zero? y) x (recur y (mod x y))))))

(defn primary
  "The primary tones of divisor d, as MIDI pitches: C4 up in steps of d semitones
   to the C lcm(d, 12) above it."
  [d]
  {:pre [(some #{d} divisors)]}
  (vec (range start (inc (+ start (lcm d 12))) d)))

(defn slonimsky
  "The primary tones of divisor d (2 3 4 5 6 8 9 10), each lower tone of a pair
   followed by the infra (a - 1), inter (a + 1) and ultra (b + 1) tones the three
   booleans switch on, in that order: [C4 B3 C#4 G4 F#4 ...] for 6 true true true."
  {:algo {:in [] :out :pitch :arity 4
          :params {:divisor {:type :int :default 6 :min 2 :max 10 :choices [2 3 4 5 6 8 9 10] :doc "semitones stacked until a C comes back: 6 is C4 F#4 C5"}
                   :infra? {:type :bool :default false :doc "a semitone below the lower tone of each pair"}
                   :inter? {:type :bool :default false :doc "a semitone above the lower tone of each pair"}
                   :ultra? {:type :bool :default false :doc "a semitone above the higher tone of each pair"}}}}
  [divisor infra? inter? ultra?]
  (let [tones (primary divisor)]
    (conj (vec (mapcat (fn [[a b]]
                         (concat [a]
                                 (when infra? [(dec a)])
                                 (when inter? [(inc a)])
                                 (when ultra? [(inc b)])))
                       (partition 2 1 tones)))
          (peek tones))))
