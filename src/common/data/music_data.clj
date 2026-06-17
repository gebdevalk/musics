;; music_data.clj
;; Clojure port of pymusics common/data/ — consolidated reference data.
;;
;; Sections: Pitches, Note Lengths, Dynamics, Articulations, Drums,
;;   MIDI, Time Signatures, Scales, Tempos, Keys, Context Keys.
;;
;; Numeric defaults are sourced from common.data.defaults.
;;
;; Python source files: pitches.py, durations.py, dynamics.py,
;;   articulations.py, drums.py, midi.py, meters.py, scales.py,
;;   tempos.py, xkeys.py, context_keys.py

(ns common.data.music-data
  (:require [clojure.string :as str]))

;; ============================================================
;; 1. PITCHES
;; ============================================================

(def note-names-sharp ["c" "c#" "d" "d#" "e" "f" "f#" "g" "g#" "a" "a#" "b"])
(def note-names-flat  ["c" "db" "d" "eb" "e" "f" "gb" "g" "ab" "a" "bb" "b"])

(def note-name->pitch-class
  {"c" 0, "d" 2, "e" 4, "f" 5, "g" 7, "a" 9, "b" 11})

(def pitch-class-sharp ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])
(def pitch-class-flat  ["C" "Db" "D" "Eb" "E" "F" "Gb" "G" "Ab" "A" "Bb" "B"])

(def tunings
  {:a4 440.0, :baroque-a4 415.0, :classical-a4 430.0,
   :devine-nine 432.0, :modern-a4 442.0})

(def just-intervals
  {:unison [1 0.0], :minor-second [16/15 111.731],
   :major-second [9/8 203.910], :minor-third [6/5 315.641],
   :major-third [5/4 386.314], :perfect-fourth [4/3 498.045],
   :augmented-fourth [45/32 590.224], :perfect-fifth [3/2 701.955],
   :minor-sixth [8/5 813.686], :major-sixth [5/3 884.359],
   :minor-seventh [9/5 1017.596], :major-seventh [15/8 1088.269],
   :octave [2 1200.0]})

(def pitch-bend {:min -8192, :max 8191, :center 0, :semitone-range 2})

;; ============================================================
;; 2. NOTE LENGTHS
;; ============================================================

(def note-lengths
  {:whole            {:length 1    :display "whole"           :dotted? false :triplet? false}
   :half             {:length 1/2  :display "half"            :dotted? false :triplet? false}
   :quarter          {:length 1/4  :display "quarter"         :dotted? false :triplet? false}
   :eighth           {:length 1/8  :display "eighth"          :dotted? false :triplet? false}
   :sixteenth        {:length 1/16 :display "sixteenth"       :dotted? false :triplet? false}
   :thirtysecond     {:length 1/32 :display "thirtysecond"    :dotted? false :triplet? false}
   :dotted-whole     {:length 3/2  :display "dotted whole"    :dotted? true  :triplet? false}
   :dotted-half      {:length 3/4  :display "dotted half"     :dotted? true  :triplet? false}
   :dotted-quarter   {:length 3/8  :display "dotted quarter"  :dotted? true  :triplet? false}
   :dotted-eighth    {:length 3/16 :display "dotted eighth"   :dotted? true  :triplet? false}
   :half-triplet     {:length 1/3  :display "half triplet"    :dotted? false :triplet? true}
   :quarter-triplet  {:length 1/6  :display "quarter triplet" :dotted? false :triplet? true}
   :eighth-triplet   {:length 1/12 :display "eighth triplet"  :dotted? false :triplet? true}})

(def dotted-map {:whole :dotted-whole, :half :dotted-half,
                 :quarter :dotted-quarter, :eighth :dotted-eighth})
(def triplet-map {:half :half-triplet, :quarter :quarter-triplet, :eighth :eighth-triplet})
(def note-config {:default :quarter, :min-length 1/32, :max-length 3/2})

;; ============================================================
;; 3. DYNAMICS
;; ============================================================

(def dynamics
  {:silence 0, :pppp 10, :ppp 20, :pp 30, :p 40,
   :mp 50, :mf 60, :f 70, :ff 80, :fff 90, :ffff 100})

