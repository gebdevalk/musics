;; snippets/parser_test.clj
;; Paste into a REPL (one section at a time) to test the reader.

;; ── Load namespaces ──────────────────────────────────────────
(require '[input.reader.parser.music-parser :as p]
         '[core.domain.music-domain :as d])

;; ── basic parse ──────────────────────────────────────────────
(p/parse "c4 d4 e4 r4 f4")
;; => {:score ... :tokens (
;;   #<Leaf c4 [60] 1/4>
;;   #<Leaf d4 [62] 1/4>
;;   #<Leaf e4 [64] 1/4>
;;   #<Rest r4 1/4>
;;   #<Leaf f4 [65] 1/4>)}

;; ── duration inheritance ─────────────────────────────────────
(p/parse "c2 d e f")
;; d, e, f all inherit 1/2 from c2

;; ── relative pitch (diatonic) ────────────────────────────────
(p/parse "c d e f g a b")
;; => 60 62 64 65 67 69 71  (each climbs by diatonic step)

;; ── absolute pitch ───────────────────────────────────────────
(p/parse "C4 D4 E4")
;; => 60 62 64  (octave digit = absolute)

;; ── mixed absolute + relative ────────────────────────────────
(p/parse "C4 D4 c d e")
;; after D4(62), c → 60 (d→c = -2), then d→62, e→64

;; ── accidentals ──────────────────────────────────────────────
(p/parse "c eb f# g")
;; c=60, eb=63, f#=66, g=67

;; ── chord + carryover ────────────────────────────────────────
(p/parse "<c e g>2 a b")
;; chord [60 64 67], then a=69 (g→a +2), b=71

;; ── with instructions ────────────────────────────────────────
(p/parse "!mf !tempo=100 c4 d4 e4 !ff <c e g>1")

;; ── tokenize only (lexer) ────────────────────────────────────
(p/tokenize "c4 <c e g>2 r4 !mf")
;; => ({:type :NOTE :value "c4"} {:type :CHORD :value "<c e g>2"} ...)

;; ── parse → play (needs midi-live) ───────────────────────────
#_(do
    (require '[output.midi.midi-live :as live])
    (let [notes  (filter d/leaf? (:tokens (p/parse "c4 d4 e4 f4 g4 a4 b4 c5")))
          rcv    (live/open-receiver)]
      (doseq [leaf notes]
        (live/note-on rcv 0 (first (:pitches leaf)) 80)
        (Thread/sleep 250)
        (live/note-off rcv 0 (first (:pitches leaf))))))
