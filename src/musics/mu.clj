(ns musics.mu
  "mu: the musical REPL (doc/mu-repl.md). (repl) runs it: an input is
   read until mu's grammar says it is complete, evaluated on the stack,
   and the prompt shows the stack's depth, mu<n>. bye or Ctrl+D leaves;
   the stack lasts the session. Ctrl+C abandons an unfinished input."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [musics.mu.eval :as e]
            [musics.mu.grammar :as g]
            [musics.mu.kernel]
            [musics.mu.tools])
  (:import (org.jline.reader EndOfFileException EOFError LineReader LineReaderBuilder
                             Parser$ParseContext UserInterruptException)
           (org.jline.reader.impl DefaultParser)
           (org.jline.terminal TerminalBuilder)))

(defn prompt [] (str "mu<" (count (:stack @e/state)) "> "))

(defn continuation
  "The prompt for a continued input: ... right-aligned under prompt."
  [prompt]
  (str (apply str (repeat (max 0 (- (count prompt) 4)) \space)) "... "))

(e/word! "kernel" "bye" "( -- )"
         (fn [_] (throw (ex-info "bye" {:mu/bye true}))))

(defn- run-input
  "Evaluate one complete input: print what reading its literals gives
   (e/read-input's :read), report an error; true to go on."
  [text]
  (try
    (let [r (e/eval-input text println)]
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
   unfinished."
  []
  (proxy [DefaultParser] []
    (parse [line cursor ctx]
      (if (and (= ctx Parser$ParseContext/ACCEPT_LINE) (g/incomplete? line))
        (throw (EOFError. -1 -1 "unfinished input"))
        (proxy-super parse line cursor ctx)))))

(defn- line-reader []
  ;; JLine warns when it gets no real terminal (piped input, lein run): quiet
  (.setLevel (java.util.logging.Logger/getLogger "org.jline") java.util.logging.Level/SEVERE)
  (-> (LineReaderBuilder/builder)
      (.terminal (-> (TerminalBuilder/builder) (.system true) (.build)))
      (.parser (mu-parser))
      (.variable LineReader/HISTORY_FILE (.toPath (io/file (System/getProperty "user.home") ".mu-history")))
      (.build)))

(defn repl
  "Run mu until bye or Ctrl+D."
  []
  (println "mu -- bye or Ctrl+D to leave")
  (let [reader (line-reader)]
    (run-loop (fn [p]
                (.setVariable reader LineReader/SECONDARY_PROMPT_PATTERN (continuation p))
                (try (.readLine reader ^String p)
                     (catch UserInterruptException _ ::abandon)
                     (catch EndOfFileException _ nil)))))
  (println "bye"))

(defn -main
  "Run mu on its own: lein mu (an alias for lein trampoline run -m
   musics.mu, so JLine gets the real terminal). Leaving exits the JVM,
   whose MIDI and engine threads would otherwise keep it running."
  [& _]
  (repl)
  (System/exit 0))

