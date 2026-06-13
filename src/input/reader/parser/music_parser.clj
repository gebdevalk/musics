;; music_parser.clj
;; Clojure port of the pymusics input parser.
;; Tokenizes the LilyPond-like text notation using regex patterns
;; ported from regex.py, then builds domain objects using
;; core.domain.music-domain records (Leaf, Rest, Drum, Composite, etc.).
;;
;; Usage: (parse text)
;;   Returns {:score Composite, :tokens [Part ...]} map.

(ns input.reader.parser.music-parser
  (:require [clojure.string :as str]
            [core.domain.music-domain :as d]
            [common.elements.music-elements :as el]
            [common.data.music-data :as data]
            [common.tools.music-tools :as tools]))

;; ID generation
(def ^:private id-counters (atom {}))
(defn- next-id [type-kw]
  (let [k (name type-kw)]
    (swap! id-counters update k (fnil inc 0))
    (str k "." (get @id-counters k))))
(defn- reset-ids! [] (reset! id-counters {}))

;; ============================================================
;; Regex patterns (ported from regex.py)
;; ============================================================

(def ^:private NAME          #"[a-zA-Z][a-zA-Z0-9_]*")
(def ^:private EQUALS        #"\s*=\s*")
(def ^:private INT           #"[0-9]+")
(def ^:private FLOAT         #"[0-9]+\.[0-9]+")
(def ^:private STRING        #"\"[^\"]*\"")

(def ^:private PITCH_NAME    #"[A-G][1-8]|[a-g]|p")
(def ^:private ACCIDENTAL    #"[b#]{0,2}|n+")
(def ^:private OCTAVE        #"[']*")

(def ^:private DURATION      #"longa|breve|\d{1,3}\.*")
(def ^:private ARTICULATION  #"-[.>^_!+]")
(def ^:private TIE            #"~")
(def ^:private OPERATION    #"[+\-*/]\s*\d+(?:/\d+)?")

;; --- Whole-unit patterns ---
(def ^:private PITCH_UNIT
  (re-pattern (str "(?:" PITCH_NAME ")(?:" ACCIDENTAL ")(?:" OCTAVE ")")))

(def ^:private CHORD_CORE
  (re-pattern (str "<(?!<)(" PITCH_UNIT "(?:\\s+" PITCH_UNIT ")*?)>")))

(def ^:private MODIFIER
  (re-pattern (str "\\\\(?:" NAME ")(?:" EQUALS "(?:" FLOAT "|" INT "|" NAME "|" STRING "))?")))

(def ^:private MODIFIERS
  (re-pattern (str "(?:" MODIFIER ")*")))

;; --- Main leaf patterns (first-pass matching) ---

;; NOTE: (PITCH_UNIT) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
(def ^:private NOTE_RE
  (re-pattern (str "(" PITCH_UNIT ")(" DURATION ")?(" ARTICULATION ")?(" MODIFIERS ")?(" TIE ")?")))

;; CHORD: (CHORD_CORE) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
(def ^:private CHORD_RE
  (re-pattern (str CHORD_CORE "(" DURATION ")?(" ARTICULATION ")?(" MODIFIERS ")?(" TIE ")?")))

;; REST: r(DURATION)?
(def ^:private REST_RE  #"r(longa|breve|\d{1,3}\.*)?")

;; DRUM: x (DURATION)? (NAME|INT)
(def ^:private DRUM_RE
  (re-pattern (str "x(" DURATION ")?(" NAME "|" INT ")")))

;; --- Instruction patterns ---
(def ^:private BANG_CONST_RE
  (re-pattern (str "!\\s*(" NAME ")")))

(def ^:private ASSIGN_INT_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" INT ")")))

(def ^:private ASSIGN_FLOAT_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" FLOAT ")")))

(def ^:private ASSIGN_CONST_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" NAME ")")))

(def ^:private ASSIGN_STRING_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" STRING ")")))

;; --- Constant keywords (ported from lexer.py CONST_KEYWORD) ---
(def ^:private CONST_KEYWORD_RE
  (re-pattern
   (str "!silence|!pppp|!ppp|!pp|!p|!mp|!mf|!ffff|!fff|!ff|!f"
        "|!cresc|!decresc|!dim|!sfz|!fp"
        "|!left|!center|!right|!near|!far"
        "|!stageLeft|!stageCenter|!stageRight"
        "|!largo|!lento|!adagio|!andante|!moderato|!allegro"
        "|!vivace|!presto|!prestissimo"
        "|!rit|!acc|!rubato"
        "|!straight|!swing|!shuffle"
        "|!jazz|!latin|!rock|!classical|!swingFeel"
        "|!DC|!DS|!Segno|!Coda|!ToCoda|!Fine"
        "|!DC_al_Fine|!DS_al_Coda"
        "|!repeatStart|!repeatEnd"
        "|!\\(|!\\)"
        "|!pedOn|!pedOff|!unaCorda|!treCorde|!sostPed"
        "|!commonTime|!cutTime"
        "|!key:[A-G][b#]?")))

;; --- Composite delimiters ---
(def ^:private SEQ_OPEN      #"\[")
(def ^:private SEQ_CLOSE     #"\]")
(def ^:private PAR_OPEN      #"<<")
(def ^:private PAR_CLOSE     #">>")
(def ^:private LIST_OPEN     #"\(")
(def ^:private LIST_CLOSE    #"\)")
(def ^:private ALGO_OPEN     #"@\(")
(def ^:private ALGO_CLOSE    #"\)")
(def ^:private DATA_OPEN     #"'\[")
(def ^:private DATA_CLOSE    #"\]'")

;; --- Single-quote list: '( ... ) ---
(def ^:private QUOTE_OPEN    #"'\(")
(def ^:private QUOTE_CLOSE   #"\)")

;; ============================================================
;; Token classification — vector-of-pairs + some (replaces cond)
;; ============================================================

(def ^:private token-classifiers
  "Ordered priority list of [regex-pattern, token-type-kw] pairs.
   First match wins. Tested in sequence — assignments first,
   then delimiters, then bang constants, then leaf patterns,
   then primitives, then bare names."
  [[ASSIGN_STRING_RE  :ASSIGN_STRING]
   [ASSIGN_FLOAT_RE   :ASSIGN_FLOAT]
   [ASSIGN_INT_RE     :ASSIGN_INT]
   [ASSIGN_CONST_RE   :ASSIGN_CONST]
   [SEQ_OPEN          :SEQ]
   [SEQ_CLOSE         :SEQ_CLOSE]
   [PAR_OPEN          :PAR]
   [PAR_CLOSE         :PAR_CLOSE]
   [ALGO_OPEN         :ALGO]
   [DATA_OPEN         :DATA]
   [DATA_CLOSE        :DATA_CLOSE]
   [LIST_OPEN         :LIST]
   [LIST_CLOSE        :LIST_CLOSE]
   [QUOTE_OPEN        :QUOTE]
   [QUOTE_CLOSE       :QUOTE_CLOSE]
   [CONST_KEYWORD_RE              :BANG_CONST
            (let [parsed (parse-bang-const value)
                  const  (:const parsed)
                  t      (double @parse-time)]
              (if-let [vol (get {:pppp 10.0 :ppp 20.0 :pp 30.0 :p 40.0 :mp 50.0
                                 :mf 60.0 :f 75.0 :ff 90.0 :fff 100.0 :ffff 110.0
                                 :sfz 95.0 :fp 70.0 :silence 0.0} const)]
                (do (d/ctx-append current-ctx :volume t vol :fixed)
                    (recur (rest remaining) stack results))
                (case const
                  (:cresc :decresc :dim)
                  (let [current-vol (or (d/ctx-value current-ctx :volume t) 50.0)
                        delta (if (= const :cresc) 20 -20)
                        new-vol (max 5.0 (min 110.0 (+ current-vol delta)))]
                    (d/ctx-append current-ctx :volume t new-vol
                                  (if (= const :cresc) :lin-up :lin-down))
                    (recur (rest remaining) stack results))
                  (recur (rest remaining) stack (conj results parsed)))))

            (:ASSIGN_INT :ASSIGN_FLOAT :ASSIGN_CONST :ASSIGN_STRING)
            (let [parsed (parse-assignment value)]
              (recur (rest remaining) stack (conj results parsed)))

;; --- Leaves: produce domain records ---
            :NOTE
            (let [result (if-let [m (re-matches NOTE_RE value)]
                           (let [pitch        (nth m 1)
                                 duration     (nth m 2)
                                 articulation (nth m 3)
                                 modifiers    (nth m 4)
                                 tie          (nth m 5)]
                             (d/leaf value
                                     current-ctx
                                     (resolve-duration duration)
                                     [(resolve-pitch pitch)]
                                     (resolve-articulation articulation)
                                      (:dynamic (resolve-articulation articulation))
                                     (parse-modifiers modifiers)
                                     (boolean tie)))
                           {:type :parse-error :value value})]
              (do (swap! parse-time + (or (:duration result) 1/4))
               (recur (rest remaining) (do (d/composite-append (peek stack) result) stack) results)))

            :CHORD
            (let [result (if-let [m (re-matches CHORD_RE value)]
                           (let [chord-core   (nth m 1)
                                 duration     (nth m 2)
                                 articulation (nth m 3)
                                 modifiers    (nth m 4)
                                 tie          (nth m 5)]
                             (d/leaf value
                                     current-ctx
                                     (resolve-duration duration)
                                     (let [inner (str/replace chord-core #"^<|>$" "")]
                                      (keep resolve-pitch (str/split inner #"\s+")))
                                     (resolve-articulation articulation)
                                      (:dynamic (resolve-articulation articulation))
                                     (parse-modifiers modifiers)
                                     (boolean tie)))
                           {:type :parse-error :value value})]
              (do (swap! parse-time + (or (:duration result) 1/4))
               (recur (rest remaining) (do (d/composite-append (peek stack) result) stack) results)))

            :REST
            (let [m   (re-matches REST_RE value)
                  dur (when m (nth m 1))]
              (recur (rest remaining) stack
                     (conj results
                           (d/make-rest value
                                    current-ctx
                                    (resolve-duration dur)))))

            :DRUM
            (let [m    (re-matches DRUM_RE value)
                  dur  (when m (nth m 1))
                  prog (when m (nth m 2))]
              (recur (rest remaining) stack
                     (conj results
                           (d/drum value
                                   current-ctx
                                   (parse-duration dur)
                                   (when prog (Integer/parseInt prog))))
                                   (when prog (Integer/parseInt prog))))))

            ;; --- Primitives ---
            :OPERATION
            (recur (rest remaining)
                   (do (d/composite-append (peek stack) {:type :op :val value}) stack)
                   results)
            :INT
            (recur (rest remaining) stack
                   (conj results {:type :int :val (Integer/parseInt value)}) )
            :FLOAT
            (recur (rest remaining) stack
                   (conj results {:type :float :val (Double/parseDouble value)}) )
            :TYPE
            (recur (rest remaining) stack
                   (conj results {:type :type-ref :val value}) )
            ;; --- Fallback ---
            (recur (rest remaining) stack
                   (conj results {:type :unknown :val value}))))
        ;; All tokens consumed — pop remaining stack levels
        (let [final (reduce (fn [[stk rslts] _]
                              (let [[ns result] (pop-and-collect stk)]
                                [ns (if result (conj rslts result) rslts)]))
                            [stack results]
                            (range (dec (count stack))))]
          (let [score (first (first final))]
           {:score  score
            :tokens (vec (concat (second final) (d/composite-children score)))}))))))

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
  (def t1 (tokenize "c4 d4 e4 r4"))
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
  (run! #(println (str (name (:type %)) \tab (:value %))) (tokenize text))
  )