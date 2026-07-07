(ns xmile.expr-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [xmile.expr :as expr]))

(defn- ev
  ([s] (ev s {}))
  ([s env] (expr/eval-eqn s (merge {"TIME" 0.0 "DT" 1.0} env))))

(deftest arithmetic-precedence
  (is (= 14.0 (ev "2 + 3 * 4")))
  (is (= 20.0 (ev "(2 + 3) * 4")))
  (is (= 512.0 (ev "2 ^ 3 ^ 2")))                 ; right-assoc: 2^(3^2) = 2^9
  (is (= -4.0 (ev "-2 ^ 2")))                      ; unary lower precedence than ^: -(2^2)
  (is (= 4.0 (ev "(-2) ^ 2")))
  (is (= 1.0 (ev "7 MOD 3")))
  (is (= 3.0 (ev "2 * 3 MOD 4 + 1"))))             ; left-to-right at one tier: ((2*3) MOD 4) + 1 = (6 MOD 4) + 1 = 3

(deftest relational-logical
  (is (= 1.0 (ev "3 < 4")))
  (is (= 0.0 (ev "3 > 4")))
  (is (= 1.0 (ev "3 <= 3")))
  (is (= 1.0 (ev "3 <> 4")))
  (is (= 1.0 (ev "1 AND 1")))
  (is (= 0.0 (ev "1 AND 0")))
  (is (= 1.0 (ev "0 OR 1")))
  (is (= 1.0 (ev "NOT 0")))
  (is (= 0.0 (ev "NOT 1"))))

(deftest conditional
  (is (= 10.0 (ev "IF 1 THEN 10 ELSE 20")))
  (is (= 20.0 (ev "IF 0 THEN 10 ELSE 20")))
  (is (= 5.0 (ev "IF Inventory < 0 THEN 0 ELSE 5" {"Inventory" 100.0}))))

(deftest refs-and-identifiers
  (is (= 42.0 (ev "Answer" {"Answer" 42.0})))
  (is (= 42.0 (ev "\"a name with spaces\"" {"a name with spaces" 42.0})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (ev "Unknown_Var"))))

(deftest math-builtins
  (is (= 3.0 (ev "ABS(-3)")))
  (is (= 2.0 (ev "SQRT(4)")))
  (is (= 4.0 (ev "MAX(4, 2)")))
  (is (= 2.0 (ev "MIN(4, 2)")))
  (is (= 3.0 (ev "INT(3.9)")))
  (is (= -4.0 (ev "INT(-3.1)")))                   ; floor semantics: next int <= x
  (is (< (Math/abs (- Math/PI (ev "PI"))) 1e-9)))

(deftest max-min-arity
  (is (thrown? #?(:clj Exception :cljs js/Error) (ev "MAX(1, 2, 3)"))))

(deftest test-inputs
  (testing "STEP"
    (is (= 0.0 (ev "STEP(6, 3)" {"TIME" 2.0})))
    (is (= 6.0 (ev "STEP(6, 3)" {"TIME" 3.0})))
    (is (= 6.0 (ev "STEP(6, 3)" {"TIME" 10.0}))))
  (testing "RAMP"
    (is (= 0.0 (ev "RAMP(2, 5)" {"TIME" 4.0})))
    (is (= 10.0 (ev "RAMP(2, 5)" {"TIME" 10.0}))))
  (testing "PULSE one-shot: PULSE(20,12,5) is 20/DT at time 12, 17, 22, ..."
    (is (= 20.0 (ev "PULSE(20, 12)" {"TIME" 12.0 "DT" 1.0})))
    (is (= 0.0 (ev "PULSE(20, 12)" {"TIME" 13.0 "DT" 1.0})))
    (is (= 0.0 (ev "PULSE(20, 12)" {"TIME" 17.0 "DT" 1.0}))))
  (testing "PULSE repeating"
    (is (= 20.0 (ev "PULSE(20, 12, 5)" {"TIME" 12.0 "DT" 1.0})))
    (is (= 20.0 (ev "PULSE(20, 12, 5)" {"TIME" 17.0 "DT" 1.0})))
    (is (= 0.0 (ev "PULSE(20, 12, 5)" {"TIME" 15.0 "DT" 1.0})))))

(deftest unsupported-builtins-throw
  (is (thrown? #?(:clj Exception :cljs js/Error) (ev "SMTH1(x, 3)" {"x" 1.0})))
  (is (contains? (expr/called-fns (expr/parse "DELAY1(x, 3)")) "DELAY1")))

(deftest free-vars-and-called-fns
  (is (= #{"a" "b"} (expr/free-vars (expr/parse "a + MAX(b, 0)"))))
  (is (= #{"MAX"} (expr/called-fns (expr/parse "a + MAX(b, 0)"))))
  (is (= #{} (expr/free-vars (expr/parse "TIME + DT")))))

(deftest parse-errors
  (is (thrown? #?(:clj Exception :cljs js/Error) (expr/parse "1 +")))
  (is (thrown? #?(:clj Exception :cljs js/Error) (expr/parse "1 2"))))
