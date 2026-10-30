(ns musics.mu.session
  "mu's own state as mu text, for a session file (musics.persist): the
   namespaces it required, the ones in use, the words defined with
   : … ; and the bindings of := -- an input that, run, defines them
   again. The stack is left out. A definition's literals are written as
   their values print, so each reads alone as it read in its input; a
   binding is written only when its value reads back as itself."
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [musics.mu.eval :as e]
            [musics.mu.print :as p]))

(def ^:private own-use (:use (e/fresh-state)))

(defn- quoted [s] (pr-str (str s)))

(defn- literals
  "The literal nodes of a definition's body, each read as one value."
  [body]
  (for [item body x (tree-seq vector? seq item)
        :when (and (vector? x) (:mu/index (meta x)))]
    x))

(defn- definition-text
  "A word's : … ; source, its literals written as their values print."
  [{:keys [source at body values]}]
  (reduce (fn [src lit]
            (let [[s end] (insta/span lit)]
              (str (subs src 0 (- s at)) (p/show (nth values (:mu/index (meta lit)))) (subs src (- end at)))))
          source
          ;; from the end, so earlier offsets still hold
          (sort-by #(- (first (insta/span %))) (literals body))))

(defn- written
  "x as text that reads back as x, or nil: it reads alone to a value that
   prints the same, and holds no word's locals (a quotation made inside
   a word, its $a gone once written)."
  [x]
  (when-not (seq (get-in x [:env :locals]))
    (let [text (try (p/show x) (catch Exception _ nil))]
      (when (and text (= text (try (p/show (peek (e/run-text [] text))) (catch Exception _ nil))))
        text))))

(defn text
  "mu's state as an input that defines it again: [text skipped], skipped
   the bindings whose value can't be written as text."
  []
  (let [st      @e/state
        defined (for [[ns words] (sort-by key (:ns st))
                      [nm entry] (sort-by key words)
                      :when (:body entry)]
                  [ns nm entry])
        bound   (for [[nm x] (sort-by key (:bindings st))] [nm (written x)])
        skipped (vec (for [[nm shown] bound :when (nil? shown)] nm))
        lines   (concat
                 (for [[alias _] (sort-by key (:required st))]
                   (str (quoted (get-in st [:sources alias] (get-in st [:required alias]))) " '" alias " require"))
                 (for [ns (:use st) :when (not (some #{ns} own-use))]
                   (str (quoted ns) " use"))
                 (mapcat (fn [[ns _ entry]]
                           [(str (quoted ns) " in") (definition-text entry)])
                         defined)
                 [(str (quoted (:in st)) " in")]
                 (for [[nm shown] bound :when (not (some #{nm} skipped))]
                   (str "'" nm " " shown " :=")))]
    [(str/join "\n" lines) skipped]))

(defn restore!
  "Replace mu's words and bindings with those text defines (from text);
   the stack stays."
  [text]
  (swap! e/state (fn [st]
                   (-> st
                       (update :ns #(into {} (for [[ns words] %]
                                               [ns (into {} (remove (comp :body val) words))])))
                       (assoc :bindings {} :use own-use :in (:in (e/fresh-state))))))
  (when-not (str/blank? text)
    (e/run-text [] text))
  nil)
