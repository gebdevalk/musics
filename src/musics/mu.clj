(ns musics.mu
  "mu: the musical REPL (doc/mu-repl.md). (repl) runs it: an input is
   read until mu's grammar says it is complete, evaluated on the stack,
   and the prompt shows the stack's depth, mu<n>. bye or Ctrl+D leaves;
   the stack lasts the session. Ctrl+C abandons an unfinished input."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [musics.mu.eval :as e]
            [musics.mu.feedback :as fb]
            [musics.mu.grammar :as g]
            [musics.mu.kernel]
            [musics.mu.tools]
            [musics.mu.algos])
  (:import (org.jline.keymap KeyMap)
           (org.jline.reader EndOfFileException EOFError LineReader LineReader$Option
                             LineReaderBuilder Parser$ParseContext Reference UserInterruptException Widget)
           (org.jline.reader.impl DefaultParser)
           (org.jline.terminal TerminalBuilder)))

(defn prompt [] (str "mu<" (count (:stack @e/state)) "> "))

(defn continuation
  "The prompt for a continued input: ... right-aligned under prompt."
  [prompt]
  (str (apply str (repeat (max 0 (- (count prompt) 4)) \space)) "... "))

(e/word! "kernel" "bye" "( -- )  leave mu (Ctrl+D too); the stack lasts the session"
         (fn [_] (throw (ex-info "bye" {:mu/bye true}))))

(defn- run-input
  "Evaluate one complete input and report an error; true to go on."
  [text]
  (try
    (let [r (e/eval-input text)]
      (when-let [msg (:error r)] (println "error:" msg "-- the stack is cleared"))
      true)
    (catch clojure.lang.ExceptionInfo ex
      (if (:mu/bye (ex-data ex)) false (throw ex)))))

(defn run-loop
  "The REPL loop over read-input, a fn of a prompt returning one line, a
   complete input, or nil at the end. Continues an unfinished input
   itself when the reader doesn't."
  [read-input]
  (loop [pending nil]
    (let [p    (if pending (continuation (prompt)) (prompt))
          line (read-input p)]
      (cond
        (nil? line) nil
        (= ::abandon line) (recur nil)
        :else
        (let [text (if pending (str pending "\n" line) line)]
          (cond
            (str/blank? text) (recur nil)
            (g/incomplete? text) (recur text)
            (run-input text) (recur nil)
            :else nil))))))

(defn- mu-parser
  "JLine's parser, telling JLine to continue an input mu's grammar calls
   unfinished. No escape characters: \\ is musics text's (x8\\36,
   c4\\f), so JLine must pass it through."
  []
  (doto (proxy [DefaultParser] []
          (parse [line cursor ctx]
            (if (and (= ctx Parser$ParseContext/ACCEPT_LINE) (g/incomplete? line))
              (throw (EOFError. -1 -1 "unfinished input"))
              (proxy-super parse line cursor ctx))))
    (.setEscapeChars (char-array 0))))

(defn- line-reader
  "A JLine reader for mu on terminal, its history kept in history (a
   java.nio Path)."
  [terminal history]
  ;; JLine warns when it gets no real terminal (piped input, lein run): quiet
  (.setLevel (java.util.logging.Logger/getLogger "org.jline") java.util.logging.Level/SEVERE)
  (-> (LineReaderBuilder/builder)
      (.terminal terminal)
      (.parser (mu-parser))
      ;; ! is an instruction (!mf), not a history event
      (.option LineReader$Option/DISABLE_EVENT_EXPANSION true)
      (.variable LineReader/HISTORY_FILE history)
      (.build)))

(def feedback-keys
  "Keys that rate or steer an audition at the prompt, without touching
   the line being typed (doc/feedback.md): [widget-name key f]. Three
   neighbouring keys, unshifted to rate (, dislike, / meh, . like) and
   shifted to move (< back, ? again, > next)."
  [["mu-dislike" (KeyMap/alt ",") #(fb/rate-current! -1)]
   ["mu-meh"     (KeyMap/alt "/") #(fb/rate-current! 0)]
   ["mu-like"    (KeyMap/alt ".") #(fb/rate-current! 1)]
   ["mu-back"    (KeyMap/alt "<") fb/back!]
   ["mu-again"   (KeyMap/alt "?") fb/again!]
   ["mu-next"    (KeyMap/alt ">") fb/next!]])

(defn- bind-feedback-keys!
  [^LineReader reader]
  (let [main (.get (.getKeyMaps reader) LineReader/MAIN)]
    (doseq [[name ^String k f] feedback-keys]
      (.put (.getWidgets reader) name (reify Widget (apply [_] (f) true)))
      (.bind ^KeyMap main (Reference. name) k))))

(defn repl
  "Run mu until bye or Ctrl+D."
  []
  (println "mu -- bye or Ctrl+D to leave")
  (let [reader (line-reader (-> (TerminalBuilder/builder) (.system true) (.build))
                            (.toPath (io/file (System/getProperty "user.home") ".mu-history")))]
    (reset! e/print-above (fn [^String line] (.printAbove ^LineReader reader line)))
    (bind-feedback-keys! reader)
    (try
      (run-loop (fn [p]
                  (.setVariable reader LineReader/SECONDARY_PROMPT_PATTERN (continuation p))
                  (try (.readLine reader ^String p)
                       (catch UserInterruptException _ ::abandon)
                       (catch EndOfFileException _ nil))))
      (finally (reset! e/print-above println))))
  (println "bye"))

(defn -main
  "Run mu on its own: lein mu (an alias for lein trampoline run -m
   musics.mu, so JLine gets the real terminal). Leaving exits the JVM,
   whose MIDI and engine threads would otherwise keep it running."
  [& _]
  (repl)
  (System/exit 0))

