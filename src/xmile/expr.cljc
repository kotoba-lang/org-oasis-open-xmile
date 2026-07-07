(ns xmile.expr
  "The XMILE 1.0 equation micro-language: `parse` turns an equation string
  into an EDN expr tree, `eval-expr` evaluates that tree against an
  environment. Zero third-party deps -- portable .cljc.

  Grammar and operator precedence per OASIS XMILE v1.0 sec 3.3.1, highest
  binding first: parens > `^` (right-assoc) > unary +/-/NOT > `*` `/` MOD >
  binary `+` `-` > relational `<` `<=` `>` `>=` > equality `=` `<>` > AND > OR.
  Conditionals (sec 3.3.3): `IF cond THEN expr ELSE expr`.

  Tree shapes: [:num n] [:ref \"Name\"] [:call \"FN\" [arg-exprs...]]
  [:neg x] [:not x] [:add a b] [:sub a b] [:mul a b] [:div a b] [:mod a b]
  [:pow a b] [:lt a b] [:le a b] [:gt a b] [:ge a b] [:eq a b] [:ne a b]
  [:and a b] [:or a b] [:if c t e]

  Built-ins implemented (sec 3.5.1 math, sec 3.5.4 test inputs): ABS SQRT
  EXP LN LOG10 SIN COS TAN ARCSIN ARCCOS ARCTAN INT PI INF MIN MAX (exactly
  2 args each, per spec) MOD (operator, not a call) PULSE STEP RAMP.

  Built-ins NOT implemented (sec 3.5.2 stochastic, sec 3.5.3 delay/smooth --
  a v2 scope-out, see README): RANDOM NORMAL EXPRND LOGNORMAL POISSON DELAY
  DELAY1 DELAY3 DELAYN SMTH1 SMTH3 SMTHN TREND FORCST. `parse` accepts calls
  to them (so a model round-trips through xmile.xml/xmile.validate);
  `eval-expr` throws a clear ex-info if one is actually evaluated.

  `PULSE`/`STEP`/`RAMP` read the reserved `\"TIME\"`/`\"DT\"` env entries
  (XMILE's built-in simulation-time variables) -- callers (xmile.execute)
  must supply them alongside ordinary variable bindings."
  (:refer-clojure :exclude [mod parse-double])
  (:require [clojure.string :as str]))

;; --- portable math shims ---

(defn- m-abs   [x] #?(:clj (Math/abs (double x))   :cljs (js/Math.abs x)))
(defn- m-sqrt  [x] #?(:clj (Math/sqrt x)           :cljs (js/Math.sqrt x)))
(defn- m-exp   [x] #?(:clj (Math/exp x)            :cljs (js/Math.exp x)))
(defn- m-ln    [x] #?(:clj (Math/log x)            :cljs (js/Math.log x)))
(defn- m-log10 [x] #?(:clj (Math/log10 x)          :cljs (/ (js/Math.log x) js/Math.LN10)))
(defn- m-sin   [x] #?(:clj (Math/sin x)            :cljs (js/Math.sin x)))
(defn- m-cos   [x] #?(:clj (Math/cos x)            :cljs (js/Math.cos x)))
(defn- m-tan   [x] #?(:clj (Math/tan x)            :cljs (js/Math.tan x)))
(defn- m-asin  [x] #?(:clj (Math/asin x)           :cljs (js/Math.asin x)))
(defn- m-acos  [x] #?(:clj (Math/acos x)           :cljs (js/Math.acos x)))
(defn- m-atan  [x] #?(:clj (Math/atan x)           :cljs (js/Math.atan x)))
(defn- m-floor [x] #?(:clj (Math/floor x)          :cljs (js/Math.floor x)))
(defn- m-pow   [a b] #?(:clj (Math/pow a b)        :cljs (js/Math.pow a b)))
(def ^:private m-pi  #?(:clj Math/PI :cljs js/Math.PI))
(def ^:private m-inf #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity))

(defn- parse-double [s] #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

;; --- tokenizer (regex-based: portable across clj/cljs native regex) ---

(def ^:private keyword-tokens #{"IF" "THEN" "ELSE" "AND" "OR" "NOT" "MOD"})

(def ^:private token-re
  #"(\s+)|(\"[^\"]*\")|([0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)|([A-Za-z_][A-Za-z0-9_]*)|(<=|>=|<>)|([<>=+\-*/^(),])|(.)")

(defn tokenize
  "String -> vector of {:type :num|:ident|:kw|:op :value _}."
  [s]
  (->> (re-seq token-re s)
       (keep (fn [[_ ws quoted num ident op2 op1 other]]
               (cond
                 ws nil
                 quoted {:type :ident :value (subs quoted 1 (dec (count quoted)))}
                 num    {:type :num :value (parse-double num)}
                 ident  (let [up (str/upper-case ident)]
                          (if (contains? keyword-tokens up)
                            {:type :kw :value up}
                            {:type :ident :value ident}))
                 op2    {:type :op :value op2}
                 op1    {:type :op :value op1}
                 :else  (throw (ex-info "xmile.expr: unexpected character"
                                         {:char other :source s})))))
       vec))

;; --- recursive-descent parser ---

(defn- peek-tok [toks] (first toks))
(defn- op? [tok v] (and tok (= (:type tok) :op) (= (:value tok) v)))
(defn- kw? [tok v] (and tok (= (:type tok) :kw) (= (:value tok) v)))

(declare parse-expr parse-unary)

(defn- expect-op [toks v]
  (if (op? (peek-tok toks) v)
    (rest toks)
    (throw (ex-info (str "xmile.expr: expected '" v "'") {:got (peek-tok toks)}))))

(defn- parse-args [toks]
  (if (op? (peek-tok toks) ")")
    [[] (rest toks)]
    (loop [toks toks args []]
      (let [[e toks] (parse-expr toks)
            args (conj args e)]
        (cond
          (op? (peek-tok toks) ",") (recur (rest toks) args)
          (op? (peek-tok toks) ")") [args (rest toks)]
          :else (throw (ex-info "xmile.expr: expected ',' or ')' in argument list"
                                 {:got (peek-tok toks)})))))))

(defn- parse-primary [toks]
  (let [tok (peek-tok toks)]
    (cond
      (nil? tok) (throw (ex-info "xmile.expr: unexpected end of input" {}))

      (= (:type tok) :num) [[:num (:value tok)] (rest toks)]

      (kw? tok "IF")
      (let [[c toks] (parse-expr (rest toks))
            toks (if (kw? (peek-tok toks) "THEN") (rest toks)
                   (throw (ex-info "xmile.expr: expected THEN" {:got (peek-tok toks)})))
            [t toks] (parse-expr toks)
            toks (if (kw? (peek-tok toks) "ELSE") (rest toks)
                   (throw (ex-info "xmile.expr: expected ELSE" {:got (peek-tok toks)})))
            [e toks] (parse-expr toks)]
        [[:if c t e] toks])

      (op? tok "(")
      (let [[e toks] (parse-expr (rest toks))
            toks (expect-op toks ")")]
        [e toks])

      (= (:type tok) :ident)
      (let [nm (:value tok) toks (rest toks)]
        (if (op? (peek-tok toks) "(")
          (let [[args toks] (parse-args (rest toks))]
            [[:call (str/upper-case nm) args] toks])
          [[:ref nm] toks]))

      :else (throw (ex-info "xmile.expr: unexpected token" {:token tok})))))

(defn- parse-power [toks]
  (let [[base toks] (parse-primary toks)]
    (if (op? (peek-tok toks) "^")
      (let [[exp toks] (parse-unary (rest toks))]   ; right-associative
        [[:pow base exp] toks])
      [base toks])))

(defn parse-unary [toks]
  (let [tok (peek-tok toks)]
    (cond
      (op? tok "-") (let [[e toks] (parse-unary (rest toks))] [[:neg e] toks])
      (op? tok "+") (parse-unary (rest toks))
      (kw? tok "NOT") (let [[e toks] (parse-unary (rest toks))] [[:not e] toks])
      :else (parse-power toks))))

(defn- parse-mult
  "`*` `/` MOD share one precedence tier (sec 3.3.1) and left-associate in
  token order, so MOD is folded into the same loop as `*`/`/` rather than a
  separate wrapping pass."
  [toks]
  (loop [[e toks] (parse-unary toks)]
    (let [tok (peek-tok toks)]
      (cond
        (op? tok "*") (let [[rhs toks] (parse-unary (rest toks))] (recur [[:mul e rhs] toks]))
        (op? tok "/") (let [[rhs toks] (parse-unary (rest toks))] (recur [[:div e rhs] toks]))
        (kw? tok "MOD") (let [[rhs toks] (parse-unary (rest toks))] (recur [[:mod e rhs] toks]))
        :else [e toks]))))

(defn- parse-left-assoc [sub op->node]
  (fn [toks]
    (loop [[e toks] (sub toks)]
      (let [tok (peek-tok toks)
            matched (some (fn [[v node-k]] (when (op? tok v) node-k)) op->node)]
        (if matched
          (let [[rhs toks] (sub (rest toks))]
            (recur [[matched e rhs] toks]))
          [e toks])))))

(def ^:private parse-additive    (parse-left-assoc parse-mult      [["+" :add] ["-" :sub]]))
(def ^:private parse-relational  (parse-left-assoc parse-additive  [["<" :lt] ["<=" :le] [">" :gt] [">=" :ge]]))
(def ^:private parse-equality    (parse-left-assoc parse-relational [["=" :eq] ["<>" :ne]]))

(defn- parse-and [toks]
  (loop [[e toks] (parse-equality toks)]
    (if (kw? (peek-tok toks) "AND")
      (let [[rhs toks] (parse-equality (rest toks))] (recur [[:and e rhs] toks]))
      [e toks])))

(defn- parse-or [toks]
  (loop [[e toks] (parse-and toks)]
    (if (kw? (peek-tok toks) "OR")
      (let [[rhs toks] (parse-and (rest toks))] (recur [[:or e rhs] toks]))
      [e toks])))

(defn parse-expr [toks] (parse-or toks))

(defn parse
  "Parse an XMILE equation string into an EDN expr tree."
  [s]
  (let [toks (tokenize s)
        [e rest-toks] (parse-expr toks)]
    (if (seq rest-toks)
      (throw (ex-info "xmile.expr: trailing input after expression" {:remaining rest-toks :source s}))
      e)))

;; --- built-ins ---

(def unsupported-builtins
  "Sec 3.5.2 (stochastic) / 3.5.3 (delay+smooth+trend) built-ins -- valid
  XMILE, not implemented by eval-expr (v2 scope-out, see README)."
  #{"RANDOM" "NORMAL" "EXPRND" "LOGNORMAL" "POISSON"
    "DELAY" "DELAY1" "DELAY3" "DELAYN" "SMTH1" "SMTH3" "SMTHN" "TREND" "FORCST"})

(def constants {"PI" m-pi "INF" m-inf})

(defn- require-arity [name args n]
  (when (not= n (count args))
    (throw (ex-info (str "xmile.expr: " name " takes exactly " n " argument(s)")
                     {:fn name :args args}))))

(defn- pulse-value
  "sec 3.5.4: PULSE(magnitude, first-time[, interval]) -- a one-DT-wide
  pulse of `magnitude/DT` at `first-time` (and every `interval` thereafter,
  if given and positive; otherwise fires once)."
  [args env]
  (when-not (contains? #{2 3} (count args))
    (throw (ex-info "xmile.expr: PULSE takes 2 or 3 arguments" {:args args})))
  (let [[magnitude first-time interval] (if (= 2 (count args)) (conj (vec args) 0.0) args)
        t  (double (get env "TIME"))
        dt (double (get env "DT"))]
    (cond
      (< t (- first-time (/ dt 2.0))) 0.0
      (<= interval 0.0) (if (< (- t first-time) dt) (/ magnitude dt) 0.0)
      :else (if (< (clojure.core/mod (- t first-time) interval) dt) (/ magnitude dt) 0.0))))

(defn- step-value [args env]
  (require-arity "STEP" args 2)
  (let [[height start] args t (double (get env "TIME"))]
    (if (>= t start) height 0.0)))

(defn- ramp-value [args env]
  (require-arity "RAMP" args 2)
  (let [[slope start] args t (double (get env "TIME"))]
    (if (< t start) 0.0 (* slope (- t start)))))

(def ^:private builtins
  ;; name -> (fn [evaluated-args env] result)
  {"ABS"    (fn [[x] _] (m-abs x))
   "SQRT"   (fn [[x] _] (m-sqrt x))
   "EXP"    (fn [[x] _] (m-exp x))
   "LN"     (fn [[x] _] (m-ln x))
   "LOG10"  (fn [[x] _] (m-log10 x))
   "SIN"    (fn [[x] _] (m-sin x))
   "COS"    (fn [[x] _] (m-cos x))
   "TAN"    (fn [[x] _] (m-tan x))
   "ARCSIN" (fn [[x] _] (m-asin x))
   "ARCCOS" (fn [[x] _] (m-acos x))
   "ARCTAN" (fn [[x] _] (m-atan x))
   "INT"    (fn [[x] _] (m-floor x))
   "PI"     (fn [args _] (require-arity "PI" args 0) m-pi)
   "INF"    (fn [args _] (require-arity "INF" args 0) m-inf)
   "MAX"    (fn [args _] (require-arity "MAX" args 2) (apply max args))
   "MIN"    (fn [args _] (require-arity "MIN" args 2) (apply min args))
   "PULSE"  pulse-value
   "STEP"   step-value
   "RAMP"   ramp-value})

;; --- evaluator ---

(defn- truthy? [x] (not (zero? x)))
(defn- bool->num [b] (if b 1.0 0.0))

(defn- resolve-ref [nm env]
  (cond
    (contains? env nm) (get env nm)
    (contains? constants nm) (get constants nm)
    :else (throw (ex-info "xmile.expr: unknown identifier" {:ref nm}))))

(defn eval-expr
  "Evaluate an expr tree (from `parse`) against `env`, a map of
  {\"VarName\" number} that MUST include \"TIME\" and \"DT\" (XMILE's
  reserved simulation-time variables) if the expression may use
  PULSE/STEP/RAMP."
  [expr env]
  (let [go #(eval-expr % env)]
    (case (first expr)
      :num (second expr)
      :ref (resolve-ref (second expr) env)
      :neg (- (go (nth expr 1)))
      :not (bool->num (not (truthy? (go (nth expr 1)))))
      :add (+ (go (nth expr 1)) (go (nth expr 2)))
      :sub (- (go (nth expr 1)) (go (nth expr 2)))
      :mul (* (go (nth expr 1)) (go (nth expr 2)))
      :div (/ (go (nth expr 1)) (go (nth expr 2)))
      :mod (clojure.core/mod (go (nth expr 1)) (go (nth expr 2)))
      :pow (m-pow (go (nth expr 1)) (go (nth expr 2)))
      :lt  (bool->num (< (go (nth expr 1)) (go (nth expr 2))))
      :le  (bool->num (<= (go (nth expr 1)) (go (nth expr 2))))
      :gt  (bool->num (> (go (nth expr 1)) (go (nth expr 2))))
      :ge  (bool->num (>= (go (nth expr 1)) (go (nth expr 2))))
      :eq  (bool->num (== (go (nth expr 1)) (go (nth expr 2))))
      :ne  (bool->num (not (== (go (nth expr 1)) (go (nth expr 2)))))
      :and (bool->num (and (truthy? (go (nth expr 1))) (truthy? (go (nth expr 2)))))
      :or  (bool->num (or  (truthy? (go (nth expr 1))) (truthy? (go (nth expr 2)))))
      :if  (if (truthy? (go (nth expr 1))) (go (nth expr 2)) (go (nth expr 3)))
      :call (let [nm (second expr) arg-exprs (nth expr 2)]
              (cond
                (contains? unsupported-builtins nm)
                (throw (ex-info (str "xmile.expr: " nm " is not yet implemented (v2 scope-out)")
                                 {:fn nm}))
                (contains? builtins nm)
                ((get builtins nm) (mapv go arg-exprs) env)
                :else
                (throw (ex-info "xmile.expr: unknown function" {:fn nm}))))
      (throw (ex-info "xmile.expr: unknown expr node" {:expr expr})))))

(defn eval-eqn
  "Convenience: parse then eval-expr a raw equation string in one call."
  [eqn-str env]
  (eval-expr (parse eqn-str) env))

;; --- static analysis ---

(defn free-vars
  "All :ref identifiers in an expr tree (excludes the reserved TIME/DT names
  and built-in constants), as a set of strings."
  [expr]
  (letfn [(walk [e]
            (case (first e)
              :num #{}
              :ref (if (contains? #{"TIME" "DT"} (second e)) #{} #{(second e)})
              (:neg :not) (walk (nth e 1))
              (:add :sub :mul :div :mod :pow :lt :le :gt :ge :eq :ne :and :or)
              (into (walk (nth e 1)) (walk (nth e 2)))
              :if (into (walk (nth e 1)) (into (walk (nth e 2)) (walk (nth e 3))))
              :call (reduce into #{} (map walk (nth e 2)))
              #{}))]
    (walk expr)))

(defn called-fns
  "All function names called (as :call) anywhere in an expr tree."
  [expr]
  (letfn [(walk [e]
            (case (first e)
              :num #{} :ref #{}
              (:neg :not) (walk (nth e 1))
              (:add :sub :mul :div :mod :pow :lt :le :gt :ge :eq :ne :and :or)
              (into (walk (nth e 1)) (walk (nth e 2)))
              :if (into (walk (nth e 1)) (into (walk (nth e 2)) (walk (nth e 3))))
              :call (into #{(second e)} (reduce into #{} (map walk (nth e 2))))
              #{}))]
    (walk expr)))
