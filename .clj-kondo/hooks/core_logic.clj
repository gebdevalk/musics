(ns hooks.core-logic
  "Teaches clj-kondo the binding shape of core.logic's run/run*.")

(defmacro run [n bindings & goals]
  `(let [_# ~n] (fn ~bindings ~@goals)))

(defmacro run* [bindings & goals]
  `(fn ~bindings ~@goals))
