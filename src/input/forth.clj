(ns input.forth
  (:require [clojure.string :as str]
            [core.domain.flat-domain :as d]
            [musics :as m])
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
;; Word lookup is case-insensitive (dup/Dup/DUP all resolve
;; the same) -- musics text (bare [...]/S"-wrapped/etc.)
;; keeps its own exact case, since that's semantically
;; significant there (C4 vs c4).
;;
;; Design: colon-definitions compile to a flat vector of "ops". Control
;; structures (IF/DO/BEGIN/...) compile to branch ops using OFFSETS
;; relative to the branching op's own position, so blocks can be spliced
;; together without knowing their final absolute address. A tiny
;; index-based VM (`run-body`) then walks the ops vector, jumping via
;; pc += offset.
;;
;; File layout: the Forth kernel (tokenizer through -main) comes first,
;; self-contained top to bottom; the musics.clj bridge (everything that
;; only exists because this Forth also hosts musics text -- bracket
;; recognition, the { collision fix, and every musics.clj fn wired as a
;; word) is one clearly-marked section at the end. The kernel still
;; calls a handful of bridge names directly (tokenize recognizes musics
;; brackets, make-dict wires musics-prims in) -- declared forward right
;; below so those calls compile, resolved for real once the bridge
;; section loads.
;; =====================================================================

(declare musics-open-at scan-musics-chunk locals-position? musics-prims)

;; ---------------------------------------------------------------------
;; Tokenizer
;; ---------------------------------------------------------------------
;; Produces a seq of tokens. A token is either a plain word string
;; ("DUP", "3", "IF", ...) or a 2-vector [:str "..."] / [:print-str "..."]
;; / [:musics "..."] (a whole balanced musics-text chunk -- see
;; musics-open-at/scan-musics-chunk/locals-position? in the musics.clj
;; bridge section at the end of this file for how that recognition
;; actually works; tokenize just calls them).

(defn tokenize [^String source]
  (let [len (long (count source))]
    (loop [i (long 0) tokens []]
      (if (>= i len)
        tokens
        (let [c (.charAt source i)]
          (cond
            (Character/isWhitespace c)
            (recur (inc i) tokens)

            ;; ( comment ) -- UNLESS source at i is specifically "(par ",
            ;; musics' own Parallel spelling (musics.ebnf's Composite
            ;; brackets, see musics-open-at below) -- the one member of
            ;; that ( -prefixed command family that's a valid whole
            ;; TopElement on its own (times/tuplet/transpose/repeat/
            ;; grace are always nested inside something else, so bare
            ;; ( at Forth's own top level staying a comment for THOSE is
            ;; unaffected, same as always), so it's the only one that
            ;; needs recognizing here specifically, matching how #{...}
            ;; (now (par ...)) was always bare-recognizable before.
            (and (= c \() (not (musics-open-at source i)))
            (let [end (str/index-of source ")" i)]
              (recur (long (if end (inc end) len)) tokens))

            ;; \ line comment
            (= c \\)
            (let [end (str/index-of source "\n" i)]
              (recur (long (or end len)) tokens))

            ;; S" string literal ...." -- case-insensitive opener (s" too)
            (and (or (= c \S) (= c \s)) (< (inc i) len) (= (.charAt source (inc i)) \"))
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

            ;; musics text, bare -- [...]/(par ...)/'[...]/{...}, no S"
            ;; wrapper needed at all. { alone is exempted immediately
            ;; after a defining word's own name
            ;; (: NAME { ...) -- that's gforth's own locals-block
            ;; position, not musics' Context; falls through to the
            ;; generic word-read below, same as it already did before
            ;; musics recognition existed, and compile-word's own "{"
            ;; case (below) still does all the real locals-binding work
            ;; unchanged. Every other opener, and { anywhere else, is
            ;; always musics -- unambiguous, since a locals block can
            ;; only ever open right there.
            (and (musics-open-at source i)
                 (not (and (= c \{) (locals-position? tokens))))
            (let [end (long (scan-musics-chunk source i))]
              (recur end (conj tokens [:musics (subs source i end)])))

            ;; Generic word (also numbers, and every symbolic primitive
            ;; like +/-/@/,/--) -- upper-cased here, and only here, so
            ;; word lookup is fully case-insensitive (dup/Dup/DUP all the
            ;; same dictionary entry, at both definition and call sites,
            ;; since every bare word -- colon-definition names, CREATE/
            ;; VARIABLE names, control-structure keywords IF/THEN/etc.,
            ;; locals names, ' NAME's target -- passes through this exact
            ;; branch). Never applied to [:str ...]/[:print-str ...]/
            ;; [:musics ...] tokens -- those are each produced by their
            ;; own earlier cond clause, matched and sliced before control
            ;; ever reaches here, so S" text, ." text, and musics text
            ;; (genuinely case-sensitive -- C4 and c4 are different
            ;; pitches, absolute vs. relative) all keep their exact
            ;; original case untouched.
            :else
            (let [end (long (loop [j i]
                        (if (or (>= j len) (Character/isWhitespace (.charAt source j)))
                          j
                          (recur (inc j)))))]
              (recur end (conj tokens (str/upper-case (subs source i end)))))))))))

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
    (swap! (:dict ctx) assoc name {:type :created :cell (atom 0) :does-body nil})
    (reset! (:last-create ctx) name)))

(defn prim-comma [ctx]
  (let [v (pop-val! ctx)
        name @(:last-create ctx)]
    (when-not name (throw (ex-info ", used with no prior CREATE" {})))
    (reset! (:cell (get @(:dict ctx) name)) v)))

;; ---------------------------------------------------------------------
;; Dictionary of primitives
;; ---------------------------------------------------------------------

(defn def-prim [nm f]
  {nm {:type :primitive :fn f}})

;; Thrown by BYE to unwind out of whichever repl loop is currently
;; running (run-repl-loop's own try/catch below is the only thing that
;; ever catches this -- confirmed no other try/catch exists anywhere
;; else in this file that could swallow it first) -- a plain signal,
;; not a real error, so it never prints "Error: ...". Deliberately not
;; also aliased as EXIT/QUIT -- both are real, DIFFERENT standard Forth
;; words (EXIT returns early from the current colon-definition's own
;; body; QUIT resets to the top-level prompt without leaving the
;; system) that this Forth doesn't implement yet; reusing either name
;; for "leave the interpreter" here would teach the wrong convention.
;; BYE is the one ANS Forth word that actually means this.
(defn- forth-exit! [] (throw (ex-info "forth-exit" {:forth/exit? true})))

(defn make-dict []
  (atom
    (merge
      (musics-prims)
      (def-prim "BYE" (fn [_ctx] (forth-exit!)))
      (def-prim "+" (fn [ctx] (let [b (pop-val! ctx)
                                    a (pop-val! ctx)] (push! ctx (+ a b)))))
      (def-prim "-" (fn [ctx] (let [b (pop-val! ctx)
                                    a (pop-val! ctx)] (push! ctx (- a b)))))
      (def-prim "*" (fn [ctx] (let [b (pop-val! ctx)
                                    a (pop-val! ctx)] (push! ctx (* a b)))))
      (def-prim "/" (fn [ctx] (let [b (pop-val! ctx)
                                    a (pop-val! ctx)] (push! ctx (quot a b)))))
      (def-prim "MOD" (fn [ctx] (let [b (pop-val! ctx)
                                      a (pop-val! ctx)] (push! ctx (mod a b)))))
      (def-prim "DUP" (fn [ctx] (let [a (pop-val! ctx)]
                                  (push! ctx a) (push! ctx a))))
      (def-prim "DROP" (fn [ctx] (pop-val! ctx)))
      (def-prim "SWAP" (fn [ctx] (let [b (pop-val! ctx)
                                       a (pop-val! ctx)]
                                   (push! ctx b) (push! ctx a))))
      (def-prim "OVER" (fn [ctx] (let [b (pop-val! ctx)
                                       a (pop-val! ctx)]
                                   (push! ctx a) (push! ctx b) (push! ctx a))))
      (def-prim "ROT" (fn [ctx] (let [c (pop-val! ctx)
                                      b (pop-val! ctx)
                                      a (pop-val! ctx)]
                                  (push! ctx b) (push! ctx c) (push! ctx a))))
      (def-prim "<" (fn [ctx] (let [b (pop-val! ctx)
                                    a (pop-val! ctx)]
                                (push! ctx (if (< a b) -1 0)))))
      (def-prim ">" (fn [ctx] (let [b (pop-val! ctx)
                                    a (pop-val! ctx)]
                                (push! ctx (if (> a b) -1 0)))))
      (def-prim "=" (fn [ctx] (let [b (pop-val! ctx)
                                    a (pop-val! ctx)]
                                (push! ctx (if (= a b) -1 0)))))
      (def-prim "0=" (fn [ctx] (push! ctx (if (= 0 (pop-val! ctx)) -1 0))))
      (def-prim "AND" (fn [ctx] (let [b (pop-val! ctx)
                                      a (pop-val! ctx)]
                                  (push! ctx (bit-and a b)))))
      (def-prim "OR" (fn [ctx] (let [b (pop-val! ctx)
                                     a (pop-val! ctx)]
                                 (push! ctx (bit-or a b)))))
      (def-prim "." (fn [ctx] (print (pop-val! ctx)) (print " ") (flush)))
      (def-prim ".S" (fn [ctx] (print @(:stack ctx)) (print " ") (flush)))
      (def-prim "CR" (fn [ctx] (println)))
      (def-prim "EMIT" (fn [ctx] (print (char (pop-val! ctx))) (flush)))
      (def-prim "TYPE" (fn [ctx] (print (pop-val! ctx)) (flush)))
      (def-prim "DEPTH" (fn [ctx] (push! ctx (count @(:stack ctx)))))
      ;; MS ( n -- ) -- pause n milliseconds, standard Forth (FACILITY
      ;; wordset). Was missing entirely -- without it, a DO...LOOP
      ;; calling PLAY! has no way to space iterations out in time, so
      ;; N calls all fire within the same instant and are audibly
      ;; indistinguishable from one (confirmed directly: play really was
      ;; called N times, just with nothing between the calls).
      (def-prim "MS" (fn [ctx] (Thread/sleep (long (pop-val! ctx)))))
      (def-prim "I" (fn [ctx] (push! ctx (:index (first @(:loops ctx))))))
      (def-prim "J" (fn [ctx] (push! ctx (:index (second @(:loops ctx))))))
      (def-prim "@" (fn [ctx] (push! ctx @(pop-val! ctx))))
      (def-prim "!" (fn [ctx] (let [addr (pop-val! ctx) v (pop-val! ctx)]
                                (reset! addr v))))
      (def-prim "CREATE" (fn [ctx] (prim-create ctx)))
      (def-prim "VARIABLE" (fn [ctx] (prim-variable ctx)))
      (def-prim "," (fn [ctx] (prim-comma ctx)))
      ;; M. -- pop a {:sid :ids} result (whatever a bare [...]/(par ...)/
      ;; etc. chunk, or S" ..." PARSE, pushed -- both stage into the same
      ;; real core.repo now, see the musics-prims comment block above)
      ;; and print every id it introduced, straight from that staged
      ;; content via musics/pending -- works before COMMIT! is ever
      ;; called, same as a REPL session inspecting a pending parse would.
      (def-prim "M." (fn [ctx] (let [{:keys [sid ids]} (pop-val! ctx)
                                      staged (m/pending sid)]
                                  (doseq [id ids]
                                    (d/print-structure staged id))))))))

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
    ;; { a b c -- comment } -- gforth-style: names up to a bare `--`
    ;; (if present) are bound from the stack; `--` and everything after
    ;; it up to `}` is a human-readable comment, never bound.
    (let [names (loop [ns [] commenting? false]
                  (let [tk (next-tok! toks)]
                    (cond
                      (nil? tk) (throw (ex-info "Unterminated locals block {" {}))
                      (= tk "}") ns
                      (= tk "--") (recur ns true)
                      commenting? (recur ns true)
                      (string? tk) (recur (conj ns tk) false)
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
          :print-str (recur (conj ops {:op :print-str :value (second t)}))
          ;; :musics is NOT baked as a :lit the way :str is -- a string
          ;; is inert data (the same value every time is correct), but
          ;; musics text has a real side effect (m/parse stages into
          ;; core.repo) that has to happen fresh every time this code
          ;; actually runs, not once at compile time. Confirmed as a
          ;; real bug, not theoretical: `10 0 DO {verse: c4} PLAY! LOOP`
          ;; called m/parse exactly once despite 10 loop iterations,
          ;; since the old {:op :lit :value (m/parse ...)} baked one
          ;; parse result into the ops vector and every iteration just
          ;; re-pushed that same already-staged value. :parse-musics
          ;; below defers the m/parse call to run-body's own dispatch,
          ;; so it re-runs -- and re-stages, under a fresh sid -- every
          ;; time this op is reached, loop iteration or repeated call
          ;; alike.
          :musics (recur (conj ops {:op :parse-musics :text (second t)})))
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
            :parse-musics (do (push! ctx (m/parse (:text instr))) (recur (inc pc)))
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

;; IF/DO/BEGIN are compile-only words -- compile-word only ever sees them
;; from inside compile-block's own recursive scan, which normally starts
;; at compile-definition (a `:` word). At the top level there's no such
;; scan, so interpret-token has to start one itself: compile just this
;; one control structure (through its own matching terminator --
;; LOOP/THEN/UNTIL/REPEAT, consumed by compile-word/compile-block the
;; same way they always are) into a standalone ops vector, then run it
;; immediately via run-body, exactly as if it had been the body of a
;; throwaway colon definition. defining-name is nil (nothing's being
;; defined).
;;
;; { a b } deliberately isn't included here even though compile-word
;; handles it too: its whole point is binding names visible to
;; everything AFTER it in the same body, but this fragment-at-a-time
;; approach only ever compiles-and-runs the ONE construct in isolation
;; -- the bound locals would vanish the instant that throwaway run-body
;; call returned, leaving every later reference in the same line seeing
;; "Unknown word." IF/DO/BEGIN don't have this problem (nothing they
;; introduce needs to outlive the construct itself), so only they're
;; safe to support this way; { } still needs an actual colon definition.
(def ^:private control-starters #{"IF" "DO" "BEGIN"})

(defn interpret-token [t ctx]
  (cond
    (= t ":") (compile-definition ctx)
    (= t "'") (let [nm (next-tok! (:toks ctx))
                    entry (get @(:dict ctx) nm)]
                (when-not entry (throw (ex-info (str "Unknown word: " nm) {})))
                (push! ctx entry))
    (= t "EXECUTE") (execute-entry (pop-val! ctx) ctx)
    (and (string? t) (contains? control-starters t))
    (let [ops (compile-word (:toks ctx) t (:dict ctx) nil (atom #{}))]
      (run-body ops ctx))
    (vector? t)
    (case (first t)
      :str (push! ctx (second t))
      :print-str (do (print (second t)) (flush))
      :musics (push! ctx (m/parse (second t))))
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

(defn run-repl-loop
  "Print prompt, read a line, run-string it, print \" ok\" (or an error),
   repeat -- until EOF (read-line returns nil, Ctrl-D at a real
   terminal) or BYE throws the forth-exit signal (see forth-exit!
   above). Returns normally either way; never calls System/exit itself
   -- that decision belongs to the caller (-main wants the whole
   process to end afterward, repl! below just wants control back).
   Shared by both so the read/eval/error-handling shape stays in
   exactly one place."
  [ctx prompt]
  (loop []
    (print prompt) (flush)
    (let [line (read-line)]
      (when line
        (let [continue?
              (try
                (run-string ctx line)
                (println " ok")
                true
                (catch clojure.lang.ExceptionInfo e
                  (if (:forth/exit? (ex-data e))
                    false
                    (do (reset! (:toks ctx) '())
                        (println "Error:" (.getMessage e))
                        true)))
                (catch Exception e
                  ;; an error mid-line leaves whatever of THIS line wasn't
                  ;; consumed yet still sitting in :toks (interpret-all's
                  ;; loop stops at the throw, it doesn't drain the rest) --
                  ;; clear it so a failed line can't silently bleed leftover
                  ;; tokens into however the NEXT line gets interpreted.
                  (reset! (:toks ctx) '())
                  (println "Error:" (.getMessage e))
                  true))]
          (when continue? (recur)))))))

(defn -main [& _]
  (println "Small Forth in Clojure. Ctrl-D or BYE to exit.")
  (run-repl-loop (make-ctx) "> "))

(defn repl!
  "Drop into a nested Forth REPL loop from within an already-running
   Clojure REPL -- the from-Clojure-to-Forth counterpart of musics.clj's
   own mu! (from-Clojure-to-mu!). Reads from/prints to the same *in*/
   *out* the outer REPL uses, and automatically shares core.repo/
   musics.clj's session with it -- those are defonce singletons, the
   same store no matter which interpreter (plain Clojure, mu!, or this)
   is driving them, so anything staged/committed here is visible from
   the outer REPL afterward and vice versa. Only the Forth-level state
   (the dictionary of user-defined words, the stack) is NOT shared or
   persisted across calls -- each (repl!) call starts a fresh make-ctx,
   same as a brand new `lein run -m input.forth` process would.

   Ctrl-D or BYE returns control to the calling Clojure REPL -- unlike
   -main's own use of the same run-repl-loop, this never calls
   System/exit, so the outer session (and anything else running in this
   same JVM) is untouched.

   (forth/repl!) from a Clojure REPL; BYE (or Ctrl-D) to come back."
  []
  (println "Forth REPL. Ctrl-D or BYE to return to the Clojure REPL.")
  (run-repl-loop (make-ctx) "forth> ")
  (println "Back to the Clojure REPL.")
  nil)

;; =======================================================================
;; musics.clj bridge -- everything below exists only because this Forth
;; also hosts musics text. Nothing above this line knows musics.clj
;; exists beyond the four forward-declared names at the top of the file
;; (musics-open-at/scan-musics-chunk/locals-position?/musics-prims) that
;; tokenize and make-dict call into.
;; =======================================================================

;; ---------------------------------------------------------------------
;; musics text embedded directly in Forth source -- no S" wrapper
;; ---------------------------------------------------------------------
;; musics.ebnf's own lead brackets (see its "Bracket system" header
;; comment), recognized at a token boundary the exact same way S"/."
;; already are in tokenize above. Longest lead first so a multi-char
;; opener is always checked before a shorter one could coincidentally
;; match its own first character (not actually ambiguous here -- '[
;; shares no first character with the bare { / [ options, and (par
;; itself is unambiguous the moment ( is involved -- but checking
;; long-first is the safe default regardless). Unit ('{ }), AtomicAlgo
;; (@[ ]), and ElementAlgo (@{ }) no longer exist in musics.ebnf at all,
;; so those three openers are gone from this table -- see that
;; grammar's own header comment. locals-position?'s own bare-{
;; collision (below) is unaffected by any of this: { still opens a
;; Context, and the collision it guards against (: NAME { ... -- is
;; this gforth's own locals block, or musics text?) is exactly the same
;; either way, since the disambiguation never depended on what { means
;; once recognized as musics, only on whether it's musics at all.
;;
;; (par -- Parallel's own current spelling, replacing the former #{ --
;; is deliberately the ONLY ( -prefixed entry here, even though
;; times/tuplet/transpose/repeat/grace (and its four synonyms) are ALSO
;; ( -prefixed: par is the one member of that family that's a valid
;; whole TopElement on its own (musics.ebnf's own TopElement comment),
;; so it's the only one bare musics-text recognition at Forth's own top
;; level actually needs -- the others are always nested inside
;; something else, same as before this table ever had a ( entry at
;; all. scan-musics-chunk (below) still has to track THEIR nesting
;; correctly once inside an already-recognized chunk, since they share
;; )'s closing token with par's own -- see its own comment on the
;; separate, generic bare-( handling that covers them (and slurs, and
;; StructValue) without needing an entry here each."
(def ^:private musics-openers
  [["'[" "]"] ["{" "}"] ["[" "]"] ["(par " ")"]])

(defn- musics-open-at
  "[open close] if source at i starts one of musics.ebnf's own composite
   brackets, else nil."
  [^String source i]
  (some (fn [[open close]]
          (let [end (+ i (count open))]
            (when (and (<= end (count source)) (= open (subs source i end)))
              [open close])))
        musics-openers))

(defn- scan-musics-chunk
  "source at i is exactly a recognized musics opener -- returns the index
   just past its matching closer, brackets and nesting depth tracked with
   a stack of expected closers (not a flat counter: {[...]} and similar
   need to know which closer is due next at each depth, not just how
   deep). A \"...\" string literal encountered along the way is skipped
   verbatim (StringLit's own grammar has no escapes, so the very next \"
   always ends it) -- a stray }/] inside quoted musics text must never
   count as a real close. Nested musics constructs (a Unit inside a
   Sequence, an AtomicAlgo's own Data operands, ...) push their own
   closer the same way the initial one did; the whole chunk is done only
   once every pushed closer has been matched, back to empty.

   Any OTHER bare ( encountered along the way -- times/tuplet/transpose/
   repeat/grace (and its four synonyms), or StructValue's own
   (!key:(...)) -- is ALSO pushed as needing its own ) tracked, even
   though none of those are in musics-open-at's own table (that table
   is deliberately narrow, see its own comment, to keep bare TOP-LEVEL
   recognition limited to what's actually valid there). Genuinely
   needed once (par ...) exists and either of those sits DIRECTLY as
   one of its own ParElements (par (times 2 [c4 d4]) [w: e4]) -- with
   no [...]/{...} in between to keep pushing a DIFFERENT closer the
   whole time the nested construct's own body plays out -- since (par
   ...) is the first musics construct whose own closer is ) too, a
   nested Command/StructValue's own ) could otherwise be mistaken for
   the enclosing (par ...)'s, ending the scan right there. Confirmed
   live as a real bug, not hypothetical, though narrower than it might
   first look: a Command/StructValue nested inside a [...] wrapper first
   (par [v: (times 2 [c4 d4])] ...), the far more common shape, was
   NEVER actually at risk -- the wrapper's own ] stays the 'currently
   expected' closer the entire time the nested construct's ) appears,
   so it's harmlessly skipped as ordinary text either way; only a bare
   Command/StructValue sitting DIRECTLY as a Parallel element, nothing
   else pushed in between, ever puts ) back on top of the stack while a
   nested ) is still pending. A slur (c4( d4 e4)) can NEVER trigger this
   at all, structurally, not just in practice: it's always glued onto a
   Note, and ParElement doesn't include bare Leaf either, so a slur can
   only ever be reached through a wrapping Sequence first, which always
   keeps ] as the currently-expected closer throughout -- confirmed
   live, not just reasoned, before writing this claim down.
   Generic on purpose rather than one musics-openers-style entry per
   Command spelling: every one of them, plus StructValue, share the
   exact same closer ), so tracking bare ( as a class covers all of
   them (present and future) with one branch instead of a growing
   enumeration that's easy to forget a new entry for."
  [^String source i]
  (let [len (count source)
        [open close] (musics-open-at source i)]
    (loop [pos (+ i (count open)) closers (list close)]
      (if (empty? closers)
        pos
        (cond
          (>= pos len)
          (throw (ex-info "Unterminated musics text embedded in Forth source" {:start i}))

          (= (.charAt source pos) \")
          (let [end (str/index-of source "\"" (inc pos))]
            (when-not end
              (throw (ex-info "Unterminated string inside embedded musics text" {:start i})))
            (recur (inc end) closers))

          (musics-open-at source pos)
          (let [[o c] (musics-open-at source pos)]
            (recur (+ pos (count o)) (conj closers c)))

          (= (.charAt source pos) \()
          (recur (inc pos) (conj closers ")"))

          (let [want (first closers) end (+ pos (count want))]
            (and (<= end len) (= want (subs source pos end))))
          (recur (+ pos (count (first closers))) (rest closers))

          :else
          (recur (inc pos) closers))))))

(defn- locals-position?
  "True right after `: NAME` -- the one, gforth-standard position a
   locals block `{ a b c -- comment }` can open, and therefore the one
   spot { must NOT be read as musics' Sequence bracket instead. tokens
   is whatever's been accumulated by tokenize so far (a plain word
   string, not a [:str ...]/[:musics ...] vector, since a colon
   definition's own name is always a bare word)."
  [tokens]
  (and (>= (count tokens) 2)
       (= ":" (nth tokens (- (count tokens) 2)))
       (string? (peek tokens))))

;; ---------------------------------------------------------------------
;; musics.clj bridge -- every public musics.clj fn as a Forth word, plus
;; PLAY! (below, near the other MIDI/playback words), the one word here
;; that isn't a 1:1 wrapper -- it composes parse/commit!/play-latest!/
;; play into one step, mirroring musics.clj/play-file!'s own recipe.
;; ---------------------------------------------------------------------
;; Argument-marshaling conventions, decided once here rather than
;; per-word:
;;
;;  - Musics text (parse/s!/sc!/try-parse) or a filesystem path
;;    (parse-file/write/load/from-ly-to-mus) is whatever S" ..." already
;;    puts on the stack -- a plain Clojure string, unchanged. A bare
;;    [...]/(par ...)/etc. chunk (see musics-openers above) is the *other*
;;    way to get musics text staged: interpret-token/compile-block both
;;    call m/parse on it directly now (not a standalone, session-less
;;    walk the way this used to work), so `[verse: c4 d4]` alone pushes
;;    the exact same {:sid :ids} result `S" [verse: c4 d4]" PARSE` would
;;    -- no quoting needed for the real staging pipeline either, not just
;;    for a throwaway look. >SID/M. both consume that shape either way.
;;
;;  - Any id/sid/key/phase/action-id/tx-target argument runs through
;;    ->kw (below) first: a real keyword passes through unchanged, and a
;;    Forth string (all this tokenizer can produce bare, since there's
;;    no keyword-literal syntax) becomes one. This is more than
;;    convenience for some of these -- musics.clj's own resolve-id
;;    (find/children/leaves/sq/inspect/ctx/ctx-value) and its explicit
;;    `(if (string? id) (keyword id) id)` (locate/describe/print-
;;    structure) already tolerate a bare string, but core.repo's direct
;;    registry/staging lookups (history/as-of/commit!/abort!/pending,
;;    keyed by sid) and every conductor id (schedule!/schedule-tx!/
;;    register-action!/trigger!/the live engine's play-arg mini-
;;    language) compare ids with plain `=`/keyword? checks, so a bare
;;    string silently never matches there. Applying ->kw everywhere
;;    uniformly sidesteps needing to remember which case is which.
;;    NOT applied to LOCATE's `path` (a raw selector vector -- an id
;;    would never appear alone in that position) or EXPAND's `leaf` (an
;;    actual leaf value, not an id at all -- see musics.clj/expand's own
;;    docstring, it walks the real repo tree searching for that exact
;;    value, not a lookup by id).
;;
;;  - A fn with an optional trailing `tx` arg is wired at its FULL arity
;;    here -- tx always required on the Forth side -- rather than
;;    proliferating a `-TX`-suffixed variant per word: LATEST-TX pushes
;;    (latest-tx), so `LATEST-TX SOME-WORD` reproduces the short-arity
;;    Clojure call exactly, one extra word total instead of doubling the
;;    dictionary.
;;
;;  - A fn with a genuinely different (not just tx-defaulting) no-arg
;;    form gets a second, differently-named word: INSPECT/INSPECT-ALL
;;    (inspect's 0-arg form is a session node-count overview, a
;;    different code path, not just (inspect :ROOT tx)), HELP/HELP?,
;;    SCHEDULED/SCHEDULED?. describe/print-structure's
;;    0-arg form, by contrast, literally *is* (describe :ROOT
;;    (latest-tx)) under the hood -- no second word needed there,
;;    `S" ROOT" LATEST-TX DESCRIBE`/`S" ROOT" LATEST-TX PRINT-STRUCTURE`
;;    reproduce it exactly.
;;
;;  - parse/s!/parse-file all return {:sid :ids} -- one logical result
;;    with two fields often both wanted right after. Pushed as ONE
;;    opaque map, same as every other map-returning word here (PENDING,
;;    SESSION, ...), plus two small accessor words, >SID and >IDS:
;;      S" [verse: c4]" PARSE DUP >SID COMMIT! DROP >IDS  ( -- tx ids )
;;    or just `>SID COMMIT!` alone when ids isn't needed.
;;
;;  - register-action!/register-wall! both get a real bridge: `' SOME-
;;    WORD` already pushes an executable token (see EXECUTE above), so
;;    wrapping one into a plain Clojure fn (callable-arg/token->fn
;;    below) is a few lines against machinery that already exists, not
;;    a redesign -- both are genuinely usable from pure Forth text as a
;;    result, no external Clojure fn needs to be seeded onto the stack.
;;
;;  - play/display's real arg is core.async-engine/play's small
;;    mini-language (one Form -- a bare keyword ref, a [] sequential
;;    group, a #{} parallel group, an optional [Form :algo Name] tag,
;;    and context-refs) -- richer than a single id. This Forth has no
;;    vector/set-literal syntax to build a group with, so PLAY/DISPLAY
;;    here only wire the single-part-reference case, `(play :verse)`'s
;;    shape; the fuller grammar (parallel groups, multiple sequential
;;    parts in one call, context-refs, algo tags) isn't reachable from
;;    bare Forth text as a result -- a real gap, noted rather than
;;    silently narrowed.

(defn- ->kw
  "String -> keyword; anything else (a keyword already, a number, ...)
   passes through unchanged. See the argument-marshaling note above."
  [x]
  (if (string? x) (keyword x) x))

(defn- token->fn
  "Wrap a Forth execution token (a word-entry map, as pushed by ' NAME)
   into a plain Clojure fn against ctx's own stack: each call arg is
   pushed, the token runs, and whatever it leaves on top becomes the
   Clojure-level return value (nil if it left nothing). ctx here is the
   live interpreter ctx the token came from (shared, mutable stack/dict
   atoms) -- captured once, at REGISTER-ACTION! time; calling the
   resulting fn later, from anywhere (a conductor signal firing during
   playback, a direct TRIGGER! call), still runs against that same
   interpreter's own stack. execute-entry already has a real definition
   by this point in the file (the VM section, above), so unlike an
   earlier version of this file, no forward-declare is needed here for
   it."
  [ctx entry]
  (fn [& args]
    (doseq [a args] (push! ctx a))
    (execute-entry entry ctx)
    (when (seq @(:stack ctx)) (pop-val! ctx))))

(defn- callable-arg
  "REGISTER-ACTION!'s f: either an execution token (a word-entry map,
   from ' NAME -- wrapped via token->fn) or an already-real Clojure fn
   (ifn?, e.g. seeded onto the stack directly from Clojure code). Word-
   entry maps are themselves technically ifn? (plain Clojure maps are),
   so the map/:type check has to run first or a token would be used as
   a lookup function instead of being wrapped."
  [ctx v]
  (cond
    (and (map? v) (contains? v :type)) (token->fn ctx v)
    (ifn? v) v
    :else (throw (ex-info "REGISTER-ACTION! needs a fn or an execution token (' NAME)" {:got v}))))

(defn- musics-prims []
  (merge
    ;; -- parse / stage -----------------------------------------------
    (def-prim "PARSE" (fn [ctx] (push! ctx (m/parse (pop-val! ctx)))))
    (def-prim "S!" (fn [ctx] (push! ctx (m/s! (pop-val! ctx)))))
    (def-prim "SC!" (fn [ctx] (push! ctx (m/sc! (pop-val! ctx)))))
    (def-prim "TRY-PARSE" (fn [ctx] (push! ctx (m/try-parse (pop-val! ctx)))))
    (def-prim "PARSE-FILE" (fn [ctx] (push! ctx (m/parse-file (pop-val! ctx)))))
    (def-prim ">SID" (fn [ctx] (push! ctx (:sid (pop-val! ctx)))))
    (def-prim ">IDS" (fn [ctx] (push! ctx (:ids (pop-val! ctx)))))

    ;; -- commit / abort / pending --------------------------------------
    (def-prim "COMMIT!" (fn [ctx] (push! ctx (m/commit! (->kw (pop-val! ctx))))))
    (def-prim "C!" (fn [ctx] (push! ctx (m/c! (->kw (pop-val! ctx))))))
    (def-prim "ABORT!" (fn [ctx] (m/abort! (->kw (pop-val! ctx)))))
    (def-prim "PENDING" (fn [ctx] (push! ctx (m/pending (->kw (pop-val! ctx))))))

    ;; -- registry / navigation / inspection -----------------------------
    (def-prim "FIND" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                  (push! ctx (m/find id tx)))))
    (def-prim "IDS" (fn [ctx] (push! ctx (m/ids (pop-val! ctx)))))
    (def-prim "ROOT-CHILDREN" (fn [ctx] (push! ctx (m/root-children (pop-val! ctx)))))
    (def-prim "CHILDREN" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                      (push! ctx (m/children id tx)))))
    (def-prim "LEAVES" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                    (push! ctx (m/leaves id tx)))))
    (def-prim "SQ" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                (push! ctx (m/sq id tx)))))
    (def-prim "INSPECT" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                     (m/inspect id tx))))
    (def-prim "INSPECT-ALL" (fn [ctx] (m/inspect)))
    (def-prim "CTX" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                 (m/ctx id tx))))
    (def-prim "CTX-VALUE" (fn [ctx] (let [tx (pop-val! ctx) time (pop-val! ctx)
                                           key (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                       (push! ctx (m/ctx-value id key time tx)))))
    (def-prim "LOCATE" (fn [ctx] (let [tx (pop-val! ctx) path (pop-val! ctx) id (->kw (pop-val! ctx))]
                                    (push! ctx (m/locate id path tx)))))
    (def-prim "DESCRIBE" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                      (push! ctx (m/describe id tx)))))
    (def-prim "PRINT-STRUCTURE" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                             (m/print-structure id tx))))
    (def-prim "EXPAND" (fn [ctx] (let [tx (pop-val! ctx) leaf (pop-val! ctx)]
                                    (push! ctx (m/expand leaf tx)))))
    (def-prim "LATEST-TX" (fn [ctx] (push! ctx (m/latest-tx))))
    (def-prim "HISTORY" (fn [ctx] (push! ctx (m/history (->kw (pop-val! ctx))))))
    (def-prim "AS-OF" (fn [ctx] (let [tx (pop-val! ctx) id (->kw (pop-val! ctx))]
                                   (push! ctx (m/as-of id tx)))))

    ;; -- MIDI / playback -------------------------------------------------
    (def-prim "CONNECT" (fn [ctx] (m/connect)))
    (def-prim "WARM-UP!" (fn [ctx] (m/warm-up!)))
    (def-prim "WARM-UP-N!" (fn [ctx] (let [ms (pop-val! ctx) n (pop-val! ctx)] (m/warm-up! n ms))))
    (def-prim "DISCONNECT" (fn [ctx] (m/disconnect)))
    (def-prim "PLAY" (fn [ctx] (m/play (->kw (pop-val! ctx)))))
    ;; PLAY-ADD/PLAY-CHANGE mirror PLAY's own narrow shape (a single
    ;; bare id, not the full [Form :algo Name] mini-language -- building
    ;; a #{}/tagged Form from Forth stack values is a bigger design
    ;; question than this parity pass covers) -- ARG PLAY-CHANGE pushes
    ;; path first then the id to supersede it with, same left-to-right
    ;; convention every other multi-arg word here uses.
    (def-prim "PLAY-ADD" (fn [ctx] (push! ctx (m/play-add (->kw (pop-val! ctx))))))
    (def-prim "PLAY-CHANGE" (fn [ctx] (let [arg (->kw (pop-val! ctx)) path (->kw (pop-val! ctx))]
                                         (push! ctx (m/play-change path arg)))))
    (def-prim "VOICE-AT" (fn [ctx] (push! ctx (m/voice-at (->kw (pop-val! ctx))))))
    (def-prim "PLAY-FILE!" (fn [ctx] (m/play-file! (pop-val! ctx))))
    (def-prim "DISPLAY" (fn [ctx] (push! ctx (m/display (->kw (pop-val! ctx))))))
    (def-prim "STOP!" (fn [ctx] (m/stop!)))
    (def-prim "PAUSE!" (fn [ctx] (m/pause!)))
    (def-prim "RESUME!" (fn [ctx] (m/resume!)))
    (def-prim "ALL-NOTES-OFF" (fn [ctx] (m/all-notes-off)))
    (def-prim "PLAY-TX!" (fn [ctx] (m/play-tx! (pop-val! ctx))))
    (def-prim "PLAY-LATEST!" (fn [ctx] (m/play-latest!)))
    ;; PLAY! -- stage, commit, and play in one step, mirroring
    ;; musics.clj/play-file!'s own recipe exactly (parse, commit!,
    ;; play-latest!, (play (vec ids))) but starting from text already on
    ;; the stack instead of a file path. Accepts either shape the
    ;; unified musics-text pathway can leave on the stack: a raw string
    ;; (S" ..." PLAY!, not yet parsed) or an already-staged {:sid :ids}
    ;; map (bare [...] PLAY! -- see the bridge comment above, a bare
    ;; chunk calls m/parse the moment it's tokenized, so by the time
    ;; PLAY! runs it's already staged, not raw text). (m/play (vec ids))
    ;; wraps every id from this call into ONE [] Form (play's own
    ;; call shape takes exactly one Form now, no more variadic top-level
    ;; ids) -- [] is always sequential, so play :a :b's old sequential
    ;; meaning is unchanged, just spelled (play [:a :b]) underneath.
    ;;
    ;; Gotcha, confirmed not hypothetical: PLAY! only ever pops ONE
    ;; stack value, so `[a: c4] [b: d4] PLAY!` does NOT stage/commit/
    ;; play both -- each bare chunk is its own token, parsed (and given
    ;; its own sid) independently the moment it's tokenized, so PLAY!
    ;; only ever sees whichever one is on top (:b here), leaving :a
    ;; staged but never committed. For several parts together, stage
    ;; them under ONE sid the way musics.clj/parse itself already
    ;; supports -- one string, several [ ] blocks inside it:
    ;; `S" [a: c4] [b: d4]" PLAY!` commits and plays both correctly.
    (def-prim "PLAY!" (fn [ctx] (let [v (pop-val! ctx)
                                       {:keys [sid ids]} (if (string? v) (m/parse v) v)]
                                   (m/commit! sid)
                                   (m/play-latest!)
                                   (m/play (vec ids)))))

    ;; P! -- musics.clj/p!'s own Forth word (p! itself is just a short
    ;; name for play!, same relationship s! has to parse). NOT the same
    ;; shape as PLAY! above, despite doing the same job: p!/play! only
    ;; ever accept raw TEXT (they call m/parse themselves), so P! only
    ;; pops a string -- S" ..." P!, not a bare [...] chunk. A bare chunk
    ;; auto-parses to an already-staged {:sid :ids} map the moment it's
    ;; tokenized (see the bridge comment above), and handing THAT to
    ;; m/p! would fail, since m/parse expects text, not a map -- PLAY!
    ;; is the word that accepts both shapes; P! deliberately doesn't.
    (def-prim "P!" (fn [ctx] (m/p! (pop-val! ctx))))

    ;; -- generative transforms -------------------------------------------
    ;; times/transpose/invert/scale/reverse/shuffle/thread/tonal-* are
    ;; all pure from here on -- every one pops MATERIAL (an already-
    ;; built seq, left on the stack by SQ or another of these words'
    ;; own output), never a bare id and never a tx. SQ (and ACTIVE-KEY,
    ;; for tonal-*'s own ks) are the only input-phase words -- tx has no
    ;; business anywhere past that point, the same separation
    ;; musics.clj's own comment above times explains in full.
    ;;
    ;; Single transform, straightforward -- own scalar arg, then material:
    ;;   2 S" verse" LATEST-TX SQ TIMES PLAY
    ;;
    ;; Chaining more than one, confirmed live, not just reasoned through
    ;; -- got this wrong once myself before checking: EVERY transform's
    ;; own scalar arg has to be pushed BEFORE material is built, in the
    ;; order the words themselves will later run in (the LAST word to
    ;; run -- TRANSPOSE here -- gets its arg pushed FIRST, so it stays
    ;; buried under everything TIMES needs until TIMES has already run
    ;; and consumed its own). Reading left to right: outer args, inner
    ;; args, THEN material, THEN the words in normal (inner-first)
    ;; execution order:
    ;;   7 2 S" verse" LATEST-TX SQ TIMES TRANSPOSE PLAY
    ;; -- NOT `2 S" verse" ... SQ TIMES 7 TRANSPOSE` (7 pushed after
    ;; TIMES's own result would just get popped BY TIMES as if it were
    ;; material, since TIMES doesn't know or care what's already run).
    ;;   S" tune" LATEST-TX ACTIVE-KEY 1 S" tune" LATEST-TX SQ TONAL-TRANSPOSE
    (def-prim "TIMES" (fn [ctx] (let [material (pop-val! ctx) n (pop-val! ctx)]
                                   (push! ctx (m/times n material)))))
    (def-prim "TRANSPOSE" (fn [ctx] (let [material (pop-val! ctx) semitones (pop-val! ctx)]
                                       (push! ctx (m/transpose semitones material)))))
    (def-prim "INVERT" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx)]
                                    (push! ctx (m/invert axis material)))))
    (def-prim "INVERT-MEAN" (fn [ctx] (push! ctx (m/invert (pop-val! ctx)))))
    (def-prim "SCALE" (fn [ctx] (let [material (pop-val! ctx) factor (pop-val! ctx)]
                                   (push! ctx (m/scale factor material)))))
    (def-prim "REVERSE" (fn [ctx] (push! ctx (m/reverse (pop-val! ctx)))))
    (def-prim "SHUFFLE" (fn [ctx] (push! ctx (m/shuffle (pop-val! ctx)))))
    (def-prim "THREAD" (fn [ctx] (let [material (pop-val! ctx) f (callable-arg ctx (pop-val! ctx))]
                                    (push! ctx (m/thread f material)))))
    (def-prim "ACTIVE-KEY" (fn [ctx] (let [tx (pop-val! ctx) x (->kw (pop-val! ctx))]
                                        (push! ctx (m/active-key x tx)))))
    (def-prim "TONAL-TRANSPOSE" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx)
                                                 ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-transpose ks steps material)))))
    (def-prim "TONAL-INVERT" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx)
                                              ks (pop-val! ctx)]
                                          (push! ctx (m/tonal-invert ks axis material)))))
    (def-prim "SNAP-TO-SCALE" (fn [ctx] (let [material (pop-val! ctx) ks (pop-val! ctx)]
                                           (push! ctx (m/snap-to-scale ks material)))))
    (def-prim "TONAL-HARMONIZE" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx)
                                                 ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-harmonize ks steps material)))))

    ;; -- variables --------------------------------------------------------
    (def-prim "CLEAR-VARS" (fn [ctx] (m/clear-vars)))

    ;; -- persistence --------------------------------------------------------
    (def-prim "WRITE" (fn [ctx] (let [tx (pop-val! ctx) path (pop-val! ctx)] (m/write path tx))))
    (def-prim "LOAD" (fn [ctx] (m/load (pop-val! ctx))))
    (def-prim "LY-TO-MUS" (fn [ctx] (push! ctx (m/ly-to-mus (pop-val! ctx)))))

    ;; -- reset / help -------------------------------------------------------
    (def-prim "RESET" (fn [ctx] (m/reset)))
    (def-prim "HELP" (fn [ctx] (m/help)))
    (def-prim "HELP?" (fn [ctx] (m/help (pop-val! ctx))))

    ;; -- wall (per-voice playback algorithms) --------------------------------
    ;; register-wall!'s own f can be a plain 3-arg wall fn or a FACTORY
    ;; (arity N -> wall-fn), same as musics.clj's own docstring -- nothing
    ;; here detects which UNLESS the registerer says so explicitly via
    ;; REGISTER-WALL-KIND! (kind :fn or :factory, ->kw'd same as name).
    ;; name/location are ->kw'd but pass a vector through unchanged (see
    ;; ->kw above), so ASSIGN-ALGO!'s own name slot works for both a bare
    ;; name and an already-built [name arg...] parameterized-args vector.
    (def-prim "REGISTER-WALL!" (fn [ctx] (let [f (callable-arg ctx (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                            (m/register-wall! nm f))))
    (def-prim "REGISTER-WALL-DOC!" (fn [ctx] (let [doc (pop-val! ctx) f (callable-arg ctx (pop-val! ctx))
                                                    nm (->kw (pop-val! ctx))]
                                                (m/register-wall! nm f doc))))
    (def-prim "REGISTER-WALL-KIND!" (fn [ctx] (let [kind (->kw (pop-val! ctx)) doc (pop-val! ctx)
                                                      f (callable-arg ctx (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                                  (m/register-wall! nm f doc kind))))
    (def-prim "UNREGISTER-WALL!" (fn [ctx] (m/unregister-wall! (->kw (pop-val! ctx)))))
    (def-prim "WALL-KIND" (fn [ctx] (push! ctx (m/wall-kind (->kw (pop-val! ctx))))))
    (def-prim "WALLS" (fn [ctx] (push! ctx (m/walls))))
    (def-prim "WALLS?" (fn [ctx] (push! ctx (m/walls (->kw (pop-val! ctx))))))
    ;; PATH NAME ASSIGN-ALGO! -- same left-to-right, matches m/assign-
    ;; algo!'s own [path name] order, as every other multi-arg word here
    ;; (e.g. ID TX FIND). NAME is wired via ->kw's own passthrough (see
    ;; the note above), so it can be a bare id OR an already-built
    ;; [name arg...] vector for a parameterized algorithm; PATH the same
    ;; for a real multi-segment :PAR-fork path, not just a bare id.
    (def-prim "ASSIGN-ALGO!" (fn [ctx] (let [nm (->kw (pop-val! ctx)) path (->kw (pop-val! ctx))]
                                          (m/assign-algo! path nm))))
    (def-prim "ALGO-ASSIGNMENTS" (fn [ctx] (push! ctx (m/algo-assignments))))
    ;; LOCATION ARGS CONFIGURE-WALL! -- same left-to-right convention,
    ;; matches m/configure-wall!'s own [location & args]. ARGS is a
    ;; plain Clojure vector of whatever LOCATION's own registered
    ;; factory expects (built on the Forth side same as any other
    ;; aggregate value, e.g. the way PLAY! already accepts a pre-built
    ;; {:sid :ids} map instead of exposing every field as its own stack
    ;; arg) -- configure-wall! is genuinely variadic in Clojure, and
    ;; this is the same "pop one aggregate, apply it" idiom already
    ;; used for that shape here rather than a fixed small arity per
    ;; word. Confirmed live: 5 S" verseColor" CONFIGURE-WALL! (pushing
    ;; a bare Int, not a vector, as ARGS -- the mistake this comment is
    ;; written to prevent) does NOT throw, since a String is itself
    ;; Seqable and silently satisfies apply's own last-arg contract --
    ;; it just resolves against a nonsense name (5) and no-ops with a
    ;; console warning rather than configuring anything, exactly
    ;; core.wall/apply-factory's own designed failure behavior, just
    ;; triggered by a caller mistake here instead of a genuinely
    ;; unregistered name.
    (def-prim "CONFIGURE-WALL!" (fn [ctx] (let [args (pop-val! ctx) location (->kw (pop-val! ctx))]
                                             (push! ctx (apply m/configure-wall! location args)))))

    ;; -- action registry / schedule -------------------------------------------
    (def-prim "REGISTER-ACTION!" (fn [ctx] (let [f (callable-arg ctx (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                              (m/register-action! id f))))
    (def-prim "UNREGISTER-ACTION!" (fn [ctx] (m/unregister-action! (->kw (pop-val! ctx)))))
    (def-prim "TRIGGER!" (fn [ctx] (let [arg (pop-val! ctx) id (->kw (pop-val! ctx))]
                                      (push! ctx (m/trigger! id arg)))))
    (def-prim "SCHEDULE!" (fn [ctx] (let [action-id (->kw (pop-val! ctx)) phase (->kw (pop-val! ctx))
                                           id (->kw (pop-val! ctx))]
                                       (m/schedule! id phase action-id))))
    (def-prim "UNSCHEDULE!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                         (m/unschedule! id phase))))
    (def-prim "SCHEDULED" (fn [ctx] (push! ctx (m/scheduled))))
    (def-prim "SCHEDULED?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                        (push! ctx (m/scheduled id phase)))))
    ;; schedule-tx! arms the separate, non-consuming repeating table (see
    ;; its own docstring), not the one-shot table SCHEDULED/SCHEDULED?
    ;; read -- these three mirror that table the same way.
    (def-prim "SCHEDULED-REPEATING" (fn [ctx] (push! ctx (m/scheduled-repeating))))
    (def-prim "SCHEDULED-REPEATING?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                  (push! ctx (m/scheduled-repeating id phase)))))
    (def-prim "UNSCHEDULE-REPEATING!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                   (m/unschedule-repeating! id phase))))
    (def-prim "SCHEDULE-TX!" (fn [ctx] (let [target (->kw (pop-val! ctx)) phase (->kw (pop-val! ctx))
                                              id (->kw (pop-val! ctx))]
                                          (push! ctx (m/schedule-tx! id phase target)))))

    ;; -- misc / REPL parity / state ---------------------------------------
    ;; MU!/MUSIC-READ read from *in* (a nested Clojure REPL loop, and the
    ;; raw clojure.main/repl-read hook it uses internally) -- wired for
    ;; completeness, but calling either from a non-interactive context
    ;; (a test, a script fed via run-string) blocks on stdin rather than
    ;; erroring, so neither is exercised by forth_test.clj.
    (def-prim "MU!" (fn [ctx] (m/mu!)))
    (def-prim "MUSIC-EVAL" (fn [ctx] (push! ctx (m/music-eval (pop-val! ctx)))))
    (def-prim "MUSIC-READ" (fn [ctx] (let [request-exit (pop-val! ctx) request-prompt (pop-val! ctx)]
                                        (push! ctx (m/music-read request-prompt request-exit)))))
    (def-prim "C1!" (fn [ctx] (push! ctx (m/c1!))))
    (def-prim "SESSION" (fn [ctx] (push! ctx @m/session)))
    (def-prim "RECEIVER" (fn [ctx] (push! ctx @m/receiver)))))
