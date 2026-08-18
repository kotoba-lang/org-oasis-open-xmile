# Maturity and conformance

`100%` is reported only against an explicit external denominator.

| Dimension | Denominator | Current result |
|---|---|---|
| Emitted XML schema validity | OASIS XMILE 1.0 official XSD | 100% for the canonical executable document |
| Canonical XML text round-trip | EDN → XML → EDN equality | 100% |
| Unknown standard element preservation | module/group/views/macro extension fixtures | 100% token-tree preservation |
| Full XSD element semantic modeling | All 189 named XSD elements | Not yet 100% |
| Core scalar execution | Supported stock/flow/aux equation subset | Euler/RK4 implemented |
| Full execution | Arrays, conveyors, queues, submodels, all built-ins/integrators | Not yet 100% |
| Host-independent evaluation | The scalar semantics of `xmile.expr` -- sec 3.5.1 math, `^`, `MOD`, relational/logical operators, sec 3.5.4 test inputs | Ported to `kotoba/xmile_expr_core.kotoba` and held to `xmile.expr` by a parity gate. Tree walking and the environment map are NOT ported and are not claimed. |

Run the externally grounded gate:

```bash
clojure -M:conformance
```

The command validates emitted XML against the official OASIS XSD and requires
EDN/XML round-trip equality. CI runs it on JDK 21.

Raw extensions preserve unimplemented standard elements, but they are not
counted as semantic or executable support.

## Host-independent evaluation

The Kotoba port covers the scalar arms of `xmile.expr/eval-expr` -- everything
that carries XMILE semantics rather than plain IEEE arithmetic. What it does
NOT cover is stated rather than implied: the expression tree, the environment
map, the tokenizer and parser, `xmile.xml`, `xmile.validate` and
`xmile.execute` are all still `.cljc` and still require a JVM or a JavaScript
engine. "A model can be simulated without one" is not claimed here and is not
true today.

The port's numeric agreement with the host is exact for selections and IEEE
operations and within 1e-12 relative for the transcendentals (worst observed
1.276e-13); its domain is narrower than the host's for wide angles. All three
facts are asserted by `test/xmile/kotoba_expr_core_parity_test.clj`, which
prints the measured errors on every run.
