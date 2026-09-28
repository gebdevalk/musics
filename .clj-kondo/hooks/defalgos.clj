(ns hooks.defalgos
  "Teach clj-kondo that (defalgos id spec ...) defines each id -- a
   variadic algo, whatever the raw fn's own arity -- plus `algos`,
   so their later uses don't lint as unresolved or wrong-arity.")

(defmacro defalgos [& specs]
  `(do ~@(for [[id spec] (partition 2 specs)]
           `(def ~id (fn [& _#] ~(if (vector? spec) (first spec) spec) nil)))
       (declare ~'algos)))