(def instrument-dynamic-ranges
  {:piano {:min 30 :max 100 :typical 70}, :strings {:min 20 :max 110 :typical 75},
   :woodwinds {:min 35 :max 105 :typical 70}, :brass {:min 40 :max 127 :typical 90},
   :percussion {:min 60 :max 127 :typical 100}, :voice {:min 30 :max 100 :typical 75},
   :synth {:min 0 :max 127 :typical 80}})

(def cc7-volume {:min 0, :max 127, :default 100,
                 :off 0, :very-soft 20, :soft 40, :medium 70, :loud 100, :very-loud 120})
(def cc11-expression {:min 0, :max 127, :default 127, :soft 40, :medium 80, :loud 120})

;; ============================================================
;; 4. ARTICULATIONS
;; ============================================================

(def articulations
  {:staccatissimo {:duration 0.25 :dynamic 0}, :stopped {:duration 0.30 :dynamic 0},
   :staccato {:duration 0.40 :dynamic 0}, :marcato {:duration 0.55 :dynamic 10},
   :portato {:duration 0.80 :dynamic 0}, :accent {:duration 0.90 :dynamic 5},
   :legato {:duration 1.00 :dynamic 0}, :tenuto {:duration 1.00 :dynamic 0},
   :sfz {:duration nil :dynamic 10}, :fermata {:duration nil :dynamic 0}})

(def articulation-shorthand
  {"-!" :staccatissimo, "-." :staccato, "-+" :stopped,
   "-^" :marcato, "-_" :portato, "->" :accent, "--" :tenuto})

;; ============================================================
;; 5. DRUMS
;; ============================================================

(def drums
  {35 {:name "Acoustic Bass Drum" :abbr "bda"  :group "Kicks"}
   36 {:name "Bass Drum 1"        :abbr "bd1"  :group "Kicks"}
   37 {:name "Side Stick"         :abbr "ss"   :group "Snares"}
   38 {:name "Acoustic Snare"     :abbr "sna"  :group "Snares"}
   39 {:name "Hand Clap"          :abbr "hcp"  :group "Snares"}
   40 {:name "Electric Snare"     :abbr "sne"  :group "Snares"}
   41 {:name "Low Floor Tom"      :abbr "ttfl" :group "Toms"}
   42 {:name "Closed Hi-Hat"      :abbr "hhc"  :group "Hi-Hats"}
   43 {:name "High Floor Tom"     :abbr "ttfh" :group "Toms"}
   44 {:name "Pedal Hi-Hat"       :abbr "hhp"  :group "Hi-Hats"}
   45 {:name "Low Tom"            :abbr "ttl"  :group "Toms"}
   46 {:name "Open Hi-Hat"        :abbr "hho"  :group "Hi-Hats"}
   47 {:name "Low-Mid Tom"        :abbr "ttml" :group "Toms"}
   48 {:name "Hi-Mid Tom"         :abbr "ttmh" :group "Toms"}
   49 {:name "Crash Cymbal 1"     :abbr "cr1"  :group "Cymbals"}
   50 {:name "High Tom"           :abbr "tth"  :group "Toms"}
   51 {:name "Ride Cymbal 1"      :abbr "rd1"  :group "Cymbals"}
   52 {:name "Chinese Cymbal"     :abbr "chn"  :group "Cymbals"}
   53 {:name "Ride Bell"          :abbr "rdb"  :group "Cymbals"}
   54 {:name "Tambourine"         :abbr "tam"  :group "Percussion"}
   55 {:name "Splash Cymbal"      :abbr "spl"  :group "Cymbals"}
   56 {:name "Cowbell"            :abbr "cow"  :group "Percussion"}
   57 {:name "Crash Cymbal 2"     :abbr "cr2"  :group "Cymbals"}
   58 {:name "Vibraslap"          :abbr "vib"  :group "Percussion"}
   59 {:name "Ride Cymbal 2"      :abbr "rd2"  :group "Cymbals"}
   60 {:name "Hi Bongo"           :abbr "bgh"  :group "Percussion"}
   61 {:name "Low Bongo"          :abbr "bgl"  :group "Percussion"}
   62 {:name "Mute Hi Conga"      :abbr "cghm" :group "Percussion"}
   63 {:name "Open Hi Conga"      :abbr "cgho" :group "Percussion"}
   64 {:name "Low Conga"          :abbr "cgl"  :group "Percussion"}
   65 {:name "High Timbale"       :abbr "tbh"  :group "Percussion"}
   66 {:name "Low Timbale"        :abbr "tbl"  :group "Percussion"}
   67 {:name "High Agogo"         :abbr "agh"  :group "Percussion"}
   68 {:name "Low Agogo"          :abbr "agl"  :group "Percussion"}
   69 {:name "Cabasa"             :abbr "cab"  :group "Percussion"}
   70 {:name "Maracas"            :abbr "mar"  :group "Percussion"}
   71 {:name "Short Whistle"      :abbr "whs"  :group "Percussion"}
   72 {:name "Long Whistle"       :abbr "whl"  :group "Percussion"}
   73 {:name "Short Guiro"        :abbr "grs"  :group "Percussion"}
   74 {:name "Long Guiro"         :abbr "grl"  :group "Percussion"}
   75 {:name "Claves"             :abbr "clv"  :group "Percussion"}
   76 {:name "Hi Wood Block"      :abbr "wbh"  :group "Percussion"}
   77 {:name "Low Wood Block"     :abbr "wbl"  :group "Percussion"}
   78 {:name "Mute Cuica"         :abbr "cum"  :group "Percussion"}
   79 {:name "Open Cuica"         :abbr "cuo"  :group "Percussion"}
   80 {:name "Mute Triangle"      :abbr "trim" :group "Percussion"}
   81 {:name "Open Triangle"      :abbr "trio" :group "Percussion"}})
