(ns musics.algo.melodic.slonimsky2
  "Slonimsky's polations on the tones of an equal division of the octaves.

   A divisor d (2 3 4 5 6 8 9 10) is the interval, in semitones, stacked from C4
   until the pitch lands on a C again: lcm(d, 12) semitones, 12 / gcd(d, 12) steps
   (6: C4 F#4 C5; 8: two octaves, 9: three, 5 and 10: five). Those are
   the primary tones. Polated tones always follow a primary tone, one for each
   consecutive pair of primary tones a and b (a below b):
     infra  a - 1   below the lower tone of the pair
     inter  a + 1   within the pair
     ultra  b + 1   above the higher tone of the pair
   and when several are on they follow a in that order. The last primary tone
   has nothing after it.
     6 infra:        [C4 B3  F#4 F4  C5]
     6 inter:        [C4 C#4 F#4 G4  C5]
     6 ultra:        [C4 G4  F#4 C#5 C5]")

(def start "The first primary tone, C4." 60)

(def divisors "The divisors that come back to a C within MIDI's range: 2 3 4 5 6 8 9 10." [2 3 4 5 6 8 9 10])

(defn- lcm [a b] (/ (* a b) (loop [x a y b] (if (zero? y) x (recur y (mod x y))))))

(defn primary
  "The primary tones of divisor d, as MIDI pitches: C4 up in steps of d semitones
   to the C lcm(d, 12) above it."
  [d]
  {:pre [(some #{d} divisors)]}
  (vec (range start (inc (+ start (lcm d 12))) d)))

(defn slonimsky2
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
