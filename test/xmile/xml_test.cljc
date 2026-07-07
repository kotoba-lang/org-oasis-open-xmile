(ns xmile.xml-test
  "Round-trip tests use double literals throughout (0.0, not 0) because
  xmile.xml's wire format is text -- parse-num always yields a double, so a
  fixture built with integer literals would legitimately fail `=` after a
  round trip (Clojure's `=` distinguishes 50 from 50.0) even though nothing
  is actually broken."
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [xmile.model :as m]
            [xmile.xml :as xml]))

(deftest sim-specs-round-trip
  (doseq [ss [(m/sim-specs 0.0 10.0)
              (m/sim-specs 0.0 10.0 {:xmile/dt 0.25 :xmile/method :rk4 :xmile/time-units "months"})
              (m/sim-specs 0.0 10.0 {:xmile/dt 1.0 :xmile/dt-reciprocal? true})]]
    (is (= ss (xml/parse-sim-specs (xml/emit-sim-specs ss))))))

(deftest gf-round-trip
  (doseq [g [(m/gf :continuous [0.0 1.0 4.0 9.0] {:xmile/xpts [0.0 1.0 2.0 3.0]})
             (m/gf :discrete [0.0 1.0 4.0 9.0] {:xmile/xscale [0.0 3.0]})]]
    (is (= g (xml/parse-gf (xml/emit-gf g))))))

(deftest variable-round-trip
  (let [s (m/stock "Inventory" "100"
                    {:xmile/inflows #{"Production"} :xmile/outflows #{"Shipping"}
                     :xmile/units "widgets" :xmile/doc "on-hand inventory"
                     :xmile/non-negative? true})
        f (m/flow "Shipping" "Inventory / 4" {:xmile/non-negative? true})
        a (m/aux "Avg_Time" "4")
        conveyor (m/stock "Pipeline" "0" {:xmile/stock-type :conveyor :xmile/length 10.0
                                           :xmile/conveyor-discrete? false})
        queue (m/stock "Backlog" "0" {:xmile/stock-type :queue :xmile/capacity 50.0})]
    (doseq [v [s f a conveyor queue]]
      (is (= v (xml/parse-variable (xml/emit-variable v)))
          (str "round-trip failed for " (:xmile/name v))))))

(deftest model-round-trip
  (let [model (-> (m/model "bathtub" {:xmile/sim-specs (m/sim-specs 0.0 10.0 {:xmile/dt 1.0})})
                  (m/add-variable (m/stock "Inventory" "100" {:xmile/inflows #{"Production"}
                                                               :xmile/outflows #{"Shipping"}}))
                  (m/add-variable (m/flow "Production" "10"))
                  (m/add-variable (m/flow "Shipping" "Inventory / 4")))]
    (is (= model (xml/parse-model (xml/emit-model model))))))

(deftest dimensions-and-units-round-trip
  (let [dims {"Location" {:xmile/size 3.0 :xmile/elements ["Boston" "Chicago" "LA"]}
              "N" {:xmile/size 5.0}}
        units {"hours" {} "Miles_per_hour" {:xmile/unit-eqn "Miles/hours" :xmile/aliases ["mph"]}}]
    (is (= dims (xml/parse-dimensions (xml/emit-dimensions dims))))
    (is (= units (xml/parse-units (xml/emit-units units))))))

(deftest doc-round-trip
  (let [doc {:xmile/header {:xmile/vendor "kotoba-lang" :xmile/product {:xmile/name "xmile-clj" :xmile/version "1.0"}}
             :xmile/sim-specs (m/sim-specs 0.0 10.0 {:xmile/dt 1.0})
             :xmile/models [(-> (m/model "bathtub")
                                 (m/add-variable (m/stock "Inventory" "100"))
                                 (m/add-variable (m/aux "Avg_Time" "4")))]}]
    (is (= doc (xml/parse-doc (xml/emit-doc doc))))))
