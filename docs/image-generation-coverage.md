# Coverage evidence

## Baseline (before new tests)

Instrumented existing suites on 2026-09-18 using JaCoCo 0.8.14 and coverage.py 7.10.7. Java report: `target/site/jacoco/jacoco.xml`; command output: `target/baseline-java.log`. Python report: `target/baseline-python.json` (18 GUI tests and 7 client tests). These target artifacts are disposable; the counts below preserve the baseline.

| Language | Covered lines/statements | Covered branches |
|---|---:|---:|
| Java handwritten production | 774 / 1657 (46.71%) | 421 / 885 (47.57%) |
| Python handwritten production | 271 / 424 (63.92%) | 65 / 112 (58.04%) |

Java excludes only generated `us/daconta/xlmeco/grpc/**`. Python includes `python_client` and the shared Chat/Admin GUI app plus its console session module, excluding generated protobuf modules. Legacy clients, vector database adapters, server entrypoints and transport logic remain included.

## Enforced gates

`mvn verify` requires Java aggregate LINE and BRANCH covered ratios independently >=0.90. Reports are `target/site/jacoco/index.html` and `target/site/jacoco/jacoco.xml`.

`python scripts/check_python_coverage.py` runs the GUI and client unittest suites with branch instrumentation, writes `target/python-coverage.json`, `target/python-coverage.xml` and `target/python-coverage-html/index.html`, and independently requires statement and branch coverage >=90%. Install `requirements-test.txt` first in the selected Python environment.

### Reproduce the gates

Run from the repository root in native PowerShell. The measured Windows environment used Java 21 and Maven 3.9.11. Use the intended Python environment consistently for dependency installation and execution. The install command below changes that Python environment; the version checks verify the selected tools afterward.

```powershell
# Start in your xlm-eco-api checkout.
mvn --version
python --version
python -m pip install -r python_client/requirements.txt -r requirements-test.txt Flask Flask-SocketIO
if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed' }
python -m coverage --version
python -c "import flask, flask_socketio, grpc, grpc_tools; print('Chat and generated-client dependencies import successfully')"

mvn clean verify
if ($LASTEXITCODE -ne 0) { throw 'Java verification or coverage gate failed' }
python scripts/check_python_coverage.py
if ($LASTEXITCODE -ne 0) { throw 'Python verification or coverage gate failed' }
```

Run Java first: `clean` removes generated Python protobuf modules and report files; Maven regenerates the modules before the Python suite. Successful Java verification prints `BUILD SUCCESS` and writes the JaCoCo reports above. The Python runner prints both independent ratios and exits nonzero for failed tests or either ratio below 90%. Open the HTML reports for class/module detail; XML and JSON retain machine-readable counters.

On this workstation Maven dependency retrieval required the documented Windows trusted-root configuration. This uses the Windows trust store and does not disable certificate validation. The actual final Java invocation was `mvn -q clean verify`, with these trust-store options and no skip flags. Quiet mode suppresses the normal success banner; its observed exit code was 0. Reproduce it while preserving existing process options:

```powershell
$savedMavenOptions = $env:MAVEN_OPTS
try {
    $env:MAVEN_OPTS = "$savedMavenOptions -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
    mvn -q clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Java verification or coverage gate failed' }
} finally {
    $env:MAVEN_OPTS = $savedMavenOptions
}
```

The measured final Python run used coverage.py installed in `../.runtime/test-tools`; this exact invocation reuses that already-installed task environment:

```powershell
$savedPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = (Resolve-Path ../.runtime/test-tools).Path
    python scripts/check_python_coverage.py
    if ($LASTEXITCODE -ne 0) { throw 'Python verification or coverage gate failed' }
} finally {
    $env:PYTHONPATH = $savedPythonPath
}
```

## Python verification

The final full runner passed **41 tests (30 GUI and 11 client/compatibility)** on 2026-09-18, including a rerun after clean Java generation: statements **471/472 (99.79%)**, branches **128/130 (98.46%)**. Every handwritten Python module exceeds 90% independently. The shared GUI app has one uncovered defensive failure line and two partial branches; `console_session.py`, `admin_transport.py`, and `xlm_client.py` have complete statement and branch coverage. Evidence: `../.runtime/final-python-coverage.log`, `target/python-coverage.json` and the report files above. These are local fake-provider and loopback tests, not remote-provider operational evidence.

Expected final summary from that measured revision:

```text
Python statements: 471/472 (99.79%), required >=90%
Python branches: 128/130 (98.46%), required >=90%
```

## Legacy Java findings

Tests cover vector RPC mappings, metadata conversion, SPI selection, real Java CLI gRPC exchanges, and the Milvus SDK against a local fake Milvus gRPC service. No actual Milvus or provider account is contacted.

Two existing defects are explicitly characterized as failures, not successful behavior: Milvus `getVector` dereferences an unassigned metadata wrapper when returned data includes metadata; vector RPC JSON decimal metadata becomes `BigDecimal`, which the converter rejects. Repairs are outside the image-generation implementation scope.

Three uncalled private Milvus helpers (`createCollectionIfNotExists`, `collectionExists`, `indexExists`) account for 57 lines. They remain in the denominator; tests do not invoke unreachable private code just to improve statistics. Any class-level coverage exception requires explicit Architect disposition. Aggregate Java LINE/BRANCH thresholds remain unchanged.

