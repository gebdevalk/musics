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

(defn- switches
  "Which of infra, inter and ultra a mask turns on, as 0 or 1 each: infra is 1,
   inter 2, ultra 4 (5 is infra and ultra, 7 all three)."
  [mask]
  {:pre [(integer? mask) (<= 0 mask 7)]}
  [(bit-and mask 1) (quot (bit-and mask 2) 2) (quot (bit-and mask 4) 4)])

(defn- arity-of
  "The arity mu calls polation with, from the stack: 4 when the top three are
   0 or 1 and a divisor lies under them, else 2 (a divisor and a mask)."
  [stack]
  (let [top (take-last 4 stack)]
    (if (and (= 4 (count top)) (every? #{0 1} (rest top)) (some #{(first top)} divisors)) 4 2)))

(defn polation
  "The primary tones of divisor d (2 3 4 5 6 8 9 10), each lower tone of a pair
   followed by the infra (a - 1), inter (a + 1) and ultra (b + 1) tones that are
   on, in that order: [C4 B3 C#4 G4 F#4 ...] with all three. Which are on is given
   as three ints, 1 on and 0 off (6 1 0 1), or as one mask: infra 1, inter 2, ultra
   4 added (6 5 is the same as 6 1 0 1)."
  {:mu {:arity arity-of}
   :algo {:in [] :out :pitch
          :params {:divisor {:type :int :default 6 :min 2 :max 10 :choices [2 3 4 5 6 8 9 10] :doc "semitones stacked until a C comes back: 6 is C4 F#4 C5"}
                   :mask {:type :int :default 0 :min 0 :max 7 :doc "infra 1 + inter 2 + ultra 4: which polations are on"}}}}
  ([divisor mask]
   (let [[infra-on inter-on ultra-on] (switches mask)]
     (polation divisor infra-on inter-on ultra-on)))
  ([divisor infra-on inter-on ultra-on]
   {:pre [(every? #{0 1} [infra-on inter-on ultra-on])]}
   (let [tones (primary divisor)]
     (conj (vec (mapcat (fn [[a b]]
                          (concat [a]
                                  (when (= 1 infra-on) [(dec a)])
                                  (when (= 1 inter-on) [(inc a)])
                                  (when (= 1 ultra-on) [(inc b)])))
                        (partition 2 1 tones)))
           (peek tones)))))
