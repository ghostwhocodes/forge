# Contributing

Forge requires Java 25 and Maven 3.9 or newer.

Before submitting a change, run:

```bash
just ci
```

For local CLI smoke checks from this checkout, do not run `./forge-dev`
concurrently. It rebuilds shared `target/` state before launching Forge. For
parallel checks, build once with:

```bash
mvn -q -DskipTests package
```

Then run `target/forge` from each shell.

Forge is prerelease. Do not add compatibility shims, fallback readers or
writers, migration paths, dual payload fields, or duplicate public runtime
surfaces unless a task explicitly requires them.

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in this work by you, as defined in the Apache License, Version
2.0, is dual licensed under Apache-2.0 and MIT without additional terms or
conditions.
