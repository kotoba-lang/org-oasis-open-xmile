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
| Host-independent evaluation | `xmile.expr` -- the equation language: sec 3.5.1 math, `^`, `MOD`, relational/logical operators, sec 3.5.4 test inputs, and the tree walk | Ported to `kotoba/xmile_expr_core.kotoba` and held to `xmile.expr` by two parity gates. The tokenizer/parser, `xmile.xml`, `xmile.validate` and `xmile.execute` are NOT ported and are not claimed. |

Run the externally grounded gate:

```bash
clojure -M:conformance
```

The command validates emitted XML against the official OASIS XSD and requires
EDN/XML round-trip equality. CI runs it on JDK 21.

Raw extensions preserve unimplemented standard elements, but they are not
counted as semantic or executable support.

## Host-independent evaluation

The Kotoba port covers `xmile.expr/eval-expr` in full: the scalar operations
that carry XMILE semantics, and the walk over the expression tree that applies
them. What it does NOT cover is stated rather than implied -- the tokenizer and
parser, `xmile.xml`, `xmile.validate` and `xmile.execute` are all still `.cljc`
and still require a JVM or a JavaScript engine. **"A model can be simulated
without one" is not claimed here and is not true today.**

| | ported | denominator |
|---|---|---|
| `xmile.expr` operations | yes | sec 3.5.1 math, `^`, `MOD`, relational/logical, sec 3.5.4 |
| `xmile.expr` walk | yes | `:num` `:ref` unary binary `:if` `:call` |
| `xmile.expr` tokenizer/parser | no | sec 3.3.1 grammar |
| `xmile.validate` | no | structural checks |
| `xmile.execute` | no | Euler/RK4, `desugar-delays` |
| `xmile.xml` | no | XML text and element trees |

Numeric agreement with the host is exact for selections, IEEE operations and
every equation containing no transcendental; within 1e-12 relative for the
transcendentals and `^` (worst observed 1.276e-13 in the operation gate,
2.538e-16 through the walk). The port's domain is narrower than the host's for
wide angles, and that is recorded as a gap rather than a feature.

Both gates print their measured errors on every run, and every property either
one claims -- laziness, refusal order, environment/constant lookup order, exact
`f64` round-tripping through the environment -- has been shown to turn the gate
red on its own.
