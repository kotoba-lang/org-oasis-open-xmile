(ns xmile.execute
  "A pure fixed-step simulator for xmile.model stock-and-flow models: Euler
  or classical RK4 integration of the stock ODE system defined by the
  model's flow/aux network (sec 3.1, 3.7.1). Assumes the model has already
  passed xmile.validate/validate with no :error problems -- a model with
  algebraic loops or dangling refs will throw here rather than silently
  produce wrong numbers.

  Scope (see README Follow-ups for the v2 list): scalar (non-array) stocks
  of :xmile/stock-type :stock only -- conveyor/queue transport is not
  simulated. Only :xmile/method :euler/:rk4 are implemented."
  (:require [clojure.set :as set]
            [xmile.model :as m]
            [xmile.expr :as expr]))

(defn- m-round [x] #?(:clj (Math/round (double x)) :cljs (js/Math.round x)))

;; --- dependency ordering among flow/aux (same construction as
;;     xmile.validate/non-stock-deps, kept local and cycle-fatal here) ---

(defn- non-stock-names [model]
  (into #{} (map :xmile/name (concat (m/flows model) (m/auxs model)))))

(defn- parsed-eqn [v] (expr/parse (:xmile/eqn v)))

(defn- deps-of [nsn v]
  (set/intersection nsn (expr/free-vars (parsed-eqn v))))

(defn topo-order
  "Names of all :flow/:aux variables, dependency-first. Throws if the
  flow/aux subgraph has a cycle (an illegal algebraic loop -- xmile.validate
  should have already caught this; execute never guesses an order)."
  [model]
  (let [nsn (non-stock-names model)
        deps (into {} (for [v (concat (m/flows model) (m/auxs model))]
                         [(:xmile/name v) (deps-of nsn v)]))
        order (atom [])
        color (atom {})]
    (letfn [(visit [n]
              (case (get @color n :white)
                :black nil
                :gray (throw (ex-info "xmile.execute: algebraic loop" {:at n}))
                :white (do (swap! color assoc n :gray)
                           (doseq [d (get deps n)] (visit d))
                           (swap! color assoc n :black)
                           (swap! order conj n))))]
      (doseq [n (keys deps)] (visit n))
      @order)))

;; --- per-step evaluation ---

(defn- clamp-non-negative [v value] (if (:xmile/non-negative? v) (max 0.0 value) value))

(defn eval-non-stocks
  "{name -> value} for every :flow/:aux, evaluated in dependency order
  `order`, given current `stock-vals` ({name -> value}), sim time `t`, and
  step size `dt`. `extra-env` merges in on top of TIME/DT (reserved for
  future host-injected values; unused in v1)."
  ([model order stock-vals t dt] (eval-non-stocks model order stock-vals t dt {}))
  ([model order stock-vals t dt extra-env]
   (reduce
    (fn [env nm]
      (let [v (m/lookup model nm)
            raw (expr/eval-expr (parsed-eqn v) env)
            val (clamp-non-negative v raw)]
        (assoc env nm val)))
    (merge {"TIME" t "DT" dt} extra-env stock-vals)
    order)))

(defn derivatives
  "{stock-name -> d(stock)/dt} at (t, stock-vals): sum of inflow values
  minus sum of outflow values, each already flow-non-negative-clamped by
  eval-non-stocks."
  [model order stock-vals t dt]
  (let [env (eval-non-stocks model order stock-vals t dt)]
    (into {}
          (for [s (m/stocks model)]
            [(:xmile/name s)
             (- (reduce + 0.0 (map #(get env %) (:xmile/inflows s)))
                (reduce + 0.0 (map #(get env %) (:xmile/outflows s))))]))))

;; --- initial stock values (sec 3.1.1: a stock's <eqn> is its initial value) ---

(defn initial-stocks
  "{stock-name -> initial value}, evaluating each stock's :xmile/eqn once at
  t = sim-specs :xmile/start, in dependency order among stocks themselves
  (a stock's initial-value equation may reference another stock's initial
  value or a constant, but not a flow/aux -- flows aren't meaningfully
  defined before stocks exist)."
  [model]
  (let [ss (:xmile/sim-specs model)
        t (:xmile/start ss) dt (:xmile/dt ss 1.0)
        stock-names (into #{} (map :xmile/name (m/stocks model)))
        deps (into {} (for [s (m/stocks model)]
                        [(:xmile/name s) (set/intersection stock-names (expr/free-vars (parsed-eqn s)))]))
        order (atom []) color (atom {})]
    (letfn [(visit [n]
              (case (get @color n :white)
                :black nil
                :gray (throw (ex-info "xmile.execute: cyclic stock initial values" {:at n}))
                :white (do (swap! color assoc n :gray)
                           (doseq [d (get deps n)] (visit d))
                           (swap! color assoc n :black)
                           (swap! order conj n))))]
      (doseq [n (keys deps)] (visit n)))
    (reduce (fn [env nm]
              (let [s (m/lookup model nm)
                    raw (expr/eval-expr (parsed-eqn s) (merge {"TIME" t "DT" dt} env))]
                (assoc env nm (clamp-non-negative s raw))))
            {}
            @order)))

;; --- integration ---

(defn- vec+ [a b scale] (into {} (for [[k v] a] [k (+ v (* scale (get b k 0.0)))])))

(defn- euler-step [model order stock-vals t dt]
  (let [d (derivatives model order stock-vals t dt)]
    (vec+ stock-vals d dt)))

(defn- rk4-step [model order stock-vals t dt]
  (let [k1 (derivatives model order stock-vals t dt)
        y2 (vec+ stock-vals k1 (/ dt 2.0))
        k2 (derivatives model order y2 (+ t (/ dt 2.0)) dt)
        y3 (vec+ stock-vals k2 (/ dt 2.0))
        k3 (derivatives model order y3 (+ t (/ dt 2.0)) dt)
        y4 (vec+ stock-vals k3 dt)
        k4 (derivatives model order y4 (+ t dt) dt)]
    (into {} (for [[nm y] stock-vals]
               [nm (+ y (* (/ dt 6.0)
                           (+ (get k1 nm 0.0) (* 2 (get k2 nm 0.0))
                              (* 2 (get k3 nm 0.0)) (get k4 nm 0.0))))]))))

(defn- clamp-stocks [model stock-vals]
  (into {} (for [[nm v] stock-vals] [nm (clamp-non-negative (m/lookup model nm) v)])))

(defn- assert-executable! [model]
  (doseq [s (m/stocks model)]
    (when (m/special-stock? s)
      (throw (ex-info (str "xmile.execute: " (:xmile/name s) " is a "
                            (name (:xmile/stock-type s)) " stock -- not yet simulated")
                       {:stock (:xmile/name s)}))))
  (doseq [v (m/variables model)]
    (when (m/dimensioned? v)
      (throw (ex-info (str "xmile.execute: " (:xmile/name v) " is array-dimensioned -- not yet simulated")
                       {:variable (:xmile/name v)}))))
  (let [method (get-in model [:xmile/sim-specs :xmile/method] :euler)]
    (when-not (contains? #{:euler :rk4} method)
      (throw (ex-info (str "xmile.execute: unsupported :xmile/method " method) {:method method})))))

(defn run
  "Simulate `model` from :xmile/start to :xmile/stop by :xmile/dt.
  Returns {:xmile/times [t0 t1 ...] :xmile/series {name -> [v0 v1 ...]}}
  covering every stock/flow/aux, one row per recorded time (inclusive of
  both endpoints)."
  [model]
  (assert-executable! model)
  (let [ss (:xmile/sim-specs model)
        start (:xmile/start ss) stop (:xmile/stop ss) dt (:xmile/dt ss 1.0)
        method (:xmile/method ss :euler)
        order (topo-order model)
        n (long (m-round (/ (- stop start) dt)))
        var-names (m/variable-names model)
        step-fn (case method :euler euler-step :rk4 rk4-step)]
    (loop [i 0 stock-vals (initial-stocks model) times [] rows []]
      (let [t (+ start (* i dt))
            env (eval-non-stocks model order stock-vals t dt)]
        (if (= i n)
          {:xmile/times (conj times t)
           :xmile/series (into {}
                                (for [nm var-names]
                                  [nm (mapv #(get % nm) (conj rows env))]))}
          (let [next-vals (clamp-stocks model (step-fn model order stock-vals t dt))]
            (recur (inc i) next-vals (conj times t) (conj rows env))))))))