## Java final measurement and manual review

The clean verification completed with **exit code 0** and measures **1656/1786 lines (92.72%)** and **903/992 branch outcomes (91.03%)**. Both aggregate thresholds are met. Surefire reports 81 tests, zero failures/errors and one skipped opt-in network TLS test container (80 executed tests). Instrumented child JVM startup tests append to the same report; all handwritten classes remain included. The clean verification log is `../.runtime/clean-verify.log`.

### Separate optional network TLS diagnostic

A subsequent `mvn -q test '-Dtest=SecurityTest'` invocation with `XLM_RUN_NETWORK_TLS_TESTS=true` produced **9 passes and 1 PKIX error**. This separate diagnostic did not pass and does not replace or invalidate the recorded clean coverage-gate result; the JaCoCo XML report was not regenerated afterward. It is consistent with the previously documented [Norton synthetic-certificate interception limitation](dynamic-administration-verification.md#tls-environment-limitation): the trusted loopback certificate is replaced in transit and the client correctly rejects the replacement. Network TLS operational acceptance remains blocked; certificate verification was not weakened. In-memory TLS trust and hostname checks passed in the clean suite.

Reproduce this optional, non-gating diagnostic only in the intended network environment. A separate JaCoCo data file keeps its measurements apart from the clean report; Surefire's individual test result files can still be overwritten, so retain the clean log above.

```powershell
$savedNetworkTlsSetting = $env:XLM_RUN_NETWORK_TLS_TESTS
try {
    $env:XLM_RUN_NETWORK_TLS_TESTS = 'true'
    mvn test '-Dtest=SecurityTest' '-Djacoco.destFile=target/jacoco-network-tls.exec'
    $networkTlsExit = $LASTEXITCODE
    Write-Output "Optional network TLS diagnostic exit code: $networkTlsExit"
} finally {
    $env:XLM_RUN_NETWORK_TLS_TESTS = $savedNetworkTlsSetting
}
```

Expected on the intercepted workstation: Maven test failure with one PKIX error. Expected in an approved environment without interception: all network TLS cases pass. Neither expectation is a substitute for running the diagnostic and recording its actual result.

The following classes remain below 90% on either counter. These are disclosed exceptions requiring Architect review, not exclusions or approved waivers. `N/A` means there are no measured branches.

| Class | Line | Branch | Remaining behavior and disposition |
|---|---:|---:|---|
| `security.AdminAuthInterceptor` | 100% | 77.78% | Null-after-single-value and control/non-ASCII metadata guards. Missing, duplicate, oversized, incorrect and valid bearer headers are exercised. Transport rejects some malformed bytes before interceptor dispatch. Retain defensive checks. |
| `security.ListenerSecurity` | 86.67% | 91.67% | Defensive address conversion exception and valid certificate listener setup are not completely exercised by this class's instrumentation; in-memory TLS trust/hostname tests pass. Optional network TLS suite remains opt-in. |
| `provider.VectorDbProviderFactory` | 85.71% | 83.33% | Implicit utility constructor and no-SPI-provider failure while the packaged Milvus service entry is present. Configured and unconfigured discovery are tested. |
| `provider.GeneratedImageValidator` | 100% | 88.24% | Defensive nonpositive decoder dimensions, null decoded raster and decoder warning outcomes remain partial. Tests cover valid PNG/JPEG, invalid base64, wrong MIME, unsupported format, corrupt/truncated data, excessive bytes, per-side dimensions and total pixels. Retain fail-closed guards. |
| `provider.impl.GoogleProvider` | 75% | 75.86% | Legacy Vertex AI/ADC paths and default public endpoint construction are not exercised with real credentials. REST text, embeddings, image analysis and generation use local fake HTTP tests. External-runtime coverage requires a separately authorized operational environment. |
| `provider.impl.ProviderHttp` | 100% | 89.47% | Null credential/body and empty structured-output defensive alternatives remain partial. Transport limits, quota/rate distinctions, HTTP errors, redirects, timeout and connection failures are exercised. |
| `provider.impl.MilvusDbProvider` | 78.35% | 77.63% | 57 dead helper lines plus exceptional query/load guards and interruption. Public schema/index, CRUD, metadata serialization, polling and provider-failure cases are tested over real SDK loopback calls; known metadata failure is explicitly characterized above. |
| `VectorDbTestClient` | 89.86% | 50% | Process-exit argument/invalid-port paths and implicit constructor. Success and RPC-failure sequences run over loopback gRPC. |
| `XlmEcosystemServiceImpl$1` | 75% | N/A | Provider observer `onError` callback forwarding; thrown provider failure and successful stream forwarding are exercised. Callback-specific failure remains untested. |
| `GrpcXlmServer` | 81.40% | 86.36% | Successful long-running await/shutdown lifecycle, some absent/optional settings and implicit constructor. Actual child JVM tests cover missing/relative/checkout paths and second-listener startup failure with cleanup/restart. |
| `GrpcXlmClient` | 91% | 77.78% | Process-exit registration/selection failures, interrupted wait, absent embedding capability key and implicit constructor. Successful text/embedding flow, unsupported embedding and stream failure run over loopback gRPC. |

Manual review prioritized capability/default persistence, registration and routing, malformed selection, provider normalization, generated output validation, and credential/TLS boundaries. Passing percentages do not establish remote model capability, operational Milvus behavior, or human Architect Acceptance.
