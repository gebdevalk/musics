(ns musics.mu
  "mu: the musical REPL (doc/md/mu-repl.md). (repl) runs it: an input is
   read until mu's grammar says it is complete, evaluated on the stack,
   and the prompt shows the stack's depth, mu<n>. bye or Ctrl+D leaves;
   the stack lasts the session. Ctrl+C abandons an unfinished input."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [musics.mu.eval :as e]
            [musics.mu.feedback :as fb]
            [musics.mu.grammar :as g]
            [musics.mu.kernel :as kernel]
            [musics.mu.material :as mt]
            [musics.mu.tools]
            [musics.mu.algos]
            [musics.mu.complete :as complete]
            [musics.mu.library])
  (:import (org.jline.keymap KeyMap)
           (org.jline.reader Candidate Completer EndOfFileException EOFError LineReader LineReader$Option
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

(defn- completer
  "Tab completes the name being written (musics.mu.complete: an
   instrument, drum, kit, context key, instruction, a note's tag or a
   scale): one candidate is filled in, several listed with what they
   are, and a second Tab moves through them."
  []
  (reify Completer
    (complete [_ _ line candidates]
      (let [word (subs (.word line) 0 (.wordCursor line))]
        (doseq [{:keys [value display descr complete]} (complete/candidates word)]
          (.add candidates (Candidate. value display nil descr nil nil complete)))))))

(defn- line-reader
  "A JLine reader for mu on terminal, its history kept in history (a
   java.nio Path)."
  [terminal history]
  ;; JLine warns when it gets no real terminal (piped input, lein run): quiet
  (.setLevel (java.util.logging.Logger/getLogger "org.jline") java.util.logging.Level/SEVERE)
  (-> (LineReaderBuilder/builder)
      (.terminal terminal)
      (.parser (mu-parser))
      (.completer (completer))
      ;; !i:Viol completes as !i:viol does; a second Tab opens a menu
      ;; of the candidates under the prompt
      (.option LineReader$Option/CASE_INSENSITIVE true)
      (.option LineReader$Option/AUTO_MENU_LIST true)
      ;; ! is an instruction (!mf), not a history event
      (.option LineReader$Option/DISABLE_EVENT_EXPANSION true)
      ;; an input beginning with a space (pasted text, often) is kept in
      ;; the history like any other -- JLine leaves it out by default
      (.option LineReader$Option/HISTORY_IGNORE_SPACE false)
      (.variable LineReader/HISTORY_FILE history)
      (.build)))

(def prompt-keys
  "Keys that act at the prompt without touching the line being typed:
   [widget-name key f]. Playback: . stops, , pauses. Rating
   (doc/md/feedback.md), three neighbours on the home row: j dislike,
   k meh, l like; shifted they move through an audition: J prev,
   K again, L next."
  [["mu-stop"    (KeyMap/alt ".") #(do (mt/stop-all!) (@e/print-above "stopped"))]
   ["mu-pause"   (KeyMap/alt ",") #(@e/print-above (kernel/toggle-pause!))]
   ["mu-dislike" (KeyMap/alt "j") #(fb/rate-current! -1)]
   ["mu-meh"     (KeyMap/alt "k") #(fb/rate-current! 0)]
   ["mu-like"    (KeyMap/alt "l") #(fb/rate-current! 1)]
   ["mu-back"    (KeyMap/alt "J") fb/back!]
   ["mu-again"   (KeyMap/alt "K") fb/again!]
   ["mu-next"    (KeyMap/alt "L") fb/next!]])

(defn- bind-prompt-keys!
  [^LineReader reader]
  (let [main (.get (.getKeyMaps reader) LineReader/MAIN)]
    (doseq [[name ^String k f] prompt-keys]
      (.put (.getWidgets reader) name (reify Widget (apply [_] (f) true)))
      (.bind ^KeyMap main (Reference. name) k))))

(def header
  "What mu shows when it starts: the words that drive the engine."
  (str/join "\n"
            ["mu -- a concatenative, real-time, interactive REPL for musics"
             ""
             "Write music and it goes on the stack; words after it change it, combine it"
             "and play it, while it plays. Tab completes names, 'see' explains a word"
             "(\"dup\" see), 'words' lists them."
             ""
             "start    gervill (default) connects to the JVM's own synth, fluidsynth to an external synthesizer"
             "play     'x >' plays immediately, 'x >>' at the next boundary, 'x | >>' at the next |"
             "         'x >+' joins now and 'x >>+' joins at the boundary"
             "         '[id: …] >' replaces only :id"
             "stop     Alt+."
             "pause    Alt+, (again resumes)"
             "echo     prints each note"
             "voices   'voices' or '<<' lists the playing voices: 1..n low to high, 0 -1 … the drums"
             "         ':id <' gives what :id plays; :id stop, :id hush, :id solo"
             "gui      opens the GUI beside mu (closing it leaves mu running)"
             "leave    bye or Ctrl+D"]))

(defn repl
  "Run mu until bye or Ctrl+D."
  []
  (println header)
  (let [reader (line-reader (-> (TerminalBuilder/builder) (.system true) (.build))
                            (.toPath (io/file (System/getProperty "user.home") ".mu-history")))]
    (reset! e/print-above (fn [^String line] (.printAbove ^LineReader reader line)))
    (bind-prompt-keys! reader)
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

