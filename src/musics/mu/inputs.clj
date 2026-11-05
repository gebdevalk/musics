(ns musics.mu.inputs
  "The inputs of a live voice: the named, changeable values its word was
   started with (the `$` locals of 5 8 42 'riff :bass ->). :bass/onsets is a
   reference (musics.mu.value/Ref) to one: @ reads it, ! stores into it, heard
   on the voice's next note. A voice has inputs from the moment it starts until
   it ends. Each input has a spec, {:type :min :max :default :choices :doc
   :algo :order}, the range its parameter is possible in: a store is checked
   against it, `settings` and the GUI show it."
  (:require [clojure.string :as str]
            [musics.mu.value :as v]))

(def ^:private voices
  "Voice id -> {:names [\"onsets\" …] :atom a :specs {:onsets spec …} :watches [k …]}:
   the order the word's stack effect names the inputs in, an atom holding
   {\"onsets\" 5 …}, and each input's spec."
  (atom {}))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {:mu/error true})))

(def ^:private alive?
  "A fn of a voice id, true while it plays; set by musics.mu.live (the engine
   is not this namespace's business). Inputs of a voice that is gone are not
   reachable."
  (atom (constantly true)))

(defn alive!
  "Say how to tell a voice is playing: f of a voice id. The one before is back."
  [f]
  (let [old @alive?] (reset! alive? f) old))

(defn set-inputs!
  "Give voice (a keyword id) the inputs [[name value] …], in that order, and
   specs, {:name spec}; the atom that holds the values back."
  ([voice pairs] (set-inputs! voice pairs {}))
  ([voice pairs specs]
   (let [a (atom (into {} (map (fn [[n x]] [(str n) x]) pairs)))]
     (swap! voices assoc voice {:names (mapv (comp str first) pairs) :atom a :specs specs :watches []})
     a)))

(defn watch!
  "Call (f old-values new-values) whenever an input of voice changes; the watch
   goes when the voice's inputs are cleared. key names it."
  [voice key f]
  (when-let [a (:atom (get @voices voice))]
    (add-watch a [::watch voice key] (fn [_ _ old new] (when (not= old new) (f old new))))
    (swap! voices update voice update :watches conj key)))

(defn clear!
  "Forget voice's inputs and the watches on them."
  [voice]
  (when-let [{:keys [atom watches]} (get @voices voice)]
    (doseq [k watches] (remove-watch atom [::watch voice k])))
  (swap! voices dissoc voice)
  voice)

(defn has? [voice] (contains? @voices voice))

(defn names [voice] (:names (get @voices voice)))

(defn specs
  "voice's specs, {:onsets {:type :int :min 0 :max 32 …} …}."
  [voice]
  (:specs (get @voices voice)))

(defn nan? [x] (and (number? x) (Double/isNaN (double x))))

(defn values
  "voice's inputs as {\"name\" value}, the plain map a function of them is given."
  [voice]
  (some-> (get @voices voice) :atom deref))

(defn value [voice input] (some-> (get @voices voice) :atom deref (get (name input))))

(defn params
  "voice's inputs as they are now, {:onsets 5 …}, in the order of its stack effect."
  [voice]
  (when-let [{:keys [names atom]} (get @voices voice)]
    (into (array-map) (map (fn [n] [(keyword n) (get @atom n)]) names))))

(defn state
  "{:params :specs}: voice's inputs and their specs as a plain value, for a GUI or a printout."
  [voice]
  {:params (params voice) :specs (specs voice)})

;; -- storing: a value is checked against its spec ------------------------------

(defn- finite? [x] (and (number? x) (Double/isFinite (double x))))

(defn- coerced
  "x in the type spec wants, or an error saying what is wrong."
  [k {:keys [type min max choices doc]} x]
  (let [why (fn [what] (fail (str (name k) ":") (pr-str x) (str "should be " what) (when (seq doc) (str "(" doc ")"))))
        x   (case type
              :int     (if (and (number? x) (== x (long x))) (long x) (why "an integer"))
              :double  (if (number? x) (double x) (why "a number"))
              :ratio   (if (and (number? x) (rational? x)) x (why "a ratio"))
              :string  (cond (string? x) x (keyword? x) (subs (str x) 1) :else (why "text"))
              :keyword (cond (keyword? x) x (string? x) (keyword x) :else (why "a name"))
              :bool    (if (boolean? x) x (why "true or false"))
              :vector  (if (sequential? x) (vec x) (why "a list"))
              x)]
    (when (and (finite? min) (number? x) (< x min)) (why (str "at least " min)))
    (when (and (finite? max) (number? x) (> x max)) (why (str "at most " max)))
    (when (and (seq choices) (not-any? #(= (str %) (str x)) choices))
      (why (str "one of " (str/join ", " (map #(if (keyword? %) (name %) (str %)) choices)))))
    x))

(defn put!
  "Make input of voice hold x, heard on the voice's next note. A value its spec
   refuses is an error and changes nothing. x back."
  [voice input x]
  (let [k    (keyword (name input))
        spec (get (specs voice) k)
        x    (if spec (coerced k spec x) x)]
    (swap! (:atom (get @voices voice)) assoc (name input) x)
    x))

(defn describe
  "Print voice's inputs: key, value, range, default, owner, doc."
  [voice]
  (let [{:keys [params specs]} (state voice)
        fmt (fn [v] (cond (nan? v) "required" (nil? v) "" :else (pr-str v)))]
    (doseq [[k {:keys [min max default algo doc type]}] (sort-by (comp :order val) specs)]
      (println (format "%-14s %-12s %-16s %-10s %-12s %s"
                       (pr-str k) (fmt (get params k))
                       (if (and (number? min) (number? max)) (str min ".." max) (if type (name type) ""))
                       (fmt default) (some-> algo name) (or doc ""))))))

(defn ref-of
  "The reference :voice/input, refused with what there is when voice has no
   inputs or none called input."
  [voice input]
  (let [label (str ":" (name voice) "/" input)]
    (cond
      (and (has? voice) (not (@alive? voice)))
      (do (clear! voice)
          (fail (str label ":") "the voice" (str ":" (name voice)) "is not playing"))
      (not (has? voice))
      (fail (str label ":") "no voice" (str ":" (name voice)) "has inputs --"
            (if (seq @voices)
              (str "voices with inputs: " (str/join ", " (map #(str ":" (name %)) (sort (keys @voices)))))
              "start one: 5 8 'riff :bass ->"))
      (not (some #{input} (names voice)))
      (fail (str label ":") (str ":" (name voice)) "has no input" (str input) "--"
            (str/join ", " (names voice)))
      :else
      (v/->Ref label #(value voice input) #(put! voice input %)))))
