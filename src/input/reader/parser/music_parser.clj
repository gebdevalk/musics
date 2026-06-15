;; music_parser.clj
;; Clojure port of the pymusics input parser.
;; Structural parsing: composite tree building, instruction handling,
;; and the main parse loop dispatching across lexer and leaf-parser.
;;
;; Usage: (parse text)
;;   Returns {:score Composite, :tokens [Part ...]} map.

(ns input.reader.parser.music-parser
  (:require [clojure.string :as str]
            [core.domain.music-domain :as d]
            [common.data.defaults :as defaults]
            [input.reader.parser.lexer :as lex]
            [input.reader.parser.leaf-parser :as leaf]))

;; Re-exports for backward compatibility
(def resolve-articulation leaf/resolve-articulation)
(def tokenize lex/tokenize)

;; ============================================================
;; Duration helpers
;; ============================================================

(defn parse-duration
  "Convert a duration string ('4', '2.', '8..') to a rational.
   longa = 4, breve = 2, otherwise n = 1/n with dots adding half each."
  [s]
  (cond
    (nil? s) nil
    (= s "longa") 4
    (= s "breve") 2
    :else
    (let [dots (count (take-while #{\.} (str/replace s #"[^.]+" "")))
          n    (Integer/parseInt (str/replace s #"\\.+" ""))]
      (loop [val (/ 1 n)
             i dots]
        (if (zero? i)
          val
          (recur (+ val (/ val 2)) (dec i)))))))

;; ============================================================
;; Modifier parsing (ported from regex.py parse_modifiers)
;; ============================================================

(defn parse-modifiers
  "Split '\\vol=80\\tempo=120' into [[key val] ...] pairs."
  [s]
  (when s
    (for [m (re-seq lex/MODIFIER_RE_SINGLE s)]
      (let [key (or (nth m 1) "")
            val (nth m 2)]
        [key val]))))

;; ============================================================
;; Instruction parsing
;; ============================================================

(defn parse-bang-const
  "Parse !mf / !cresc etc. Returns a context map with :instruction and :const."
  [s]
  (let [kw (keyword (subs (str/replace s #"\s+" "") 1))]
    {:type :instruction :const kw :raw s}))

(defn parse-assignment
  "Parse !art=80 / !pan=0.0 / !vol=mf / !timbre=\"piano\"
   into {:type :assignment :key :art :val 80 :raw ...}"
  [s]
  (when-let [m (re-matches lex/ASSIGN_RE s)]
    (let [key     (keyword (nth m 1))
          raw-val (nth m 2)
          val     (cond
                    (re-matches lex/INT raw-val)    (Integer/parseInt raw-val)
                    (re-matches lex/FLOAT raw-val)  (Double/parseDouble raw-val)
                    (re-matches lex/STRING raw-val) (subs raw-val 1 (dec (count raw-val)))
                    :else                           (keyword raw-val))]
      {:type :assignment :key key :val val :raw s})))

;; ============================================================
;; Composite stack management
;; ============================================================

(defn push-container
  "Create a new container inheriting the parent's context.
   Pushes onto the stack.
   - :SEQ, :PAR, :ALGO, :DATA, :QUOTE → Composite
   - :LIST → Transient"
  [stack container-type id]
  (let [parent      (peek stack)
        parent-ctx  (when parent (:context parent))
        ctx         (d/context parent-ctx)]
    (if (= container-type :LIST)
      (conj stack (d/transient* container-type id ctx))
      (conj stack (d/composite container-type id ctx)))))

(defn pop-and-collect
  "Pop the top container and add it (or its children) to the parent.
   Returns [new-stack popped-result] — result is nil when parent exists
   (container absorbed), or the popped container when it's the root."
  [stack]
  (let [current    (peek stack)
        rest-stack (pop stack)
        parent     (peek rest-stack)]
    (if (nil? parent)
      [rest-stack current]
      (do (if (d/transient? current)
            (doseq [child (d/transient-children current)]
              (d/composite-append parent child))
            (d/composite-append parent current))
          [rest-stack nil]))))

;; ============================================================
;; Main parser dispatch
;; ============================================================

(defn parse
  "Parse a text string into domain objects.
   Returns {:score Composite, :tokens [Part ...]}.

   Example:
     (parse \"[c4 d4 e4] f4 g4\")"
  [text]
  (let [tokens     (lex/tokenize text)
        init-ctx   (d/context-root (defaults/root-defaults))
        last-pitch (atom nil)]
    (loop [remaining tokens
           stack      (vector (d/make-score init-ctx))
           results    []]
      (if-let [{:keys [type value]} (first remaining)]
        (let [current-ctx (:context (peek stack))]
          (case type
            ;; --- Composite openers ---
            (:SEQ :PAR :LIST :ALGO :DATA :QUOTE)
            (recur (rest remaining)
                   (push-container stack type nil)
                   results)

            ;; --- Composite closers ---
            (:SEQ_CLOSE :PAR_CLOSE :LIST_CLOSE :DATA_CLOSE :QUOTE_CLOSE)
            (let [[new-stack result] (pop-and-collect stack)]
              (if result
                (recur (rest remaining) new-stack (conj results result))
                (recur (rest remaining) new-stack results)))

            ;; --- String ID: update current container's ID ---
            :STRING
            (let [id      (subs value 1 (dec (count value)))
                  current (peek stack)
                  updated (d/mutate current :id id)]
              (recur (rest remaining)
                     (conj (pop stack) updated)
                     (conj results updated)))

            ;; --- Instructions ---
            :BANG_CONST
            (let [parsed (parse-bang-const value)]
              (recur (rest remaining) stack (conj results parsed)))

            (:ASSIGN_INT :ASSIGN_FLOAT :ASSIGN_CONST :ASSIGN_STRING)
            (let [parsed (parse-assignment value)]
              (recur (rest remaining) stack (conj results parsed)))

            ;; --- Leaves: produce domain records ---
            :NOTE
            (let [m      (re-matches lex/NOTE_RE value)
                  result (if m
                           (let [pitch-str    (nth m 1)
                                 duration     (nth m 2)
                                 articulation (nth m 3)
                                 modifiers    (nth m 4)
                                 tie          (nth m 5)
                                 art          (leaf/resolve-articulation articulation)
                                 pitch-tuple  (leaf/parse-pitch pitch-str)
                                 [midi new-last]
                                 (if pitch-tuple
                                   (leaf/resolve-pitch pitch-tuple @last-pitch)
                                   [nil @last-pitch])]
                             (reset! last-pitch new-last)
                             [(d/leaf value
                                      (or current-ctx (d/context))
                                      (parse-duration duration)
                                      (if midi [midi] [])
                                      art
                                      (when (map? art) (:dynamic art))
                                      (parse-modifiers modifiers)
                                      (boolean tie))
                              new-last])
                           [{:type :parse-error :value value} @last-pitch])]
              (let [obj (first result)]
                (when (d/part? obj)
                  (d/composite-append (peek stack) obj))
                (recur (rest remaining) stack
                       (conj results obj))))

            :CHORD
            (let [m      (re-matches lex/CHORD_RE value)
                  result (if m
                           (let [chord-core   (nth m 1)
                                 duration     (nth m 2)
                                 articulation (nth m 3)
                                 modifiers    (nth m 4)
                                 tie          (nth m 5)
                                 art          (leaf/resolve-articulation articulation)
                                 pitch-tuples (leaf/parse-pitches chord-core)
                                 [midis new-last]
                                 (leaf/resolve-pitches-seq pitch-tuples @last-pitch)]
                             (reset! last-pitch new-last)
                             [(d/leaf value
                                      (or current-ctx (d/context))
                                      (parse-duration duration)
                                      (vec midis)
                                      art
                                      (when (map? art) (:dynamic art))
                                      (parse-modifiers modifiers)
                                      (boolean tie))
                              new-last])
                           [{:type :parse-error :value value} @last-pitch])]
              (let [obj (first result)]
                (when (d/part? obj)
                  (d/composite-append (peek stack) obj))
                (recur (rest remaining) stack
                       (conj results obj))))

            :REST
            (let [m   (re-matches lex/REST_RE value)
                  dur (when m (nth m 1))
                  obj (d/rest* value
                               (or current-ctx (d/context))
                               (parse-duration dur))]
              (d/composite-append (peek stack) obj)
              (recur (rest remaining) stack
                     (conj results obj)))

            :DRUM
            (let [m    (re-matches lex/DRUM_RE value)
                  dur  (when m (nth m 1))
                  prog (when m (nth m 2))
                  obj  (d/drum value
                               (or current-ctx (d/context))
                               (parse-duration dur)
                               (when prog (Integer/parseInt prog)))]
              (d/composite-append (peek stack) obj)
              (recur (rest remaining) stack
                     (conj results obj)))

            ;; --- Primitives ---
            :INT
            (recur (rest remaining) stack
                   (conj results {:type :int :val (Integer/parseInt value)}))

            :FLOAT
            (recur (rest remaining) stack
                   (conj results {:type :float :val (Double/parseDouble value)}))

            :TYPE
            (recur (rest remaining) stack
                   (conj results {:type :type-ref :val value}))

            ;; --- Fallback ---
            (recur (rest remaining) stack
                   (conj results {:type :unknown :val value}))))
        ;; All tokens consumed — pop remaining stack levels
        (let [final (reduce (fn [[stk rslts] _]
                              (let [[ns result] (pop-and-collect stk)]
                                [ns (if result (conj rslts result) rslts)]))
                            [stack results]
                            (range (dec (count stack))))]
          {:score  (peek (first final))
           :tokens (vec (second final))})))))

;; ============================================================
;; Pretty printing
;; ============================================================

(defn pp
  "Pretty-print the parse result."
  [parsed]
  (println "\n=== Parse Result ===")
  (let [score (:score parsed)]
    (println "Score:" (name (:type score)) "with"
             (d/composite-count score) "children"))
  (println "\nTokens:" (count (:tokens parsed)))
  (doseq [t (:tokens parsed)]
    (println " " (pr-str t))))

;; ============================================================
;; REPL test
;; ============================================================

(comment
  ;; Small test
  (def t1 (lex/tokenize "c4 d4 e4 r4"))
  (println "\n=== Tokens ===")
  (run! println t1)

  ;; Full test
  (def text
    "a#'4..-^\\prall~ !mf !art=80 !pan=0.0 !vol=mf !timbre=\"piano\" !cresc c4 <c e g>2-! r4 !f [ d' e' ] << f' g' >>
     << [ \"1\" a b c] [ \"2\" [ \"3\" d e f ] [g a' b]] [[c d e][ f g a][ b c d]] >>
     << \"composite\" [a b c] [e f g] >>
     '[ int 1 2 3 4']
     ( trans(9) A4 b c )
     @( reverse [ a b c d e f g ])")

  (def result (parse text))
  (pp result)

  ;; Just tokens
  (println "\n=== Tokens ===")
  (run! #(println (str (name (:type %)) \tab (:value %))) (lex/tokenize text))
  )
