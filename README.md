# org-oasis-open-xmile

[![CI](https://github.com/kotoba-lang/org-oasis-open-xmile/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/org-oasis-open-xmile/actions/workflows/ci.yml)

**[OASIS XMILE 1.0](https://www.oasis-open.org/standard/xmile1-0/)
(the XML Interchange Language for System Dynamics) as EDN/Clojure data, in
portable `.cljc`.** A [kotoba-lang](https://github.com/kotoba-lang) `org-*`
library: the same pattern as `org-w3-webauthn`/`org-ietf-oauth2`/`org-ros` --
a small, zero-third-party-dependency, portable implementation of an open
standard, pure data in, pure data out. [System dynamics](https://en.wikipedia.org/wiki/System_dynamics)
(Jay Forrester's stock-and-flow modeling method) is the domain; XMILE 1.0
(approved as an OASIS Standard 2015-12-14) is the interchange format
Stella/iThink, insightmaker and others use for it. Spec references below
cite section numbers of the [OASIS Standard HTML](http://docs.oasis-open.org/xmile/xmile/v1.0/os/xmile-v1.0-os.html)
(also available as [PDF](http://docs.oasis-open.org/xmile/xmile/v1.0/os/xmile-v1.0-os.pdf)
and [XSD](http://docs.oasis-open.org/xmile/xmile/v1.0/os/schemas/xmile.xsd)).

A model is stocks (accumulators), flows (rates of change into/out of a
stock) and auxiliaries (algebraic converters), wired by equations in a
small infix micro-language (sec 3.3) -- e.g. a bathtub: `Inventory` (stock,
initial 100) filled by `Production` (flow, 10/step) and drained by
`Shipping` (flow, `Inventory / 4`). This library gives you that model as
plain EDN, a parser+evaluator for the equation language, structural
validation, and a fixed-step Euler/RK4 simulator -- no vendor tool, no XML
parser dependency (host injects a parsed XML tree; see `xmile.xml`).

## Maturity

| | |
|---|---|
| Role | capability (data model + equation language + validation + simulation) |
| Structural coverage | header, sim_specs, model_units, dimensions, model/variables (stock/flow/aux), gf -- round-trips through `xmile.xml` |
| Equation language | full sec 3.3 grammar/precedence; sec 3.5.1 math + sec 3.5.4 test-input built-ins evaluate; sec 3.5.2 (stochastic) / 3.5.3 (delay/smooth/trend) parse but do not evaluate (v2, see Follow-ups) |
| Simulation | scalar stocks, `:euler`/`:rk4`, non-negative clamping; conveyor/queue transport and arrays are not yet simulated (v2) |
| Tests | round-trip/property coverage for every namespace |
| Runtime deps | `kotoba-lang/dsl-core` (validation-problem convention) only |

## Namespaces

- `xmile.model` -- the EDN schema (sec 3.1 stock/flow/aux, sec 3.7.1
  sim_specs, sec 3.2.2 gf) plus a threading-friendly builder and the
  structural queries `xmile.validate`/`xmile.execute` need.
- `xmile.expr` -- the equation micro-language: `parse` (string -> EDN expr
  tree, sec 3.3 grammar/precedence) and `eval-expr` (pure evaluator, sec
  3.5 built-ins).
- `xmile.xml` -- converts between an *already-parsed* XML element tree
  (`{:tag :stock :attrs {...} :content [...]}`, exactly what
  `clojure.data.xml`/`cljs.xml` already produce) and the `:xmile/*` EDN
  model. Does not parse XML text and does not cover the diagram/display
  layer (`<views>`/`<style>`) -- sec 3.7.5 itself says any XMILE-conformant
  tool must be able to simulate a whole-model with no diagram at all.
- `xmile.validate` -- structural checks (dangling references, illegal
  algebraic loops, malformed `sim_specs`/`gf`, unknown flow references)
  returning `kotoba.dsl.problem`-shaped problems. `:error` means the model
  is not valid XMILE; `:warn` means it's valid XMILE but exercises a
  feature `xmile.execute` v1 doesn't simulate yet.
- `xmile.execute` -- a pure fixed-step simulator (Euler or classical RK4)
  over the stock ODE system defined by the model's flow/aux network.

## Contract

```clojure
(require '[xmile.model :as m]
         '[xmile.validate :as validate]
         '[xmile.execute :as execute])

(def bathtub
  (-> (m/model "bathtub" {:xmile/sim-specs (m/sim-specs 0.0 40.0 {:xmile/dt 1.0})})
      (m/add-variable (m/stock "Inventory" "100"
                                {:xmile/inflows #{"Production"} :xmile/outflows #{"Shipping"}}))
      (m/add-variable (m/flow "Production" "10"))
      (m/add-variable (m/flow "Shipping" "Inventory / 4"))))

(validate/valid? (validate/validate bathtub))   ;=> true

(def result (execute/run bathtub))
(get-in result [:xmile/series "Inventory"])     ;=> [100.0 92.5 86.375 ... converges to 40.0]
(:xmile/times result)                           ;=> [0.0 1.0 2.0 ... 40.0]
```

Reading a real `.xmile` file: parse the XML text with your host's XML
parser into `{:tag :xmile :attrs {...} :content [...]}` (e.g.
`clojure.data.xml/parse` on the JVM), then `(xmile.xml/parse-doc that-tree)`.

## Follow-ups (v2, out of scope for this landing)

- **Stochastic built-ins** (sec 3.5.2: `RANDOM`/`NORMAL`/`EXPRND`/
  `LOGNORMAL`/`POISSON`) and **delay/smooth/trend built-ins** (sec 3.5.3:
  `DELAY`/`DELAY1`/`DELAY3`/`DELAYN`/`SMTH1`/`SMTH3`/`SMTHN`/`TREND`/
  `FORCST`) -- `xmile.expr/parse` accepts calls to them (so a model
  round-trips and validates structurally); `eval-expr` throws, and
  `xmile.validate` flags them as a `:warn`. A real implementation needs a
  host-injected seeded-RNG port for the former and internal hidden-stock
  state for the latter.
- **Conveyor/queue stock transport** (sec 3.7.2/3.7.3) -- modeled as data
  in `xmile.model`/round-trips through `xmile.xml`, but `xmile.execute`
  throws rather than approximate their transit-time/discrete-slot
  mechanics silently.
- **Arrays / dimensioned variables** (sec 4.5) -- `xmile.xml` models
  `<dimensions>` structurally; per-element/apply-to-all array equations
  are not evaluated by `xmile.execute`.
- **Units dimensional analysis** (sec 3.6) -- `xmile.xml` round-trips unit
  definitions and aliases as data; no unit-consistency checking of
  variable equations is performed.
- **Submodels/modules and macros** (sec 3.7.4) -- not modeled.
- **Diagram/display layer** (`<views>`, `<style>`) -- deliberately out of
  scope; sec 3.7.5 states a conformant simulator does not need it.
- Integration methods `rk2`/`rk45`/`gear` (sec 3.7.1 allows them) -- only
  `euler`/`rk4` are implemented.

## Test

```bash
clojure -M:test
```

## License

MIT.
