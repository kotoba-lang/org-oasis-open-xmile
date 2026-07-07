(ns xmile.validate
  "Structural validation for xmile.model models, returning
  kotoba.dsl.problem-shaped problems (:xmile/severity :error|:warn).

  :error means the model is not valid XMILE (dangling reference, illegal
  algebraic loop, malformed sim_specs/gf, ...). :warn means the model IS
  valid XMILE but exercises a feature xmile.execute v1 does not yet
  simulate (arrays, conveyor/queue transport, DELAY*/SMTH*/TREND/FORCST,
  stochastic functions, integration methods other than euler/rk4) -- see
  README Follow-ups. A model with only :warn problems is spec-valid but
  xmile.execute/run will throw if you actually try to run it."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [kotoba.dsl.problem :as problem]
            [xmile.model :as m]
            [xmile.expr :as expr]))

(def domain :xmile)

(defn- err  [code subject msg] (problem/problem domain :error code subject msg))
(defn- warn [code subject msg] (problem/problem domain :warn code subject msg))

(defn- try-parse [eqn-str]
  (try {:ok (expr/parse eqn-str)}
       (catch #?(:clj Exception :cljs :default) ex
         {:error (ex-message ex)})))

(defn sim-specs-problems [model]
  (let [ss (:xmile/sim-specs model)]
    (if-not ss
      [(err :xmile/missing-sim-specs (:xmile/name model) "model has no :xmile/sim-specs")]
      (cond-> []
        (not (< (:xmile/start ss) (:xmile/stop ss)))
        (conj (err :xmile/bad-sim-specs ss "sim_specs :xmile/start must be < :xmile/stop"))
        (and (:xmile/dt ss) (not (pos? (:xmile/dt ss))))
        (conj (err :xmile/bad-sim-specs ss "sim_specs :xmile/dt must be > 0"))
        (not (contains? #{:euler :rk4} (:xmile/method ss :euler)))
        (conj (warn :xmile/unsupported-method (:xmile/method ss)
                     "xmile.execute v1 only implements :euler and :rk4 (sec 3.7.1 also allows rk2/rk45/gear)"))))))

(defn- eqn-string [v] (:xmile/eqn v))

(defn eqn-problems
  "Per-variable equation problems: parse errors, dangling references
  (sec 3.3), and calls to sec 3.5.2/3.5.3 built-ins xmile.expr does not
  implement (v2 scope-out)."
  [model]
  (let [names (m/variable-names model)
        known (into names #{"TIME" "DT"})]
    (mapcat
     (fn [v]
       (let [nm (:xmile/name v)
             parsed (try-parse (eqn-string v))]
         (if (:error parsed)
           [(err :xmile/eqn-parse-error nm (str "could not parse equation: " (:error parsed)))]
           (let [tree (:ok parsed)
                 dangling (remove known (expr/free-vars tree))
                 unsupported (filter expr/unsupported-builtins (expr/called-fns tree))]
             (concat
              (map #(err :xmile/dangling-ref [nm %] (str nm "'s equation references unknown identifier " %))
                   dangling)
              (map #(warn :xmile/unsupported-builtin [nm %]
                          (str nm "'s equation calls " % ", not implemented by xmile.expr (v2 scope-out)"))
                   unsupported))))))
     (m/variables model))))

(defn flow-ref-problems
  "Every stock :xmile/inflows/:xmile/outflows name must be an existing :flow variable."
  [model]
  (mapcat
   (fn [s]
     (let [nm (:xmile/name s)]
       (for [fname (into (:xmile/inflows s #{}) (:xmile/outflows s #{}))
             :let [target (m/lookup model fname)]
             :when (not (and target (m/flow? target)))]
         (err :xmile/unknown-flow-ref [nm fname]
              (str nm " names " fname " as an inflow/outflow, but it is not a known :flow variable")))))
   (m/stocks model)))

(defn- non-stock-deps
  "{name -> #{names it depends on}} restricted to :flow/:aux variables --
  a stock's current value is already known at the start of a step, so an
  edge INTO a stock is not a same-tick ordering hazard; only cycles among
  flow/aux equations are illegal (sec 3.3 implies whole-model equations
  must be evaluable, i.e. acyclic once stocks are treated as known)."
  [model]
  (let [non-stock-names (into #{} (map :xmile/name (concat (m/flows model) (m/auxs model))))]
    (into {}
          (for [v (concat (m/flows model) (m/auxs model))
                :let [parsed (try-parse (eqn-string v))]
                :when (:ok parsed)]
            [(:xmile/name v) (set/intersection non-stock-names (expr/free-vars (:ok parsed)))]))))

(defn algebraic-loop-problems
  "Cycle-detect the flow/aux dependency graph via DFS (white/gray/black)."
  [model]
  (let [deps (non-stock-deps model)
        color (atom {})
        cycle (atom nil)]
    (letfn [(visit [n path]
              (when-not @cycle
                (case (get @color n :white)
                  :black nil
                  :gray (reset! cycle (conj path n))
                  :white (do (swap! color assoc n :gray)
                             (doseq [d (get deps n)] (visit d (conj path n)))
                             (swap! color assoc n :black)))))]
      (doseq [n (keys deps)] (visit n []))
      (if @cycle
        [(err :xmile/algebraic-loop @cycle
              (str "illegal algebraic loop (no stock breaks the cycle): " (str/join " -> " @cycle)))]
        []))))

(defn gf-problems [model]
  (keep (fn [v]
          (when-let [gf (:xmile/gf v)]
            (let [xpts (:xmile/xpts gf) ypts (:xmile/ypts gf)]
              (cond
                (nil? ypts) (err :xmile/bad-gf (:xmile/name v) "gf has no :xmile/ypts")
                (< (count ypts) 2) (err :xmile/bad-gf (:xmile/name v) "gf :xmile/ypts needs >= 2 points")
                (and xpts (not= (count xpts) (count ypts)))
                (err :xmile/bad-gf (:xmile/name v) "gf :xmile/xpts and :xmile/ypts must be the same length")))))
        (m/variables model)))

(defn not-yet-executable-problems
  "Sec 3.7.2/3.7.3 conveyor/queue stocks and array-dimensioned (sec 4.5)
  variables are valid XMILE but xmile.execute v1 does not simulate them."
  [model]
  (concat
   (keep (fn [s] (when (m/special-stock? s)
                   (warn :xmile/not-yet-executable (:xmile/name s)
                         (str (:xmile/name s) " is a " (name (:xmile/stock-type s))
                              " stock -- transport semantics not yet simulated by xmile.execute"))))
         (m/stocks model))
   (keep (fn [v] (when (m/dimensioned? v)
                   (warn :xmile/not-yet-executable (:xmile/name v)
                         (str (:xmile/name v) " is array-dimensioned -- not yet simulated by xmile.execute"))))
         (m/variables model))))

(defn validate
  "All problems for `model`, most structural first."
  [model]
  (vec (concat (sim-specs-problems model)
               (flow-ref-problems model)
               (eqn-problems model)
               (algebraic-loop-problems model)
               (gf-problems model)
               (not-yet-executable-problems model))))

(defn errors   [problems] (problem/errors domain problems))
(defn warnings [problems] (problem/warnings domain problems))
(defn valid?   [problems] (problem/valid? domain problems))
