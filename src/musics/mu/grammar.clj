(ns musics.mu.grammar
  "mu's grammar: musics.ebnf with mu's own rules appended
   (resources/musics/mu.ebnf), starting at MuInput. One grammar for .mus
   text and mu, so a musical literal means in mu what it means in a
   file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta]))

(def ^:private grammar
  (str (slurp (io/resource "musics/input/musics.ebnf"))
       "\n"
       (slurp (io/resource "musics/mu.ebnf"))))

(def parser (insta/parser grammar :start :MuInput :string-ci false))

(defn parse
  "The parse tree of one input, or an instaparse failure."
  [text]
  (insta/parse parser text))

(defn failure? [x] (insta/failure? x))

(defn incomplete?
  "Is text an unfinished input -- does it fail only at its very end (an
   open bracket, '(, a definition without ;, an open string)? A failure
   earlier in the text is a real error, not an unfinished input."
  [text]
  (let [r (parse text)]
    (and (insta/failure? r)
         (>= (:index r) (count (str/trimr text))))))
