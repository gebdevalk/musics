(ns musics.version
  "mu's version, 0.3.<merges>.<commits>: the merges of develop into main
   (main's first-parent merges) and the commits on develop, counted by git
   in the repo mu runs from."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- git-count
  "git rev-list --count with args, or nil when git can't tell."
  [& args]
  (let [{:keys [exit out]} (try (apply sh/sh "git" "rev-list" "--count" args)
                                (catch java.io.IOException _ {:exit 1}))]
    (when (zero? exit) (parse-long (str/trim out)))))

(def version
  "0.3.65.807, say; a ? for a count git can't give (no repo, no branch)."
  (delay (str "0.3." (or (git-count "--first-parent" "--merges" "main") "?")
              "." (or (git-count "develop") "?"))))
