(ns xmile.execute-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [xmile.model :as m]
            [xmile.execute :as ex]))

(defn- close? [a b tol] (< (Math/abs (- a b)) tol))

(defn- bathtub [method]
  (-> (m/model "bathtub" {:xmile/sim-specs (m/sim-specs 0.0 100.0 {:xmile/dt 1.0 :xmile/method method})})
      (m/add-variable (m/stock "Inventory" "100" {:xmile/inflows #{"Production"}
                                                   :xmile/outflows #{"Shipping"}}))
      (m/add-variable (m/flow "Production" "10"))
      (m/add-variable (m/flow "Shipping" "Inventory / 4"))))

(deftest bathtub-converges-to-equilibrium
  (testing "dS/dt = 10 - S/4 has a stable fixed point at S=40 regardless of method/dt"
    (doseq [method [:euler :rk4]]
      (let [result (ex/run (bathtub method))
            final (last (get-in result [:xmile/series "Inventory"]))]
        (is (close? 40.0 final 0.01) (str method " final=" final))))))

(defn- exponential-growth [method dt]
  (-> (m/model "growth" {:xmile/sim-specs (m/sim-specs 0.0 10.0 {:xmile/dt dt :xmile/method method})})
      (m/add-variable (m/stock "S" "100" {:xmile/inflows #{"Growth"}}))
      (m/add-variable (m/flow "Growth" "S * 0.1"))))

(deftest exponential-growth-matches-analytic-solution
  (let [exact (* 100.0 (Math/exp 1.0))]                    ; S(t) = S0 * e^(r*t), r*t = 0.1*10 = 1
    (testing "rk4 (dt=0.1) is much closer to the analytic solution than euler"
      (let [rk4-final   (last (get-in (ex/run (exponential-growth :rk4 0.1)) [:xmile/series "S"]))
            euler-final (last (get-in (ex/run (exponential-growth :euler 0.1)) [:xmile/series "S"]))]
        (is (close? exact rk4-final (* exact 1.0e-4)))
        (is (close? exact euler-final (* exact 1.0e-2)))
        (is (< (Math/abs (- exact rk4-final)) (Math/abs (- exact euler-final))))))))

(deftest non-negative-clamp
  (let [model (-> (m/model "drain" {:xmile/sim-specs (m/sim-specs 0.0 3.0 {:xmile/dt 1.0})})
                  (m/add-variable (m/stock "S" "5" {:xmile/outflows #{"Out"} :xmile/non-negative? true}))
                  (m/add-variable (m/flow "Out" "10")))
        series (get-in (ex/run model) [:xmile/series "S"])]
    (is (every? #(>= % 0.0) series))
    (is (= 0.0 (last series)))))

(deftest pulse-adds-exactly-its-magnitude
  (testing "PULSE(20,2) is 20/DT for one DT-wide tick -- Euler-integrating it adds exactly 20"
    (let [model (-> (m/model "pulse" {:xmile/sim-specs (m/sim-specs 0.0 5.0 {:xmile/dt 1.0 :xmile/method :euler})})
                    (m/add-variable (m/stock "S" "0" {:xmile/inflows #{"P"}}))
                    (m/add-variable (m/flow "P" "PULSE(20, 2)")))
          result (ex/run model)
          s (get-in result [:xmile/series "S"])
          times (:xmile/times result)]
      (is (= [0.0 1.0 2.0 3.0 4.0 5.0] times))
      (is (= 0.0 (nth s 2)))     ; before the pulse takes effect
      (is (= 20.0 (nth s 3)))    ; the tick after the pulse's inflow was integrated
      (is (= 20.0 (last s))))))

(deftest step-and-flow-composition
  (let [model (-> (m/model "step" {:xmile/sim-specs (m/sim-specs 0.0 6.0 {:xmile/dt 1.0 :xmile/method :euler})})
                  (m/add-variable (m/stock "S" "0" {:xmile/inflows #{"In"}}))
                  (m/add-variable (m/flow "In" "STEP(3, 2)")))
        s (get-in (ex/run model) [:xmile/series "S"])]
    ;; In=0 for t<2, In=3 for t>=2; S accumulates 3/step from t=2 onward.
    (is (= [0.0 0.0 0.0 3.0 6.0 9.0 12.0] s))))

(deftest not-yet-executable-throws
  (let [model (-> (m/model "arr" {:xmile/sim-specs (m/sim-specs 0.0 1.0)})
                  (m/add-variable (m/stock "S" "0" {:xmile/stock-type :conveyor :xmile/length 5})))]
    (is (thrown? #?(:clj Exception :cljs js/Error) (ex/run model)))))
