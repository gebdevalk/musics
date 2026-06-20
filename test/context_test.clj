(ns context-test
  "Context + Envelope tests: inheritance, interpolation, reverse."
  (:require [clojure.test :refer [deftest is testing]]
            [core.domain.music-domain :as d]
            [input.reader.parser.music-parser :as p]))

(deftest root-context
  (testing "root has no parent"
    (let [root (d/context-root {"tempo" 120 "volume" 0.8})]
      (is (nil? (:parent root)))))
  (testing "reads values at time 0"
    (let [root (d/context-root {"tempo" 120 "volume" 0.8})]
      (is (= 120 (d/ctx-value root :tempo 0.0)))
      (is (= 0.8 (d/ctx-value root :volume 0.0))))))

(deftest inheritance
  (testing "child inherits from parent"
    (let [root  (d/context-root {"tempo" 120})
          child (d/context root)]
      (is (= 120 (d/ctx-value child :tempo 0.0)))))
  (testing "child overrides parent only from its timestamp"
    (let [root  (d/context-root {"tempo" 120})
          child (d/context root)]
      (d/ctx-append child :tempo 2.0 80 :fixed)
      (is (= 120 (d/ctx-value child :tempo 0.0))
          "before the override, parent value is used")
      (is (= 80 (d/ctx-value child :tempo 2.0))
          "at the override time, child value is used")))
  (testing "grandchild sees root"
    (let [root       (d/context-root {"tempo" 120})
          child      (d/context root)
          grandchild (d/context child)]
      (is (= 120 (d/ctx-value grandchild :tempo 0.0)))))
  (testing "nil for unknown key"
    (let [root (d/context-root {"tempo" 120})]
      (is (nil? (d/ctx-value root :nonexistent 0.0))))))

(deftest interpolation
  (testing "fixed holds constant"
    (let [root (d/context-root {"volume" 0.5})]
      (d/ctx-append root :volume 2.0 1.0 :fixed)
      (is (= 0.5 (d/ctx-value root :volume 0.0)))
      (is (= 1.0 (d/ctx-value root :volume 2.0)))
      (is (= 1.0 (d/ctx-value root :volume 5.0)))))
  (testing "step holds then jumps"
    (let [root (d/context-root {"volume" 0.0})]
      (d/ctx-append root :volume 1.0 1.0 :step)
      (is (= 0.0 (d/ctx-value root :volume 0.5)))
      (is (= 1.0 (d/ctx-value root :volume 1.5)))))
  (testing "lin-up crescendo"
    (let [root (d/context-root {"volume" 0.0})]
      (d/ctx-append root :volume 2.0 1.0 :lin-up)
      (let [v0 (double (d/ctx-value root :volume 0.0))
            vq (double (d/ctx-value root :volume 0.5))
            vm (double (d/ctx-value root :volume 1.0))
            ve (double (d/ctx-value root :volume 2.0))]
        (is (< -0.01 v0 0.01))
        (is (< 0.24 vq 0.26))
        (is (< 0.49 vm 0.51))
        (is (< 0.99 ve 1.01)))))
  (testing "lin-down decrescendo"
    (let [root (d/context-root {"volume" 1.0})]
      (d/ctx-append root :volume 2.0 0.0 :lin-down)
      (let [v0 (double (d/ctx-value root :volume 0.0))
            vm (double (d/ctx-value root :volume 1.0))
            ve (double (d/ctx-value root :volume 2.0))]
        (is (< 0.99 v0 1.01))
        (is (< 0.49 vm 0.51))
        (is (< -0.01 ve 0.01)))))
  (testing "smooth s-curve"
    (let [root (d/context-root {"volume" 0.0})]
      (d/ctx-append root :volume 2.0 1.0 :smooth)
      (let [mid (double (d/ctx-value root :volume 1.0))]
        (is (< 0.49 mid 0.51)))))
  (testing "ease-in slow start"
    (let [root (d/context-root {"volume" 0.0})]
      (d/ctx-append root :volume 2.0 1.0 :ease-in)
      (let [q (double (d/ctx-value root :volume 0.5))]
        (is (< q 0.25)))
      (let [ve (double (d/ctx-value root :volume 2.0))]
        (is (< 0.99 ve 1.01)))))
  (testing "ease-out slow end"
    (let [root (d/context-root {"volume" 0.0})]
      (d/ctx-append root :volume 2.0 1.0 :ease-out)
      (let [tq (double (d/ctx-value root :volume 1.5))]
        (is (> tq 0.75)))
      (let [ve (double (d/ctx-value root :volume 2.0))]
        (is (< 0.99 ve 1.01))))))

(deftest envelope-reverse
  (testing "reversed waveform mirrors forward"
    (let [fwd (d/envelope)]
      (d/env-append fwd 0.0 0.0 :fixed)
      (d/env-append fwd 2.0 1.0 :lin-up)
      (d/env-append fwd 4.0 0.0 :lin-down)
      (let [rev (d/env-reverse fwd)]
        (is (= 4.0 (d/env-duration rev)))
        (is (< -0.01 (double (d/env-get rev 0.0)) 0.01) "starts at 0")
        (let [t1 (double (d/env-get rev 1.0))
              t2 (double (d/env-get rev 2.0))
              t3 (double (d/env-get rev 3.0))
              t4 (double (d/env-get rev 4.0))]
          (is (< 0.49 t1 0.51) "ramp midpoint at t=1")
          (is (< 0.99 t2 1.01) "peak at t=2")
          (is (< 0.49 t3 0.51) "ramp down midpoint at t=3")
          (is (< -0.01 t4 0.01) "ends at 0")))))
  (testing "empty stays empty"
    (let [rev (d/env-reverse (d/envelope))]
      (is (d/env-empty? rev))))
  (testing "double reverse roundtrip"
    (let [orig (d/envelope)]
      (d/env-append orig 0.0 0.0 :fixed)
      (d/env-append orig 3.0 1.0 :lin-up)
      (let [rt (d/env-reverse (d/env-reverse orig))
            a  (double (d/env-get orig 0.0))
            b  (double (d/env-get rt 0.0))]
        (is (= (d/env-duration orig) (d/env-duration rt)))
        (is (< -0.01 (- a b) 0.01))))))

(deftest parser-integration
  (testing "score context has no parent"
    (let [score (:score (p/parse "c4"))]
      (is (nil? (:parent (:context score))))))
  (testing "leaf sees score tempo"
    (let [tokens (:tokens (p/parse "c4"))
          leaf (first (filter d/leaf? tokens))]
      (is (= 92 (d/ctx-value (:context leaf) :Tempo 0.0)))))
  (testing "container has parent context"
    (let [children (d/composite-children (:score (p/parse "{c4 d4}")))
          seq-ctx (:context (first children))]
      (is (some? (:parent seq-ctx))))))