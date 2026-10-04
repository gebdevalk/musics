(ns core.assist
  "What can I do now, and how do I get where I want to be -- worked out
   from facts, not stored as sentences.

   Each REPL action is one entry in `actions`: what it needs, what it
   gives, how to call it. The facts that hold are read fresh: the
   current state (`state`) plus history -- what the actions in the
   activity log gave that no state shows (a tree made, a file
   rendered). core.logic relates facts and actions:

     actiono   an action with its needs and gives
     achieveo  a plan (actions in order) that makes a fact hold
     missingo  a need of an action that doesn't hold

   so one table answers what's possible now (`now`), how to reach a
   fact or take an action (`plan`), why an action can't be taken yet
   (`why-not`) and what gives a fact (`providers`). musics.core/assist
   prints them; the GUI colours and disables its buttons with them.

   Every action is recorded with core.registries/log! by the function
   its :var names, under its own key or :logs (test/assist_test.clj
   holds that line)."
  (:refer-clojure :exclude [==])
  (:require [clojure.core.logic :refer [== all conde conso emptyo fresh membero
                                        nafc or* run run* fail]]
            [core.engine :as engine]
            [core.registries :as reg]
            [core.repo :as repo]
            [core.wall :as wall]))

(def actions
  "The REPL actions, in the order they are suggested."
  [{:action :parse      :needs []                 :gives [:committed]
    :var 'musics.core/parse     :call "(parse \"[name: c4 d e f]\")"   :doc "commit musics text"}
   {:action :play       :needs [:committed]       :gives [:playing :played]
    :var 'musics.core/play      :call "(play :name)"                   :doc "play it (connects first)"}
   {:action :play-add   :needs [:committed :playing] :gives []
    :var 'musics.core/play-add  :call "(play-add :other)"              :doc "join what's playing"}
   {:action :pause!     :needs [:playing]         :gives [:paused]
    :var 'musics.core/pause!    :call "(pause!)"                       :doc "hold playback"}
   {:action :resume!    :needs [:paused]          :gives []
    :var 'musics.core/resume!   :call "(resume!)"                      :doc "go on from the pause"}
   {:action :stop!      :needs [:playing]         :gives []
    :var 'musics.core/stop!     :call "(stop!)"                        :doc "stop everything"}
   {:action :render     :needs [:committed]       :gives [:rendered]
    :var 'musics.core/render    :call "(render :name \"name.mid\")"    :doc "write a MIDI file"}
   {:action :build-tree :needs []                 :gives [:tree :tctx]
    :var 'algo.tree/build-tree  :call "(def tt (build-tree))"          :doc "compose an algo tree by drag and drop -> [tree tctx]"}
   {:action :tctx       :needs [:tree]            :gives [:tctx]
    :var 'algo.tree/tctx        :call "(def tc (t/tctx tree))"         :doc "settings for a tree"}
   {:action :gui        :needs [:tree]            :gives [:tctx]
    :var 'algo.tree/gui         :call "(def tc (gui tree))"            :doc "settings window with a preview"}
   {:action :live!      :needs [:tree :tctx]      :gives [:live :playing :played]
    :var 'algo.tree/live!       :call "(t/live! :riff tree tc)"        :doc "play a tree live; settings heard at once"}
   {:action :play-algo  :needs [:committed :live] :gives [:playing :played] :logs :play
    :var 'musics.core/play      :call "(play :name :algo :riff)"       :doc "play material through a live tree"}
   {:action :connect    :needs []                 :gives [:connected]
    :var 'musics.core/connect   :call "(connect)"                      :doc "open MIDI (play does it for you)"}])

(def ^:private by-action (into {} (map (juxt :action identity)) actions))

(def observable
  "Facts read from the system itself; any other fact is history."
  #{:committed :connected :playing :paused :live})

;; ---------------------------------------------------------------------------
;; Facts
;; ---------------------------------------------------------------------------

(defn- connected? []
  (some-> (resolve 'musics.core/receiver) deref deref some?))

(defn state
  "The observable facts that hold now."
  []
  (let [eng engine/*engine*]
    (cond-> #{}
      (seq (:children (repo/current :ROOT)))           (conj :committed)
      (connected?)                                     (conj :connected)
      (and eng (seq @(:voices eng)))                   (conj :playing)
      (and eng (= :paused @(:state eng)))              (conj :paused)
      (seq (wall/algos))                               (conj :live))))

(defn history
  "Facts the logged actions needed or gave that no state shows -- an
   action taken proves its needs held, so (t/tctx tree) shows a tree
   exists, even one written by hand."
  []
  (into #{} (comp (map :action) (keep by-action) (mapcat #(concat (:needs %) (:gives %)))
                  (remove observable))
        @reg/*activity-log*))

(defn facts
  "Every fact that holds: the state now plus history."
  []
  (into (state) (history)))

(defn last-action [] (:action (peek @reg/*activity-log*)))

;; ---------------------------------------------------------------------------
;; Relations
;; ---------------------------------------------------------------------------

(defn- actiono
  "a is an action with needs and gives (vectors)."
  [a needs gives]
  (or* (for [{:keys [action] n :needs g :gives} actions]
         (all (== a action) (== needs n) (== gives g)))))

(declare all-achieveo)

(defn- achieveo
  "plan (actions in order) makes fact f hold, starting from have, at
   most depth actions deep."
  [have depth f plan]
  (conde
    [(membero f have) (emptyo plan)]
    [(nafc membero f have)
     (if (pos? depth)
       (fresh [a needs gives sub]
         (actiono a needs gives)
         (membero f gives)
         (all-achieveo have (dec depth) needs sub)
         (conso a sub plan))                     ; reversed: the action first, its needs after
       fail)]))

(defn- all-achieveo [have depth fs plan]
  (conde
    [(emptyo fs) (emptyo plan)]
    [(fresh [f more p1 p2]
       (conso f more fs)
       (achieveo have depth f p1)
       (all-achieveo have depth more p2)
       (== plan [p1 p2]))]))

(defn- missingo
  "n is a need of action a that doesn't hold."
  [have a n]
  (fresh [needs gives]
    (actiono a needs gives)
    (membero n needs)
    (nafc membero n have)))

(defn- order
  "A plan term (nested, each action before its needs) as actions in the
   order to take them, each once."
  [term]
  (letfn [(walk [t] (cond (keyword? t) [t]
                          (sequential? t) (if (keyword? (first t))
                                            (concat (mapcat walk (rest t)) [(first t)])
                                            (mapcat walk t))
                          :else []))]
    (vec (distinct (walk term)))))

;; ---------------------------------------------------------------------------
;; Questions
;; ---------------------------------------------------------------------------

(defn action [k] (by-action k))

(defn possible?
  "Every need of action k holds."
  ([k] (possible? (facts) k))
  ([have k] (empty? (run* [n] (missingo (vec have) k n)))))

(defn why-not
  "The needs of action k that don't hold (empty when it is possible)."
  ([k] (why-not (facts) k))
  ([have k] (vec (distinct (run* [n] (missingo (vec have) k n))))))

(defn providers
  "The actions that give fact f, in table order."
  [f]
  (vec (distinct (run* [a] (fresh [needs gives] (actiono a needs gives) (membero f gives))))))

(defn- redone
  "How many facts plan p gives that already hold -- the tie-break between
   equally short plans, so a tree made already isn't made again."
  [have p]
  (count (filter (set have) (mapcat (comp :gives by-action) p))))

(defn plan
  "The shortest list of actions, in order, that makes `goal` hold -- a
   fact, or an action (taken last). [] when it already holds, nil when
   nothing reaches it."
  ([goal] (plan (facts) goal))
  ([have goal]
   (let [have (vec have)]
     (if-let [{:keys [needs]} (by-action goal)]
       (when-let [p (plan have needs)] (conj (vec (remove #{goal} p)) goal))
       (let [goals (if (keyword? goal) [goal] (vec goal))]
         (->> (range 0 5)
              (some (fn [d] (seq (run 20 [p] (all-achieveo have d goals p)))))
              (map order)
              (sort-by (fn [p] [(count p) (redone have p)]))
              first))))))

(defn now
  "The actions possible now that change something -- or have nothing to
   give but are still worth taking (stop!, play-add) -- the one just
   taken left out, those following from it first."
  ([] (now (facts) (last-action)))
  ([have last]
   (let [have (set have)
         gave (set (:gives (by-action last)))
         ks   (for [{:keys [action gives]} actions
                    :when (and (not= action last)
                               (possible? have action)
                               (or (empty? gives) (some (complement have) gives)))]
                action)]
     (vec (sort-by #(if (some gave (:needs (by-action %))) 0 1) ks)))))

(defn next-step
  "The first action of the plan to hear something, nil when something is
   playing or nothing leads there."
  ([] (next-step (facts)))
  ([have] (first (plan have :playing))))
