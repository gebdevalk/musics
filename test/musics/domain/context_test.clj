(ns ^:domain musics.domain.context-test
  "Tests for musics.domain.context -- envelopes, ctx-append/ctx-value-chain.
   Run: lein test musics.domain.context-test"
  (:require [clojure.test :refer [deftest is testing]]
            [musics.domain.context :as c]))

(deftest env-append-replaces-same-instant-across-numeric-types
  ;; Regression coverage: env-append used to compare the new point's
  ;; time against the last point's time with =, which is false across
  ;; the numeric tower even for an equal value ((= 0.0 0) is false in
  ;; Clojure, only == compares numerically) -- confirmed directly. A
  ;; write at the same instant but a different numeric type (a very
  ;; real case: context-root seeds every default at a literal 0.0
  ;; double, while musics.domain/duration reports a plain 0
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
      (c/ctx-append ctx "key" 0 :written :fixed)
      (is (= :written (c/ctx-value-chain [ctx root] "key" 0))))))

(deftest ctx-shift-rebases-envelope-points-without-mutating-the-original
  ;; ctx-shift exists to fix a real bug: a container's own envelope is
  ;; built at parse time with LOCAL, zero-based time (walker's
  ;; (duration state)), but the same repo container can be played after
  ;; other material -- alone, or as the second+ item in (play :a :b) --
  ;; so at play time its points have to be rebased into whatever absolute
  ;; timeline structural-time is already using. See async-engine's
  ;; build-chain, the actual caller.
  (testing "every point's time is shifted, value/ip untouched"
    (let [ctx (c/context)]
      (c/ctx-append ctx "volume" 0 30 :lin-up)
      (c/ctx-append ctx "volume" 1 80 :fixed)
      (let [shifted (c/ctx-shift ctx 8)]
        (is (= 30 (c/ctx-value-chain [shifted] "volume" 8))
            "the first point now lands at the shift offset")
        (is (= 55.0 (c/ctx-value-chain [shifted] "volume" 8.5))
            "interpolation midpoint moved along with it")
        (is (= 80 (c/ctx-value-chain [shifted] "volume" 9))))))
  (testing "the original context is never mutated"
    (let [ctx (c/context)]
      (c/ctx-append ctx "volume" 0 30 :lin-up)
      (c/ctx-shift ctx 8)
      (is (= 30 (c/ctx-value-chain [ctx] "volume" 0))
          "querying the original at its own local time is unaffected")))
  (testing "a zero offset returns the same context, not a copy"
    (let [ctx (c/context)]
      (is (identical? ctx (c/ctx-shift ctx 0))))))

(deftest a-generator-is-a-context-value-that-is-a-function-of-time
  (let [live  (atom 10)
        gen   (c/ctx-generate (c/context) "volume" (fn [t] (+ @live (* 2 t))))
        root  (c/context-root {"volume" 50 "pan" 0.0})
        chain [gen root]]
    (is (= 10 (c/ctx-value-chain chain "volume" 0)) "sampled at the time asked")
    (is (= 16 (c/ctx-value-chain chain "volume" 3)))
    (reset! live 20)
    (is (= 26 (c/ctx-value-chain chain "volume" 3)) "it follows a setting that changes while it plays")
    (is (= 0.0 (c/ctx-value-chain chain "pan" 3)) "a key it does not hold falls through to the root"))
  (testing "last before the root: what the music writes wins"
    (let [gen      (c/ctx-generate (c/context) "volume" (fn [_] 99))
          written  (c/context)
          _        (c/ctx-append written "volume" 0 70 :fixed)
          root     (c/context-root {"volume" 50})]
      (is (= 70 (c/ctx-value-chain [written gen root] "volume" 1)))
      (is (= 99 (c/ctx-value-chain [(c/context) gen root] "volume" 1)) "and what it does not write is the generator's")))
  (testing "first in the chain: it overrides what the music writes"
    (let [gen     (c/ctx-generate (c/context) "volume" (fn [_] 99))
          written (c/context)
          _       (c/ctx-append written "volume" 0 70 :fixed)]
      (is (= 99 (c/ctx-value-chain [gen written (c/context-root {"volume" 50})] "volume" 1)))))
  (testing "a shifted context re-bases the function like an envelope's points"
    (let [gen (c/ctx-generate (c/context) "volume" (fn [t] t))]
      (is (= 2 (c/ctx-value-chain [(c/ctx-shift gen 3)] "volume" 5))))))

(deftest sample-many-asks-a-generator-too
  (let [gen  (c/ctx-generate (c/context) "volume" (fn [t] (* 10 t)))
        root (c/context-root {"volume" 50 "pan" 0.0})]
    (is (= {"volume" 40 "pan" 0.0} (c/sample-many [(c/context) gen root] {"volume" 0 "pan" 1} 4)))
    (is (= {"volume" 10 "pan" 0.0} (c/sample-many [[gen 3] root] {"volume" 0 "pan" 1} 4))
        "an ancestor offset by 3 is asked at the local time 1")))

(deftest a-tctx-in-front-of-a-chain-answers-keywords-and-leaves-strings-alone
  (let [tctx    (-> (c/context)
                    (c/tctx-put :reps 4)
                    (c/tctx-put :arc (fn [t] (* 10 t)))
                    (c/tctx-put :density 7))
        root    (c/context-root {"volume" 50 "pan" 0.0 "density" 1})
        written (c/context)
        _       (c/ctx-append written "pan" 0 -1.0 :fixed)
        chain   [tctx written root]]
    (is (= 4 (c/ctx-value-chain chain :reps 3)) "a keyword is asked of the tctx")
    (is (= 30 (c/ctx-value-chain chain :arc 3)) "a function of time")
    (is (nil? (c/ctx-value-chain chain :nothing 3)) "a key nobody holds")
    (is (= 50 (c/ctx-value-chain chain "volume" 3)) "a string is a context's key: the tctx never answers it")
    (is (= -1.0 (c/ctx-value-chain chain "pan" 3)) "and the chain is as it was: the written context first")
    (is (= 1 (c/ctx-value-chain chain "density" 3)) "\"density\" and :density are two keys")
    (is (= 7 (c/ctx-value-chain chain :density 3)))
    (is (= {"volume" 50 :reps 4 :arc 30 "pan" -1.0}
           (c/sample-many chain {"volume" 0 :reps 0 :arc 0 "pan" 1} 3))
        "sample-many too")
    (is (nil? (c/ctx-value-chain [written root] :reps 3)) "no tctx on the chain: nothing")
    (is (thrown? Exception (c/ctx-append (c/context) :reps 0 1 :fixed)) "a context key is a string")
    (is (thrown? AssertionError (c/tctx-put (c/context) "reps" 1)) "a tctx key is a keyword"))
  (testing "the chain a voice holds sees a change of the tctx at once"
    (let [tctx  (c/tctx-put (c/context) :reps 8)
          chain [tctx (c/context-root {"volume" 50})]]
      (is (= 8 (c/ctx-value-chain chain :reps 1)))
      (c/tctx-put tctx :reps 2)
      (is (= 2 (c/ctx-value-chain chain :reps 1)))))
  (testing "a shifted tctx is shifted like any context"
    (let [tctx (c/tctx-put (c/context) :t identity)]
      (is (= 2 (c/ctx-value-chain [(c/ctx-shift tctx 3)] :t 5))))))
