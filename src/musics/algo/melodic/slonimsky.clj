(ns musics.algo.melodic.slonimsky
  "Slonimsky's polations (Thesaurus of Scales and Melodic Patterns, 1947): tones
   inserted among the primary tones of a melody. Infra, inter and ultra say where
   the inserted tones lie in pitch -- below the lower tone of a pair, within it,
   above the higher one -- and they always sound AFTER a primary tone, never
   before one. polation works that way (below).

   The forms on a melody you give take the insertion you give, tones you chose
   to lie below, within or above, and put it after every primary tone but the
   last, infra first, then inter, then ultra:
     (infra p ins)                    ins after each tone
     (inter p ins)                    ins after each tone
     (ultra p ins)                    ins after each tone
     (polation2 p infra inter ultra) all three, each optional

   And on the tones of an equal division of the octaves, polation: a divisor d
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

(defn polation2
  "The general form of infra, inter and ultra, each optional (nil or empty switches it off): each pair of consecutive principal tones is the first tone, then its infra, its inter and its ultra insertion, in that order; the last tone has nothing after it. Inserted tones always come after a principal tone, never before one."
  {:algo {:in [:pitch] :out :pitch :arity 4
          :params {:infra {:type :vector :default [] :doc "after each tone but the last, first"}
                   :inter {:type :vector :default [] :doc "after each tone but the last, second"}
                   :ultra {:type :vector :default [] :doc "after each tone but the last, third"}}}}
  ([principal] (polation2 principal nil nil nil))
  ([principal infra inter ultra]
   (if (< (count principal) 2)
     (vec principal)
     (let [after (vec (concat infra inter ultra))]
       (conj (vec (mapcat (fn [p] (cons p after)) (butlast principal)))
             (peek (vec principal)))))))

(defn infra
  "Insert insertion after each tone in principal but the last, as the infra tones."
  {:algo {:in [:pitch :pitch] :out :pitch}}
  [principal insertion]
  (polation2 principal insertion nil nil))

(defn inter
  "Insert insertion after each tone in principal but the last, as the inter tones.
   A principal of fewer than 2 tones passes through unchanged."
  {:algo {:in [:pitch :pitch] :out :pitch}}
  [principal insertion]
  (polation2 principal nil insertion nil))

(defn ultra
  "Insert insertion after each tone in principal but the last, as the ultra tones."
  {:algo {:in [:pitch :pitch] :out :pitch}}
  [principal insertion]
  (polation2 principal nil nil insertion))

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

(defn polation
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