;; Drum name -> MIDI note lookup (user-facing names)
(def drum-name->midi
  (merge
   ;; Build from :abbr fields
   (into {} (for [[midi {:keys [abbr]}] drums] [abbr midi]))
   ;; Common aliases
   {"kick"   36, "bd"     36,
    "snare"  38, "sd"     38,
    "clap"   39,
    "hihat"  42, "hh"     42, "hat" 42,
    "tom"    45, "tom-lo" 45,
    "tom-hi" 50, "tom-mid" 47,
    "crash"  49,
    "ride"   51,
    "cowbell" 56, "cow"   56,
    "tamb"   54, "tambourine" 54,
    "bongo"  60, "bongo-hi" 60, "bongo-lo" 61,
    "conga"  63, "conga-hi" 63, "conga-lo" 64,
    "maracas" 70,
    "claves"  75,
    "whistle" 71}))

(defn resolve-drum [name]
  "Resolve a drum name or integer string to a MIDI note number.
   Returns the MIDI note or nil if unknown."
  (if (re-matches #"\d+" name)
    (Integer/parseInt name)
    (get drum-name->midi (str/lower-case name))))


;; ============================================================
;; 6. MIDI
;; ============================================================

(def midi-channels {:piano 0, :melody 1, :bass 2, :drums 9, :pad 3, :fx 4})

(def midi-cc
  {:modulation 1, :breath 2, :foot 4, :volume 7, :balance 8,
   :pan 10, :expression 11, :sustain 64, :portamento 65, :sostenuto 66,
   :soft-pedal 67, :legato 68, :hold-2 69, :sound-variation 70, :timbre 71,
   :brightness 74, :effects-depth 91, :reverb 91, :tremolo 92, :chorus 93,
   :detune 94, :phaser 95, :data-increment 96, :data-decrement 97,
   :all-sound-off 120, :all-controllers-off 121, :local-control 122, :all-notes-off 123})

(def gm-sound-set
  {:acoustic-grand {:prog 1 :abbr "AcGd"}, :bright-acoustic {:prog 2 :abbr "BrAc"},
   :electric-grand {:prog 3 :abbr "ElGr"}, :honky-tonk {:prog 4 :abbr "Hnky"},
   :electric-piano-1 {:prog 5 :abbr "EP1"}, :electric-piano-2 {:prog 6 :abbr "EP2"},
   :harpsichord {:prog 7 :abbr "Hpsd"}, :clavinet {:prog 8 :abbr "Clav"},
   :celesta {:prog 9 :abbr "Cel"}, :glockenspiel {:prog 10 :abbr "Glck"},
   :music-box {:prog 11 :abbr "MBox"}, :vibraphone {:prog 12 :abbr "Vib"},
   :marimba {:prog 13 :abbr "Mar"}, :xylophone {:prog 14 :abbr "Xyl"},
   :tubular-bells {:prog 15 :abbr "TbB"}, :dulcimer {:prog 16 :abbr "Dul"},
   :drawbar-organ {:prog 17 :abbr "DrOr"}, :percussive-organ {:prog 18 :abbr "PcOr"},
   :rock-organ {:prog 19 :abbr "RkOr"}, :church-organ {:prog 20 :abbr "ChOr"},
   :reed-organ {:prog 21 :abbr "RdOr"}, :accordion {:prog 22 :abbr "Acc"},
   :harmonica {:prog 23 :abbr "Harm"}, :tango-accordion {:prog 24 :abbr "TngAc"},
   :acoustic-guitar-nylon {:prog 25 :abbr "AcGtN"}, :acoustic-guitar-steel {:prog 26 :abbr "AcGtS"},
   :electric-guitar-jazz {:prog 27 :abbr "ElGtJ"}, :electric-guitar-clean {:prog 28 :abbr "ElGtC"},
   :electric-guitar-muted {:prog 29 :abbr "ElGtM"}, :overdriven-guitar {:prog 30 :abbr "OvGt"},
   :distortion-guitar {:prog 31 :abbr "DsGt"}, :guitar-harmonics {:prog 32 :abbr "GtHr"},
   :acoustic-bass {:prog 33 :abbr "AcBs"}, :electric-bass-finger {:prog 34 :abbr "ElBsF"},
   :electric-bass-pick {:prog 35 :abbr "ElBsP"}, :fretless-bass {:prog 36 :abbr "FrBs"},
   :slap-bass-1 {:prog 37 :abbr "SlB1"}, :slap-bass-2 {:prog 38 :abbr "SlB2"},
   :synth-bass-1 {:prog 39 :abbr "SyB1"}, :synth-bass-2 {:prog 40 :abbr "SyB2"},
   :violin {:prog 41 :abbr "Vln"}, :viola {:prog 42 :abbr "Vla"},
   :cello {:prog 43 :abbr "Clo"}, :contrabass {:prog 44 :abbr "CBs"},
   :tremolo-strings {:prog 45 :abbr "TrSt"}, :pizzicato-strings {:prog 46 :abbr "PzSt"},
   :orchestral-harp {:prog 47 :abbr "OHp"}, :timpani {:prog 48 :abbr "Tmp"},
   :string-ensemble-1 {:prog 49 :abbr "StE1"}, :string-ensemble-2 {:prog 50 :abbr "StE2"},
   :synth-strings-1 {:prog 51 :abbr "SyS1"}, :synth-strings-2 {:prog 52 :abbr "SyS2"},
   :choir-aahs {:prog 53 :abbr "ChAh"}, :voice-oohs {:prog 54 :abbr "VoOh"},
   :synth-voice {:prog 55 :abbr "SyVo"}, :orchestra-hit {:prog 56 :abbr "OrHt"},
   :trumpet {:prog 57 :abbr "Tpt"}, :trombone {:prog 58 :abbr "Tbn"},
   :tuba {:prog 59 :abbr "Tba"}, :muted-trumpet {:prog 60 :abbr "MTpt"},
   :french-horn {:prog 61 :abbr "FHn"}, :brass-section {:prog 62 :abbr "BrSc"},
   :synth-brass-1 {:prog 63 :abbr "SyBr1"}, :synth-brass-2 {:prog 64 :abbr "SyBr2"},
   :soprano-sax {:prog 65 :abbr "SpSx"}, :alto-sax {:prog 66 :abbr "AlSx"},
   :tenor-sax {:prog 67 :abbr "TnSx"}, :baritone-sax {:prog 68 :abbr "BrSx"},
   :oboe {:prog 69 :abbr "Ob"}, :english-horn {:prog 70 :abbr "EnHn"},
   :bassoon {:prog 71 :abbr "Bsn"}, :clarinet {:prog 72 :abbr "Cl"},
   :piccolo {:prog 73 :abbr "Pic"}, :flute {:prog 74 :abbr "Fl"},
   :recorder {:prog 75 :abbr "Rec"}, :pan-flute {:prog 76 :abbr "PnFl"},
   :blown-bottle {:prog 77 :abbr "BnBt"}, :shakuhachi {:prog 78 :abbr "Skh"},
   :whistle {:prog 79 :abbr "Whs"}, :ocarina {:prog 80 :abbr "Oca"},
   :lead-1 {:prog 81 :abbr "Ld1"}, :lead-2 {:prog 82 :abbr "Ld2"},
   :lead-3 {:prog 83 :abbr "Ld3"}, :lead-4 {:prog 84 :abbr "Ld4"},
   :lead-5 {:prog 85 :abbr "Ld5"}, :lead-6 {:prog 86 :abbr "Ld6"},
   :lead-7 {:prog 87 :abbr "Ld7"}, :lead-8 {:prog 88 :abbr "Ld8"},
   :pad-1 {:prog 89 :abbr "Pd1"}, :pad-2 {:prog 90 :abbr "Pd2"},
   :pad-3 {:prog 91 :abbr "Pd3"}, :pad-4 {:prog 92 :abbr "Pd4"},
   :pad-5 {:prog 93 :abbr "Pd5"}, :pad-6 {:prog 94 :abbr "Pd6"},
   :pad-7 {:prog 95 :abbr "Pd7"}, :pad-8 {:prog 96 :abbr "Pd8"},
   :fx-1 {:prog 97 :abbr "FX1"}, :fx-2 {:prog 98 :abbr "FX2"},
   :fx-3 {:prog 99 :abbr "FX3"}, :fx-4 {:prog 100 :abbr "FX4"},
   :fx-5 {:prog 101 :abbr "FX5"}, :fx-6 {:prog 102 :abbr "FX6"},
   :fx-7 {:prog 103 :abbr "FX7"}, :fx-8 {:prog 104 :abbr "FX8"},
   :sitar {:prog 105 :abbr "Sit"}, :banjo {:prog 106 :abbr "Bnj"},
   :shamisen {:prog 107 :abbr "Smi"}, :koto {:prog 108 :abbr "Kot"},
   :kalimba {:prog 109 :abbr "Kmb"}, :bagpipe {:prog 110 :abbr "Bgp"},
   :fiddle {:prog 111 :abbr "Fdl"}, :shanai {:prog 112 :abbr "Shn"},
   :tinkle-bell {:prog 113 :abbr "TnBl"}, :agogo {:prog 114 :abbr "Ago"},
   :steel-drums {:prog 115 :abbr "StDm"}, :woodblock {:prog 116 :abbr "WBlk"},
   :taiko-drum {:prog 117 :abbr "TkDm"}, :melodic-tom {:prog 118 :abbr "MlTm"},
   :synth-drum {:prog 119 :abbr "SyDm"}, :reverse-cymbal {:prog 120 :abbr "RvCy"},
   :guitar-fret-noise {:prog 121 :abbr "GtFr"}, :breath-noise {:prog 122 :abbr "BrNs"},
   :seashore {:prog 123 :abbr "Sea"}, :bird-tweet {:prog 124 :abbr "Brd"},
   :telephone-ring {:prog 125 :abbr "Tel"}, :helicopter {:prog 126 :abbr "Hel"},
   :applause {:prog 127 :abbr "Apl"}, :gunshot {:prog 128 :abbr "Gun"}})

(def program->name (into {} (map (fn [[k v]] [(:prog v) k]) gm-sound-set)))

;; ============================================================
;; 7. TIME SIGNATURES
;; ============================================================

(def time-signatures
  {"2/2" {:num 2 :den 2}, "2/4" {:num 2 :den 4}, "2/8" {:num 2 :den 8},
   "3/2" {:num 3 :den 2}, "3/4" {:num 3 :den 4}, "3/8" {:num 3 :den 8},
   "4/2" {:num 4 :den 2}, "4/4" {:num 4 :den 4}, "4/8" {:num 4 :den 8},
   "6/8" {:num 6 :den 8}, "6/4" {:num 6 :den 4}, "9/8" {:num 9 :den 8},
   "9/4" {:num 9 :den 4}, "12/8" {:num 12 :den 8}, "12/4" {:num 12 :den 4},
   "5/8-23" {:num 5 :den 8 :subdivisions [2 3]}, "5/8-32" {:num 5 :den 8 :subdivisions [3 2]},
   "5/4-23" {:num 5 :den 4 :subdivisions [2 3]}, "5/4-32" {:num 5 :den 4 :subdivisions [3 2]},
   "7/8-223" {:num 7 :den 8 :subdivisions [2 2 3]}, "7/8-232" {:num 7 :den 8 :subdivisions [2 3 2]},
   "7/8-322" {:num 7 :den 8 :subdivisions [3 2 2]}, "7/4-223" {:num 7 :den 4 :subdivisions [2 2 3]},
   "7/4-232" {:num 7 :den 4 :subdivisions [2 3 2]}, "7/4-322" {:num 7 :den 4 :subdivisions [3 2 2]},
   "8/8-332" {:num 8 :den 8 :subdivisions [3 3 2]}, "8/8-323" {:num 8 :den 8 :subdivisions [3 2 3]},
   "8/8-233" {:num 8 :den 8 :subdivisions [2 3 3]},
   "10/8-2233" {:num 10 :den 8 :subdivisions [2 2 3 3]},
   "10/8-3322" {:num 10 :den 8 :subdivisions [3 3 2 2]},
   "10/8-2323" {:num 10 :den 8 :subdivisions [2 3 2 3]}})

;; ============================================================
;; 8. SCALES
;; ============================================================

(def scales
  {:major {:intervals [0 2 4 5 7 9 11] :display "Major" :offset 0 :alternatives [:ionian]}
   :minor {:intervals [0 2 3 5 7 9 10] :display "Natural Minor" :offset -3
           :alternatives [:natural-minor :aeolian]}
   :harmonic-minor {:intervals [0 2 3 5 7 8 11] :display "Harmonic Minor" :offset -3}
   :melodic-minor {:intervals [0 2 3 5 7 9 11] :display "Melodic Minor" :offset -3}
   :ionian {:intervals [0 2 4 5 7 9 11] :display "Ionian" :offset 0 :alternatives [:major]}
   :aeolian {:intervals [0 2 3 5 7 9 10] :display "Aeolian" :offset -3
             :alternatives [:minor :natural-minor]}
   :dorian {:intervals [0 2 3 5 7 9 10] :display "Dorian" :offset 2}
   :mixolydian {:intervals [0 2 4 5 7 9 10] :display "Mixolydian" :offset 7}
   :phrygian {:intervals [0 1 3 5 7 8 10] :display "Phrygian" :offset 4}
   :lydian {:intervals [0 2 4 6 7 9 11] :display "Lydian" :offset 5}
   :locrian {:intervals [0 1 3 5 6 8 10] :display "Locrian" :offset -1}
   :pentatonic-major {:intervals [0 2 4 7 9] :display "Major Pentatonic" :offset 0}
   :pentatonic-minor {:intervals [0 3 5 7 10] :display "Minor Pentatonic" :offset 0}
   :blues-major {:intervals [0 2 3 4 7 9] :display "Major Blues" :offset 0}
   :blues-minor {:intervals [0 3 5 6 7 10] :display "Minor Blues" :offset 0}
   :whole-tone {:intervals [0 2 4 6 8 10] :display "Whole Tone" :offset 0}
   :diminished-hw {:intervals [0 1 3 4 6 7 9 10] :display "Diminished (Half-Whole)" :offset 0
                   :alternatives [:octatonic-hw]}
   :diminished-wh {:intervals [0 2 3 5 6 8 9 11] :display "Diminished (Whole-Half)" :offset 0
                   :alternatives [:octatonic-wh]}
   :phrygian-dominant {:intervals [0 1 4 5 7 8 10] :display "Phrygian Dominant" :offset 0
                       :alternatives [:spanish-phrygian]}
   :hungarian-minor {:intervals [0 2 3 6 7 8 11] :display "Hungarian Minor" :offset 0}
   :double-harmonic {:intervals [0 1 4 5 7 8 11] :display "Double Harmonic" :offset 0
                     :alternatives [:gypsy]}
   :bebop-dominant {:intervals [0 2 4 5 7 9 10 11] :display "Bebop Dominant" :offset 0}
   :bebop-major {:intervals [0 2 4 5 7 8 9 11] :display "Bebop Major" :offset 0}})

;; ============================================================
;; 9. TEMPOS
;; ============================================================

(def tempo-markings
  {:larghissimo 24, :adagissimo 24, :grave 35, :largo 50, :lent 52, :lento 52,
   :larghetto 63, :adagio 71, :adagietto 76, :marcia-moderato 84, :andante 92,
   :andantino 94, :andante-moderato 102, :moderato 114, :allegretto 116,
   :allegro-moderato 118, :allegro 138, :vivace 166, :vivacissimo 174,
   :allegrissimo 174, :allegro-vivace 174, :presto 184, :prestissimo 200})

(def tempo-categories
  {:largo [40 60], :lento [45 60], :adagio [60 70], :andante [70 85],
   :moderato [85 100], :allegro [100 130], :vivace [130 160],
   :presto [160 200], :prestissimo [200 250]})

(def tempo-config {:min 20 :default 120 :max 300})

;; ============================================================
;; Instruction → context mapping
;; ============================================================

(def instruction-context
  "Map of !BANG_CONST keyword -> [context-key value].
   Merges dynamics, tempo-markings, and manual entries."
  (merge
   (into {} (for [[k v] dynamics]       [k [:volume v]]))
   (into {} (for [[k v] tempo-markings] [k [:Tempo v]]))
   ;; Dynamic changes (TODO: replace with envelope ramps)
   {:cresc [:volume 80] :decresc [:volume 30] :dim [:volume 30]}
   ;; Meter
   {:commonTime  [:Meter "4/4"]  :cutTime  [:Meter "2/2"]}
   ;; Swing / feel
   {:straight    [:swing 0.0]    :swing    [:swing 0.5]
    :shuffle     [:swing 0.67]}
   ;; Panning
   {:left       [:panning -1.0]  :center   [:panning 0.0]
    :right      [:panning 1.0]}
   ;; Stage position (panning proxy)
   {:stageLeft    [:panning -0.7]  :stageCenter [:panning 0.0]
    :stageRight   [:panning 0.7]
    :near         [:panning 0.0]   :far         [:panning 0.0]}
   ;; Style hints
   {:jazz         [:Algorithm "jazz"]
    :latin        [:Algorithm "latin"]
    :rock         [:Algorithm "rock"]
    :classical    [:Algorithm "classical"]
    :swingFeel    [:Algorithm "swing"]}))

;; ============================================================
;; 10. KEYS
;; ============================================================

(def signatures
  {:C  {:accidental 0  :tonic-pc 0  :display "C"}
   :G  {:accidental 1  :tonic-pc 7  :display "G"}
   :D  {:accidental 2  :tonic-pc 2  :display "D"}
   :A  {:accidental 3  :tonic-pc 9  :display "A"}
   :E  {:accidental 4  :tonic-pc 4  :display "E"}
   :B  {:accidental 5  :tonic-pc 11 :display "B"}
   :F# {:accidental 6  :tonic-pc 6  :display "F#" :alternative "Gb"}
   :Gb {:accidental -6 :tonic-pc 6  :display "Gb" :alternative "F#"}
   :Db {:accidental -5 :tonic-pc 1  :display "Db" :alternative "C#"}
   :Ab {:accidental -4 :tonic-pc 8  :display "Ab" :alternative "G#"}
   :Eb {:accidental -3 :tonic-pc 3  :display "Eb" :alternative "D#"}
   :Bb {:accidental -2 :tonic-pc 10 :display "Bb" :alternative "A#"}
   :F  {:accidental -1 :tonic-pc 5  :display "F"}})

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  (get note-name->pitch-class "c")       ;; => 0
  (:mf dynamics)                          ;; => 60
  (get articulations :staccato)           ;; => {:duration 0.4, :dynamic 0}
  (get articulation-shorthand "-.")       ;; => :staccato
  (get-in drums [38 :abbr])               ;; => "sna"
  (:pan midi-cc)                          ;; => 10
  (get-in scales [:dorian :intervals])    ;; => [0 2 3 5 7 9 10]
  (:tonic-pc (signatures :D))                   ;; => 2
  (context-key-default :T)                ;; => 92  (from ranges)
  (context-key-default :volume)           ;; => 50.0 (from ranges)
  (volume->midi 50.0)                     ;; => 64
  )