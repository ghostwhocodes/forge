# Release Verification

Forge release packaging is channel-neutral. It builds local artifacts only; it
does not publish to Maven Central, Homebrew, or GitHub Releases.

Do not use `./forge-dev` for concurrent release smoke checks. It rebuilds
shared `target/` state. Build once, then use the packaged
`target/release/forge-0.1.0/forge` launcher for parallel verification.

Build the package:

```bash
just release-package
```

Verify the distribution checksums:

```bash
cd target/release/forge-0.1.0
sha256sum -c SHA256SUMS
cd ..
sha256sum -c forge-0.1.0.tar.gz.sha256
```

Verify the packaged launcher from outside the repository:

```bash
repo=$(pwd)
tmp=$(mktemp -d)
cd "$tmp"
"$repo/target/release/forge-0.1.0/forge" version
"$repo/target/release/forge-0.1.0/forge" template list
```

Validate every built-in template:

```bash
repo=$(pwd)
for template in implement-change review-only review-and-fix auto-review-and-fix architecture-guard qa-gap-guard; do
  "$repo/target/release/forge-0.1.0/forge" template validate --template="$template"
done
```

Run a minimal sample inspection:

```bash
repo=$(pwd)
tmp=$(mktemp -d)
"$repo/target/release/forge-0.1.0/forge" run init \
  --spec="$repo/docs/examples/minimal/software-loop.json" \
  --runs="$tmp/runs" \
  --slug=sample \
  --artifact=request="$repo/docs/examples/minimal/request-simple.json"
"$repo/target/release/forge-0.1.0/forge" run next --runs="$tmp/runs" --slug=sample
```
