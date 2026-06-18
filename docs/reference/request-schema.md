# Request Schema

[`request.schema.json`](request.schema.json) is a reference schema for the
normalized request artifact shape currently produced by `forge intake`.

Use it for:

- understanding the current `artifacts/request.json` structure
- generating compatible request artifacts in external tooling
- reading the current request surface without tracing intake code first

Current status:

- descriptive reference, not runtime-enforced validation
- intended to stay aligned with current intake behavior
- not yet a formal compatibility promise
