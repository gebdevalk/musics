(ns gui.global
  (:import
    (javafx.beans.value ChangeListener ObservableValue)
    (javafx.embed.swing JFXPanel)
    (javafx.event EventHandler)
    (javafx.util StringConverter)))

(defonce force-toolkit-init (JFXPanel.))

(defonce show-labels true)

(defn event-handler*
  [f]
  (reify EventHandler
    (handle [this e] (f e))))

(defmacro event-handler
  [arg & body]
  `(event-handler* (fn ~arg ~@body)))

(defn add-change-listener
  [component f & args]
  (let [listener
        (proxy [ChangeListener] []
          (changed [^ObservableValue ov
                    old-state
                    new-state]
            (if (not= new-state old-state)
              (apply f new-state args))))]
    (.addListener component listener)))

(defn add-change-listener-double
  [component f & args]
  (let [listener
        (proxy [ChangeListener] []
          (changed [^ObservableValue ov
                    ^Double old-state
                    ^Double new-state]
            (if (not= new-state old-state)
              (apply f new-state args))))]
    (.addListener component listener)))

(defn add-change-listener-str
  [component f & args]
  (let [listener
        (proxy [ChangeListener] []
          (changed [^ObservableValue ov
                    ^String old-state
                    ^String new-state]
            (if (not= new-state old-state)
              (apply f new-state args))))]
    (.addListener component listener)))

(defn add-slide-label-converter
  [component f & args]
  (if (not (nil? f))
    (let [converter
          (proxy [StringConverter] []
            (toString [^Double n]
              (apply f n args)))]
      (.setLabelFormatter component converter))))
