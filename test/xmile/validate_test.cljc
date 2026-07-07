(ns xmile.validate-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [xmile.model :as m]
            [xmile.validate :as v]))

(defn- bathtub []
  (-> (m/model "bathtub" {:xmile/sim-specs (m/sim-specs 0 10 {:xmile/dt 1})})
      (m/add-variable (m/stock "Inventory" "100" {:xmile/inflows #{"Production"}
                                                   :xmile/outflows #{"Shipping"}}))
      (m/add-variable (m/flow "Production" "10"))
      (m/add-variable (m/flow "Shipping" "Inventory / 4"))))

(deftest valid-model-has-no-errors
  (let [problems (v/validate (bathtub))]
    (is (v/valid? problems))
    (is (empty? (v/errors problems)))))

(deftest bad-sim-specs
  (let [model (m/model "bad" {:xmile/sim-specs (m/sim-specs 10 0)})
        problems (v/validate model)]
    (is (not (v/valid? problems)))
    (is (some #(= :xmile/bad-sim-specs (:xmile/code %)) (v/errors problems))))
  (let [model (m/model "bad-dt" {:xmile/sim-specs (m/sim-specs 0 10 {:xmile/dt -1})})]
    (is (some #(= :xmile/bad-sim-specs (:xmile/code %)) (v/errors (v/validate model))))))

(deftest dangling-ref
  (let [model (-> (m/model "m" {:xmile/sim-specs (m/sim-specs 0 10)})
                  (m/add-variable (m/aux "A" "B + 1")))
        problems (v/validate model)]
    (is (some #(= :xmile/dangling-ref (:xmile/code %)) (v/errors problems)))))

(deftest unknown-flow-ref
  (let [model (-> (m/model "m" {:xmile/sim-specs (m/sim-specs 0 10)})
                  (m/add-variable (m/stock "S" "0" {:xmile/inflows #{"NoSuchFlow"}})))
        problems (v/validate model)]
    (is (some #(= :xmile/unknown-flow-ref (:xmile/code %)) (v/errors problems)))))

(deftest algebraic-loop
  (let [model (-> (m/model "m" {:xmile/sim-specs (m/sim-specs 0 10)})
                  (m/add-variable (m/aux "A" "B + 1"))
                  (m/add-variable (m/aux "B" "A + 1")))
        problems (v/validate model)]
    (is (some #(= :xmile/algebraic-loop (:xmile/code %)) (v/errors problems)))))

(deftest stock-referencing-flow-is-not-a-loop
  (testing "a flow depending on its own stock's CURRENT value is normal SD, not a same-tick cycle"
    (let [model (bathtub)
          problems (v/validate model)]
      (is (empty? (filter #(= :xmile/algebraic-loop (:xmile/code %)) problems))))))

(deftest unsupported-builtin-is-a-warning-not-an-error
  (let [model (-> (m/model "m" {:xmile/sim-specs (m/sim-specs 0 10)})
                  (m/add-variable (m/aux "A" "SMTH1(1, 3)")))
        problems (v/validate model)]
    (is (v/valid? problems))
    (is (some #(= :xmile/unsupported-builtin (:xmile/code %)) (v/warnings problems)))))

(deftest not-yet-executable-warnings
  (let [model (-> (m/model "m" {:xmile/sim-specs (m/sim-specs 0 10)})
                  (m/add-variable (m/stock "S" "0" {:xmile/stock-type :conveyor :xmile/length 5}))
                  (m/add-variable (m/aux "Arr" "1" {:xmile/dims ["Location"]})))
        problems (v/validate model)]
    (is (v/valid? problems))
    (is (>= (count (filter #(= :xmile/not-yet-executable (:xmile/code %)) (v/warnings problems))) 2))))

(deftest gf-shape
  (let [model (-> (m/model "m" {:xmile/sim-specs (m/sim-specs 0 10)})
                  (m/add-variable (m/aux "A" "1" {:xmile/gf (m/gf :continuous [1])})))
        problems (v/validate model)]
    (is (some #(= :xmile/bad-gf (:xmile/code %)) (v/errors problems)))))
