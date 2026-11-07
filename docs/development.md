# Develop TexSuite

[Home](../README.md) · [User guide](user-guide.md) · [Safety reference](../opinions.md)

## Build and run from source

Run these commands from a source checkout using Java 21+ and the Maven wrapper:

```sh
./mvnw --batch-mode clean verify
java -jar target/texsuite.jar --help
```

Java sources and tests are in `src/main/java/texsuite` and `src/test/java/texsuite`.
The executable JAR is `target/texsuite.jar`. Tests use synthetic temporary projects;
real-compiler integration tests need pdflatex and BibTeX and can be skipped when
those tools are absent. Check the reported skip counts before claiming compiler proof.

For manual merge trials, the development workspace may also contain
`../merge-playground/README.md` with four resettable pairs and expected results;
this optional fixture directory is not part of an installed application.
Merge itself needs no AI request or additional Java dependency.

## Coverage, generated cases and mutation testing

Run `./mvnw clean verify` for JUnit tests, jqwik properties, and JaCoCo coverage.
Open `target/site/jacoco/index.html`; `jacoco.xml` provides machine-readable line
and branch counts. Coverage starts as a baseline, without a percentage gate.
Inspect uncovered safety paths rather than adding tests solely to raise a score.
JaCoCo does not count exception handlers as branches: commit, rollback, and
provider failures still need explicit scenarios and assertions about unchanged files.
Run `sh scripts/test-install.sh` separately for installed-launcher and offline-guide
checks (Python 3.9+). `python3 scripts/check-docs.py .` checks local links and headings
without installing.

`EditingPropertiesTest` checks empty-selection rejection without writes, unchanged
segments around valid replacements, exact recovery after interruption, and Unicode
candidate byte ranges. It runs 200 cases per editing/offset property and 50 recovery
cases, using synthetic files in a fresh temporary directory for each trial.
The tests use jqwik's JUnit Platform engine, not Jupiter's `@TempDir` lifecycle.
`CachePropertiesTest` adds 400 generated checks of canonical object ordering, preserved array
ordering and exact replacement whitespace. `ModelCacheTest` exercises persistence, corruption,
limits, project isolation, controls, repairs, partial batches, and approval/compile/freshness gates.
JUnit regression tests use Arrange–Act–Assert and compare externally visible
results; one rejected operation can require assertions on several files.

On a property failure, preserve the printed seed and shrunk sample from
`target/surefire-reports/` and the replay database `target/jqwik-database` before
running `clean`. Re-run with
`./mvnw -Dtest=EditingPropertiesTest test`; jqwik retries the stored failure first.
For replay on another machine, temporarily put the reported seed in the failing
method's `@Property(seed = "...")` annotation with the same generator code.
After fixing a defect, retain the minimized example as a deterministic JUnit
regression and remove the temporary seed. Do not retry unexplained failures away.

Run focused mutation analysis separately:

```sh
./mvnw --batch-mode -Pmutation -Djacoco.skip=true \
  test-compile org.pitest:pitest-maven:mutationCoverage
```

PIT targets `TextEditPlan`, `TextRecovery`, `DocumentInputValidator`, and
`ModelProtocol` (including nested classes), using deterministic JUnit tests.
Generated property tests are excluded from PIT to keep mutation runs predictable.
Read `target/pit-reports/index.html` or `mutations.xml`; classify surviving
mutations as missing assertions, uncovered behavior, equivalent behavior with
justification, or unresolved. A survivor is a question to investigate, not
permission to weaken a contract. There is initially no mutation-score gate.

Normal CI uploads coverage and test diagnostics for 14 days, including jqwik
replay data when available. Mutation CI runs weekly on Sunday at 02:00 UTC and
through manual dispatch, with a 30-minute timeout and 14-day reports. Neither
workflow hides test/tool failures. Reports use synthetic fixtures; live model
calls and private documents are not part of these tests.

