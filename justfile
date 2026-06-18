set shell := ["bash", "-euo", "pipefail", "-c"]

default: ci

build:
    mvn -q -DskipTests package

release-package:
    mvn -q -DskipTests package
    version="$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)"; \
    name="forge-${version}"; \
    target="target"; \
    release_root="${target}/release"; \
    dist="${release_root}/${name}"; \
    rm -rf "${dist}" "${release_root}/${name}.tar.gz" "${release_root}/${name}.tar.gz.sha256"; \
    mkdir -p "${dist}"; \
    cp -p "${target}/${name}.jar" "${dist}/"; \
    cp -p "${target}/forge" "${dist}/"; \
    cp -pR "${target}/forge-assets" "${dist}/forge-assets"; \
    cp -p LICENSE-APACHE LICENSE-MIT "${dist}/"; \
    cp -p README.md QUICKSTART.md "${dist}/"; \
    cp -p CHANGELOG.md CONTRIBUTING.md SECURITY.md "${dist}/"; \
    (cd "${dist}" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS); \
    (cd "${release_root}" && tar -czf "${name}.tar.gz" "${name}" && sha256sum "${name}.tar.gz" > "${name}.tar.gz.sha256")

test:
    mvn -q test

java-test:
    mvn -q test

java-verify:
    mvn -q verify

coverage:
    mvn -q verify

coverage-html:
    mvn -q verify

verify:
    mvn -q verify

ci: verify

ci-logged:
    mkdir -p target/validation && ts="$(date -u +%Y%m%dT%H%M%SZ)" && just ci 2>&1 | tee "target/validation/ci-${ts}.log"
