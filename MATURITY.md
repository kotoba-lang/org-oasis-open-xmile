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

Run the externally grounded gate:

```bash
clojure -M:conformance
```

The command validates emitted XML against the official OASIS XSD and requires
EDN/XML round-trip equality. CI runs it on JDK 21.

Raw extensions preserve unimplemented standard elements, but they are not
counted as semantic or executable support.
