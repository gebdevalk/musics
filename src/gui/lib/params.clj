(ns gui.lib.params
  "A settings window for one tctx -- one control per param, without the
   rest of the GUI:

     (gui tctx)        ; the tctx's params
     (gui tree)        ; makes a tctx for tree, returns it
     (gui tree tctx)   ; also the tree, a live preview, Play once, Live as

   The tctx is the model, both ways: a control calls t/setp! (so the
   validator checks it; a rejected value is shown, not applied), and a
   change made at the REPL moves the control. A tctx bound with t/live!
   is heard on the next note. One window per tctx; opening it again
   brings it to the front. Closing it stops watching the tctx."
  (:require [algo.tree :as t]
            [algo.tree.lib :as lib]
            [cljfx.api :as fx]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [gui.lib.components :as ui]
            [gui.lib.state :as state]
            [gui.lib.theme :as theme])
  (:import [javafx.application Platform]))

(defonce ^:private windows (atom {}))          ; tctx -> {:state :renderer}

(def ^:private preview-limit 32)

(defn- preview-text
  "The tree's result as text: notes as musics text, anything else
   printed -- at most preview-limit items of an endless result."
  [tree tctx]
  (try
    (let [v    (t/run tree tctx)
          more (and (seq? v) (not (counted? v)) (seq (drop preview-limit v)))
          xs   (if (sequential? v) (vec (take preview-limit v)) v)
          body (if (and (sequential? xs) (seq xs) (every? #(and (map? %) (:type %)) xs))
                 (lib/notes->mus xs)
                 (binding [*print-length* preview-limit] (pr-str xs)))]
      (str body (when more (str "\n… endless; the first " preview-limit " shown"))))
    (catch Exception e (str "does not run: " (ex-message e)))))

(defn- refresh!
  "Re-run the preview after `delay-ms` quiet: a slider drag re-runs the
   tree once it settles, not on every step."
  [st tree tctx]
  (when tree
    (let [g (:gen (swap! st update :gen (fnil inc 0)))]
      (future
        (Thread/sleep 120)
        (when (= g (:gen @st))
          (swap! st assoc :preview (preview-text tree tctx)))))))

(defn- groups
  "[algo [[key value spec] ...]] in the tctx's own param order."
  [{:keys [params specs]}]
  (->> (sort-by (comp :order val) specs)
       (partition-by (comp :algo val))
       (map (fn [kvs] [(:algo (val (first kvs)))
                       (for [[k spec] kvs] [k (get params k) spec])]))))

(defn- view [{:keys [tree-text tctx-value preview message live-name showing theme]}]
  {:fx/type :stage
   :showing showing
   :title (str "Params — " (or tree-text "tctx"))
   :width 620
   :height (if tree-text 720 520)
   :on-close-request {:event/type ::close}
   :scene
   {:fx/type :scene
    :stylesheets [(theme/stylesheet theme)]
    :root
    {:fx/type :v-box
     :spacing 8
     :style "-fx-padding: 8;"
     :children
     (concat
       (when tree-text
         [(ui/label {:text tree-text :style "-fx-font-family: monospace;"})])
       [(assoc (ui/scroll-pane
                 {:content
                  {:fx/type :v-box
                   :spacing 8
                   :children (vec (for [[algo rows] (groups tctx-value)]
                                    (ui/titled-panel
                                      {:title (name algo)
                                       :children (vec (for [[k v spec] rows]
                                                        (ui/param-control {} k v spec)))})))}})
               :v-box/vgrow :always)]
       (when tree-text
         [(ui/titled-panel
            {:title "Result"
             :children [(ui/text-area {:text (or preview "") :pref-row-count 6 :editable? false})]})
          (ui/button-row
            {:children [(ui/button {:text "Play once" :on-action {:event/type ::play}})
                        (ui/text-field {:text (or live-name "") :prompt "name, e.g. riff"
                                        :on-text-changed {:event/type ::live-name}})
                        (ui/button {:text "Live as" :on-action {:event/type ::live}})
                        (ui/button {:text "Stop" :on-action {:event/type ::stop}})]})])
       [(ui/label {:text (or message "")})
        (ui/button {:text "Close" :on-action {:event/type ::close}})])}}})

(declare close!)

(defn- report! [st f]
  (try (f) (swap! st assoc :message nil)
       (catch Exception e (swap! st assoc :message (ex-message e)))))

(defn- handler [st tree tctx]
  (fn [event]
    (case (:event/type event)
      (:set-tree-param :set-tree-param-text)
      (report! st #(let [v (ui/param-input event)]
                     (t/setp! tctx (:key event) (if (string? v) (edn/read-string v) v))))
      ::play      (report! st #(t/play! tree tctx))
      ::live-name (swap! st assoc :live-name (:fx/event event))
      ::live      (report! st #(let [nm (str/trim (or (:live-name @st) ""))]
                                 (when (str/blank? nm) (throw (ex-info "give the live name first" {})))
                                 (t/live! (keyword nm) tree tctx)
                                 (swap! st assoc :live (keyword nm))))
      ::stop      (report! st #(some-> (:live @st) t/stop!))
      ::close     (close! tctx)
      nil)))

(defn close!
  "Close `tctx`'s window and stop watching it (a live name keeps playing)."
  [tctx]
  (when-let [{:keys [state renderer]} (get @windows tctx)]
    (remove-watch tctx ::window)
    (swap! state assoc :showing false)
    (fx/unmount-renderer state renderer)
    (swap! windows dissoc tctx))
  nil)

(defn open!
  "Open a settings window: (open! tctx), (open! tree) -- a new tctx for
   it -- or (open! tree tctx). Returns the tctx."
  ([x] (if (t/tctx? x) (open! nil x) (open! x (t/tctx x))))
  ([tree tctx]
   (let [tree (some-> tree t/as-node)]
     (when tree (t/fit! tctx tree))
     (Platform/setImplicitExit false)             ; closing this window mustn't end JavaFX
     (if-let [{:keys [state]} (get @windows tctx)]
       (swap! state assoc :showing true)
       (let [st (atom {:tree-text  (some-> tree t/show pr-str)
                       :tctx-value @tctx
                       :showing    true
                       :theme      (:theme @state/*state :dark)})
             r  (fx/create-renderer
                  :middleware (fx/wrap-map-desc view)
                  :opts {:fx.opt/map-event-handler (handler st tree tctx)})]
         (add-watch tctx ::window (fn [_ _ _ new]
                                    (swap! st assoc :tctx-value new)
                                    (refresh! st tree tctx)))
         (swap! windows assoc tctx {:state st :renderer r})
         (refresh! st tree tctx)
         (fx/mount-renderer st r)))
     tctx)))
