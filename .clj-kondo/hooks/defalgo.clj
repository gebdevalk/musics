(ns hooks.defalgo
  "Teach clj-kondo what algo.tree's macros define: (defalgo name ...)
   defines the constructor `name` and the raw fn `name*`.")

(defmacro defalgo [nm & fdecl]
  (let [raw (symbol (str (name nm) "*"))]
    `(do (defn ~raw ~@fdecl)
         (def ~nm (fn [& _#] nil)))))
