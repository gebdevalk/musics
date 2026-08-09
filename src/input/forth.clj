(ns input.forth
  (:require [clojure.string :as str])
  (:gen-class))

;; =====================================================================
;; A small Forth, hosted in Clojure.
;;
;; Supports:
;; : NAME ... ; colon definitions (incl. self-recursion
;; by using the word's own name)
;; IF ... ELSE ... THEN compiled as relative branches
;; DO ... LOOP / +LOOP with I and J loop indices
;; BEGIN ... UNTIL
;; BEGIN ... WHILE ... REPEAT
;; { a b c } named locals, bound from the stack
;; (leftmost = deepest, rightmost = top,
;; gforth-style)
;; S" ..." real string literals (pushed as Clojure
;; strings, no address/length games)
;; ." ..." print a string immediately
;; CREATE / DOES> classic defining-word mechanism
;; ' NAME and EXECUTE tick pushes an execution token,
;; EXECUTE runs it
;; VARIABLE, @, !, , simple single-cell storage
;; + - * / MOD DUP DROP SWAP OVER ROT < > = 0= AND OR
;; . .S CR EMIT TYPE DEPTH
;;
;; Design: colon-definitions compile to a flat vector of "ops". Control
;; structures (IF/DO/BEGIN/...) compile to branch ops using OFFSETS
;; relative to the branching op's own position, so blocks can be spliced
;; together without knowing their final absolute address. A tiny
;; index-based VM (`run-body`) then walks the ops vector, jumping via
;; pc += offset.
;; =====================================================================

;; ---------------------------------------------------------------------
;; Tokenizer
;; ---------------------------------------------------------------------
;; Produces a seq of tokens. A token is either a plain word string
;; ("DUP", "3", "IF", ...) or a 2-vector [:str "..."] / [:print-str "..."].

(defn tokenize [^String source]
  (let [len (long (count source))]
    (loop [i (long 0) tokens []]
      (if (>= i len)
        tokens
        (let [c (.charAt source i)]
          (cond
            (Character/isWhitespace c)
            (recur (inc i) tokens)

            ;; ( comment )
            (= c \()
            (let [end (str/index-of source ")" i)]
              (recur (long (if end (inc end) len)) tokens))

            ;; \ line comment
            (= c \\)
            (let [end (str/index-of source "\n" i)]
              (recur (long (or end len)) tokens))

            ;; S" string literal ...."
            (and (= c \S) (< (inc i) len) (= (.charAt source (inc i)) \"))
            (let [start (+ i 2)
                  start (if (and (< start len) (= (.charAt source start) \space)) (inc start) start)
                  end (str/index-of source "\"" start)]
              (when-not end (throw (ex-info "Unterminated S\" string" {})))
              (recur (long (inc end)) (conj tokens [:str (subs source start end)])))

            ;; ." print-string ...."
            (and (= c \.) (< (inc i) len) (= (.charAt source (inc i)) \"))
            (let [start (+ i 2)
                  start (if (and (< start len) (= (.charAt source start) \space)) (inc start) start)
                  end (str/index-of source "\"" start)]
              (when-not end (throw (ex-info "Unterminated .\" string" {})))
              (recur (long (inc end)) (conj tokens [:print-str (subs source start end)])))

            :else
            (let [end (long (loop [j i]
                        (if (or (>= j len) (Character/isWhitespace (.charAt source j)))
                          j
                          (recur (inc j)))))]
              (recur end (conj tokens (subs source i end))))))))))

;; ---------------------------------------------------------------------
;; Runtime state
;; ---------------------------------------------------------------------
;; ctx is a plain map:
;; :stack atom of a vector (top = end) -- shared
;; :dict atom of {name -> word-entry} -- shared
;; :toks atom of the remaining token stream -- shared
;; :last-create atom holding the name most recently CREATEd -- shared
;; :locals atom of {name -> value} -- fresh per word-call
;; :loops atom of a list of {:index n :limit n} frames
;; -- fresh per word-call, innermost loop first
;;
;; word-entry is one of:
;; {:type :primitive :fn (fn [ctx] ...)}
;; {:type :colon :body [ops...]}
;; {:type :created :cell (atom v) :does-body [ops...] | nil}

(defn next-tok! [toks-atom]
  (let [t (first @toks-atom)]
    (swap! toks-atom rest)
    t))

(defn push! [ctx v] (swap! (:stack ctx) conj v))

(defn pop-val! [ctx]
  (let [s @(:stack ctx)]
    (when (empty? s) (throw (ex-info "Stack underflow" {})))
    (let [v (peek s)]
      (swap! (:stack ctx) pop)
      v)))

(defn parse-number [s]
  (try (Long/parseLong s)
       (catch Exception _
         (try (Double/parseDouble s)
              (catch Exception _ nil)))))

;; ---------------------------------------------------------------------
;; Primitive word bodies that need special runtime access (reading the
;; live token stream, touching :last-create). Everything else is a
;; plain one-liner down in `make-dict`.
;; ---------------------------------------------------------------------

(defn prim-create [ctx]
  (let [name (next-tok! (:toks ctx))]
    (when-not name (throw (ex-info "CREATE expects a name" {})))
    (swap! (:dict ctx) assoc name {:type :created :cell (atom nil) :does-body nil})
    (reset! (:last-create ctx) name)))

(defn prim-variable [ctx]
  (let [name (next-tok! (:toks ctx))]
    (when-not name (throw (ex-info "VARIABLE expects a name" {})))
    (swap! (:dict ctx) assoc name {:type :created :cell (atom 0) :does-body nil})))

(defn prim-comma [ctx]
  (let [v (pop-val! ctx)
        name @(:last-create ctx)]
    (when-not name (throw (ex-info ", used with no prior CREATE" {})))
    (reset! (:cell (get @(:dict ctx) name)) v)))

;; ---------------------------------------------------------------------
;; Dictionary of primitives
;; ---------------------------------------------------------------------

(defmacro def-prim [nm argv & body]
  `{~nm {:type :primitive :fn (fn ~argv ~@body)}})

(defn make-dict []
  (atom
    (merge
      (def-prim "+" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (+ a b))))
      (def-prim "-" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (- a b))))
      (def-prim "*" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (* a b))))
      (def-prim "/" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (quot a b))))
      (def-prim "MOD" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (mod a b))))
      (def-prim "DUP" [ctx] (let [a (pop-val! ctx)] (push! ctx a) (push! ctx a)))
      (def-prim "DROP" [ctx] (pop-val! ctx))
      (def-prim "SWAP" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx b) (push! ctx a)))
      (def-prim "OVER" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx a) (push! ctx b) (push! ctx a)))
      (def-prim "ROT" [ctx] (let [c (pop-val! ctx) b (pop-val! ctx) a (pop-val! ctx)] (push! ctx b) (push! ctx c) (push! ctx a)))
      (def-prim "<" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (if (< a b) -1 0))))
      (def-prim ">" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (if (> a b) -1 0))))
      (def-prim "=" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (if (= a b) -1 0))))
      (def-prim "0=" [ctx] (push! ctx (if (= 0 (pop-val! ctx)) -1 0)))
      (def-prim "AND" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (bit-and a b))))
      (def-prim "OR" [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (bit-or a b))))
      (def-prim "." [ctx] (print (pop-val! ctx)) (print " ") (flush))
      (def-prim ".S" [ctx] (print @(:stack ctx)) (print " ") (flush))
      (def-prim "CR" [ctx] (println))
      (def-prim "EMIT" [ctx] (print (char (pop-val! ctx))) (flush))
      (def-prim "TYPE" [ctx] (print (pop-val! ctx)) (flush))
      (def-prim "DEPTH" [ctx] (push! ctx (count @(:stack ctx))))
      (def-prim "I" [ctx] (push! ctx (:index (first @(:loops ctx)))))
      (def-prim "J" [ctx] (push! ctx (:index (second @(:loops ctx)))))
      (def-prim "@" [ctx] (push! ctx @(pop-val! ctx)))
      (def-prim "!" [ctx] (let [addr (pop-val! ctx) v (pop-val! ctx)] (reset! addr v)))
      (def-prim "CREATE" [ctx] (prim-create ctx))
      (def-prim "VARIABLE" [ctx] (prim-variable ctx))
      (def-prim "," [ctx] (prim-comma ctx)))))

;; ---------------------------------------------------------------------
;; Compiler: token stream -> flat vector of ops
;; ---------------------------------------------------------------------

(declare compile-block)

;; `known-locals` is an atom of a set of names, shared across the whole
;; definition being compiled right now. `{ a b c }` adds to it at compile
;; time so later references to `a`/`b`/`c` in the same definition resolve
;; to :local ops instead of dictionary calls.

(defn compile-word [toks t dict defining-name known-locals]
  (case t
    "IF"
    (let [[then-ops term] (compile-block toks dict defining-name known-locals #{"ELSE" "THEN"})]
      (when (nil? term) (throw (ex-info "IF without THEN" {})))
      (if (= term "ELSE")
        (let [[else-ops _] (compile-block toks dict defining-name known-locals #{"THEN"})]
          (vec (concat
                 [{:op :branch0 :offset (+ (count then-ops) 2)}]
                 then-ops
                 [{:op :branch :offset (inc (count else-ops))}]
                 else-ops)))
        (vec (concat
               [{:op :branch0 :offset (inc (count then-ops))}]
               then-ops))))

    "DO"
    (let [[body term] (compile-block toks dict defining-name known-locals #{"LOOP" "+LOOP"})]
      (when (nil? term) (throw (ex-info "DO without LOOP" {})))
      (vec (concat [{:op :do-init}]
                   body
                   [{:op (if (= term "+LOOP") :plusloop :loop)
                     :offset (- (count body))}])))

    "BEGIN"
    (let [[body1 term] (compile-block toks dict defining-name known-locals #{"UNTIL" "WHILE"})]
      (when (nil? term) (throw (ex-info "BEGIN without UNTIL/WHILE" {})))
      (if (= term "UNTIL")
        (vec (concat body1 [{:op :branch0 :offset (- (count body1))}]))
        (let [[body2 term2] (compile-block toks dict defining-name known-locals #{"REPEAT"})]
          (when (nil? term2) (throw (ex-info "WHILE without REPEAT" {})))
          (vec (concat
                 body1
                 [{:op :branch0 :offset (+ (count body2) 2)}]
                 body2
                 [{:op :branch :offset (- (+ (count body1) 1 (count body2)))}])))))

    "{"
    (let [names (loop [ns []]
                  (let [tk (next-tok! toks)]
                    (cond
                      (nil? tk) (throw (ex-info "Unterminated locals block {" {}))
                      (= tk "}") ns
                      (string? tk) (recur (conj ns tk))
                      :else (throw (ex-info "Bad token inside locals block" {})))))]
      (swap! known-locals into names)
      [{:op :locals-bind :names names}])

    ;; default: a local reference, a call to an existing/self word, or a
    ;; numeric literal -- checked in that order.
    (cond
      (contains? @known-locals t) [{:op :local :name t}]
      (or (= t defining-name) (contains? @dict t)) [{:op :call :name t}]
      :else
      (if-let [n (parse-number t)]
        [{:op :lit :value n}]
        (throw (ex-info (str "Unknown word during compile: " t) {}))))))

(defn compile-block [toks dict defining-name known-locals stop-words]
  (loop [ops []]
    (let [t (next-tok! toks)]
      (cond
        (nil? t) [ops nil]
        (and (string? t) (contains? stop-words t)) [ops t]
        (vector? t)
        (case (first t)
          :str (recur (conj ops {:op :lit :value (second t)}))
          :print-str (recur (conj ops {:op :print-str :value (second t)})))
        :else
        (recur (into ops (compile-word toks t dict defining-name known-locals)))))))

(defn compile-definition [ctx]
  (let [toks (:toks ctx)
        dict (:dict ctx)
        name (next-tok! toks)
        known-locals (atom #{})]
    (when-not name (throw (ex-info "Expected a name after :" {})))
    (let [[ops term] (compile-block toks dict name known-locals #{";" "DOES>"})]
      (when (nil? term) (throw (ex-info (str "Unterminated definition: " name) {})))
      (if (= term "DOES>")
        (let [[does-ops term2] (compile-block toks dict name (atom #{}) #{";"})]
          (when (nil? term2) (throw (ex-info (str "Unterminated DOES> body: " name) {})))
          (swap! dict assoc name
                 {:type :colon
                  :body (conj (vec ops) {:op :does-install :body (vec does-ops)})}))
        (swap! dict assoc name {:type :colon :body (vec ops)})))))

;; ---------------------------------------------------------------------
;; VM: execute a compiled ops vector
;; ---------------------------------------------------------------------

(declare execute-entry)

(defn execute-name [name ctx]
  (if-let [entry (get @(:dict ctx) name)]
    (execute-entry entry ctx)
    (throw (ex-info (str "Undefined word: " name) {}))))

(defn bind-locals [ctx names]
  (loop [n (count names) acc '()]
    (if (zero? n)
      (zipmap names acc)
      (recur (dec n) (cons (pop-val! ctx) acc)))))

(defn run-body [ops ctx0]
  (let [ctx (assoc ctx0 :locals (atom {}) :loops (atom '()))
        n (long (count ops))]
    (loop [pc (long 0)]
      (when (< pc n)
        (let [instr (nth ops pc)]
          (case (:op instr)
            :lit (do (push! ctx (:value instr)) (recur (inc pc)))
            :print-str (do (print (:value instr)) (flush) (recur (inc pc)))
            :call (do (execute-name (:name instr) ctx) (recur (inc pc)))
            :branch (recur (+ pc (long (:offset instr))))
            :branch0 (let [v (pop-val! ctx)]
                       (if (= v 0)
                         (recur (+ pc (long (:offset instr))))
                         (recur (inc pc))))
            :do-init (let [start (pop-val! ctx) limit (pop-val! ctx)]
                       (swap! (:loops ctx) conj {:index start :limit limit})
                       (recur (inc pc)))
            :loop (let [{:keys [index limit]} (first @(:loops ctx))
                        new-index (inc index)]
                    (if (< new-index limit)
                      (do (swap! (:loops ctx) #(cons (assoc (first %) :index new-index) (rest %)))
                          (recur (+ pc (long (:offset instr)))))
                      (do (swap! (:loops ctx) rest)
                          (recur (inc pc)))))
            :plusloop (let [step (pop-val! ctx)
                            {:keys [index limit]} (first @(:loops ctx))
                            new-index (+ index step)]
                        (if (< new-index limit)
                          (do (swap! (:loops ctx) #(cons (assoc (first %) :index new-index) (rest %)))
                              (recur (+ pc (long (:offset instr)))))
                          (do (swap! (:loops ctx) rest)
                              (recur (inc pc)))))
            :locals-bind (do (reset! (:locals ctx) (bind-locals ctx (:names instr))) (recur (inc pc)))
            :local (do (push! ctx (get @(:locals ctx) (:name instr))) (recur (inc pc)))
            :does-install
            (let [name @(:last-create ctx)]
              (when-not name (throw (ex-info "DOES> used with no prior CREATE" {})))
              (swap! (:dict ctx) update name assoc :does-body (:body instr))
              (recur (inc pc)))
            (throw (ex-info (str "Unknown op: " (:op instr)) {}))))))))

(defn execute-entry [entry ctx]
  (case (:type entry)
    :primitive ((:fn entry) ctx)
    :colon (run-body (:body entry) ctx)
    :created (do (push! ctx (:cell entry))
                 (when-let [b (:does-body entry)] (run-body b ctx)))
    (throw (ex-info "Cannot execute this entry" {:entry entry}))))

;; ---------------------------------------------------------------------
;; Top level: interpret a stream of tokens
;; ---------------------------------------------------------------------

(defn interpret-token [t ctx]
  (cond
    (= t ":") (compile-definition ctx)
    (= t "'") (let [nm (next-tok! (:toks ctx))
                    entry (get @(:dict ctx) nm)]
                (when-not entry (throw (ex-info (str "Unknown word: " nm) {})))
                (push! ctx entry))
    (= t "EXECUTE") (execute-entry (pop-val! ctx) ctx)
    (vector? t)
    (case (first t)
      :str (push! ctx (second t))
      :print-str (do (print (second t)) (flush)))
    (string? t)
    (if-let [entry (get @(:dict ctx) t)]
      (execute-entry entry ctx)
      (if-let [n (parse-number t)]
        (push! ctx n)
        (throw (ex-info (str "Unknown word: " t) {}))))))

(defn interpret-all [ctx]
  (loop []
    (when-let [t (next-tok! (:toks ctx))]
      (interpret-token t ctx)
      (recur))))

(defn make-ctx []
  {:stack (atom [])
   :dict (make-dict)
   :toks (atom '())
   :last-create (atom nil)
   :locals (atom {})
   :loops (atom '())})

(defn feed! [ctx s]
  (swap! (:toks ctx) #(concat % (tokenize s))))

(defn run-string [ctx s]
  (feed! ctx s)
  (interpret-all ctx))

(defn -main [& _]
  (let [ctx (make-ctx)]
    (println "Small Forth in Clojure. Ctrl-D to exit.")
    (loop []
      (print "> ") (flush)
      (let [line (read-line)]
        (when line
          (try
            (run-string ctx line)
            (println " ok")
            (catch Exception e
              (println "Error:" (.getMessage e))))
          (recur))))))
