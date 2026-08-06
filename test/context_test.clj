(ns ^:domain context-test
  "Tests for core.domain.context -- envelopes, ctx-append/ctx-value-chain.
   Run: lein test context-test"
  (:require [clojure.test :refer [deftest is testing]]
            [core.domain.context :as c]))

(deftest env-append-replaces-same-instant-across-numeric-types
  ;; Regression coverage: env-append used to compare the new point's
  ;; time against the last point's time with =, which is false across
  ;; the numeric tower even for an equal value ((= 0.0 0) is false in
  ;; Clojure, only == compares numerically) -- confirmed directly. A
  ;; write at the same instant but a different numeric type (a very
  ;; real case: context-root seeds every default at a literal 0.0
  ;; double, while core.domain.flat-domain/duration reports a plain 0
  ;; long for an empty container) silently accumulated as a second
  ;; point instead of replacing the first, and env-get's own before-
  ;; the-first-point shortcut then returned the stale original value
  ;; when sampled at that exact instant.
  (testing "double then long at the same instant"
    (let [env (c/envelope)]
      (c/env-append env 0.0 :root :fixed)
      (c/env-append env 0 :written :fixed)
      (is (= 1 (count @(:points-atom env))) "second write replaced, not accumulated")
      (is (= :written (c/env-get env 0)))
      (is (= :written (c/env-get env 0.0)))))
  (testing "long then ratio at the same instant"
    (let [env (c/envelope)]
      (c/env-append env 0 :first :fixed)
      (c/env-append env 0/1 :second :fixed)
      (is (= 1 (count @(:points-atom env))))
      (is (= :second (c/env-get env 0)))))
  (testing "still replaces for genuinely identical types too"
    (let [env (c/envelope)]
      (c/env-append env 1.0 :a :fixed)
      (c/env-append env 1.0 :b :fixed)
      (is (= 1 (count @(:points-atom env))))
      (is (= :b (c/env-get env 1.0))))))

(deftest ctx-value-chain-sees-a-same-instant-overwrite
  (testing "a value set at time 0 on a fresh context is visible at time 0, not shadowed by a differently-typed root default"
    (let [root (c/context-root {"key" :root-default})
          ctx  (c/context)]
      (c/ctx-append ctx :key 0 :written :fixed)
      (is (= :written (c/ctx-value-chain [ctx root] :key 0))))))

(deftest ctx-shift-rebases-envelope-points-without-mutating-the-original
  ;; ctx-shift exists to fix a real bug: a container's own envelope is
  ;; built at parse time with LOCAL, zero-based time (flat-tree-walker's
  ;; (duration state)), but the same repo container can be played after
  ;; other material -- alone, or as the second+ item in (play :a :b) --
  ;; so at play time its points have to be rebased into whatever absolute
  ;; timeline structural-time is already using. See async-engine's
  ;; build-chain, the actual caller.
  (testing "every point's time is shifted, value/ip untouched"
    (let [ctx (c/context)]
      (c/ctx-append ctx :volume 0 30 :lin-up)
      (c/ctx-append ctx :volume 1 80 :fixed)
      (let [shifted (c/ctx-shift ctx 8)]
        (is (= 30 (c/ctx-value-chain [shifted] :volume 8))
            "the first point now lands at the shift offset")
        (is (= 55.0 (c/ctx-value-chain [shifted] :volume 8.5))
            "interpolation midpoint moved along with it")
        (is (= 80 (c/ctx-value-chain [shifted] :volume 9))))))
  (testing "the original context is never mutated"
    (let [ctx (c/context)]
      (c/ctx-append ctx :volume 0 30 :lin-up)
      (c/ctx-shift ctx 8)
      (is (= 30 (c/ctx-value-chain [ctx] :volume 0))
          "querying the original at its own local time is unaffected")))
  (testing "a zero offset returns the same context, not a copy"
    (let [ctx (c/context)]
      (is (identical? ctx (c/ctx-shift ctx 0))))))
