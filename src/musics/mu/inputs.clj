(ns musics.mu.inputs
  "The inputs of a live voice: the named, changeable values its word was started with
   (the `$` locals of 5 8 42 'riff :bass ->). They are entries of the voice's scope in the
   session's tctx (:bass/onsets, :bass/pulses): T! stores one, T@ reads it, and the voice hears
   it on its next note; a key the scope lacks is read from the shared tctx. What this namespace
   keeps beside the entries is what is not in the tctx: the order the word's stack effect names the
   inputs in, and each input's spec, {:type :min :max :default :choices :doc :algo :order}, the
   range its parameter is possible in (a store is checked against it; `settings` and the GUI show
   it). The entries stay when the voice ends: a voice started again by name is as it was tuned."
  (:require [clojure.string :as str]
            [musics.domain.context :as c]
            [musics.registries :as reg]))

(def ^:private voices
  "Voice id -> {:names [\"onsets\" …] :specs {:onsets spec …} :watches [k …]}: the order the
   word's stack effect names the inputs in, and each input's spec. The values are in the tctx."
  (atom {}))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {:mu/error true})))

(defn- key-of
  "The tctx key of input of voice: :bass/onsets."
  [voice input]
  (keyword (name voice) (name input)))

(defn- scope-chain [voice] [(c/tctx-scope reg/tctx voice) reg/tctx])

(defn set-inputs!
  "Give voice (a keyword id) the inputs [[name value] …], in that order, and
   specs, {:name spec}: the values are written into its scope in the tctx, over what
   it held."
  ([voice pairs] (set-inputs! voice pairs {}))
  ([voice pairs specs]
   (swap! voices assoc voice {:names (mapv (comp str first) pairs) :specs specs :watches []})
   (doseq [[n x] pairs] (c/tctx! reg/tctx (key-of voice n) x))
   voice))

(defn- raw-values
  "The entries of voice's inputs in an envelopes map m, its own else the shared one."
  [m voice names]
  (mapv (fn [n] (let [q (key-of voice n)] (if (contains? m q) (get m q) (get m (keyword n))))) names))

(defn watch!
  "Call (f old new) whenever an input of voice changes in the tctx (its own entry or the
   shared one it reads); the watch goes when the voice's inputs are cleared. key names it."
  [voice key f]
  (when-let [{:keys [names]} (get @voices voice)]
    (add-watch (:envelopes-atom reg/tctx) [::watch voice key]
               (fn [_ _ old new]
                 (let [o (raw-values old voice names) n (raw-values new voice names)]
                   (when (not= o n) (f o n)))))
    (swap! voices update voice update :watches conj key)))

(defn clear!
  "Forget voice's inputs (their order and specs) and the watches on them. The entries
   of its scope stay in the tctx."
  [voice]
  (when-let [{:keys [watches]} (get @voices voice)]
    (doseq [k watches] (remove-watch (:envelopes-atom reg/tctx) [::watch voice k])))
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
  "voice's inputs as {\"name\" value}, the plain map a function of them is given: each as the
   voice's scope gives it at onset 0, else the shared tctx."
  [voice]
  (when-let [{:keys [names]} (get @voices voice)]
    (let [g (c/getter (scope-chain voice) 0)]
      (into {} (map (fn [n] [n (g (keyword n))]) names)))))

(defn value [voice input]
  (when (contains? @voices voice)
    ((c/getter (scope-chain voice) 0) (keyword (name input)))))

(defn params
  "voice's inputs as they are now, {:onsets 5 …}, in the order of its stack effect."
  [voice]
  (when-let [{:keys [names]} (get @voices voice)]
    (let [g (c/getter (scope-chain voice) 0)]
      (into (array-map) (map (fn [n] [(keyword n) (g (keyword n))]) names)))))

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

(defn checked
  "x as input of voice (a keyword) takes it, or an error saying what is wrong when its spec
   refuses it; x as it is when the voice has no spec for input."
  [voice input x]
  (let [k    (keyword (name input))
        spec (get (specs voice) k)]
    (if spec (coerced k spec x) x)))

(defn put!
  "Make input of voice hold x in its scope, heard on the voice's next note. A value its spec
   refuses is an error and changes nothing. x back."
  [voice input x]
  (let [x (checked voice input x)]
    (c/tctx! reg/tctx (key-of voice input) x)
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
