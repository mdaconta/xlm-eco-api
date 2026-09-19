# Run and test XLM

Run these commands from the `xlm-eco-api` repository root. Human commands use **Git Bash on Windows** unless marked PowerShell. The Windows server launcher and its tests intentionally execute through native PowerShell. Keep separate terminals for the server, Chat, and client checks.

## 1. Check tools and install dependencies

The measured environment uses Java 21, Maven 3.9.11, and Python 3.13; Java source/target compatibility is 17. The Maven build provisions repository-local Node 20.9.0/npm 10.0.0. Use the same Python executable for installation, Maven code generation, Chat, and tests.

```bash
java -version
mvn --version
python --version
python -m pip --version
python -m pip install -r python_client/requirements.txt -r requirements-test.txt
python -m pip install Flask==3.1.2 Flask-SocketIO==5.5.1
python -c "import grpc, grpc_tools, flask, flask_socketio; print('Python runtime imports passed')"
python -m coverage --version
```

These installation commands change the selected Python environment. `python_client/requirements.txt` pins the protobuf/gRPC generator; `requirements-test.txt` pins coverage.py. The Flask versions above match the measured Chat environment. Maven resolves Java dependencies and installs Node packages from the root package files. A separate global gRPC installation is not required.

## 2. Build and run local automated checks

No real provider credentials or running production server are needed for this section. Tests create local fixtures. Dependency installation may access package repositories; this is not an air-gapped build. Run Java first because `clean` removes generated Python stubs and previous reports.

```bash
mvn clean verify
python scripts/check_python_coverage.py
./node/node.exe --version
./node/node.exe gui/chat-ui/test_admin_ui.cjs
./node/node.exe gui/chat-ui/test_chat_ui.cjs
./node/node.exe src/test/node/client_compatibility.js
powershell.exe -NoProfile -File scripts/tests/local-server.test.ps1
```

Stop on a failing command and retain its output. Successful Maven verification prints `BUILD SUCCESS`, creates `target/xlm-eco-api-1.0-SNAPSHOT.jar`, generates Java/Python protobuf classes, runs JUnit and loopback integration tests, and enforces aggregate Java line/branch coverage independently at 90%. The Python runner discovers both GUI and client suites and independently enforces statement/branch coverage at 90%. Node commands verify Admin/Chat controller behavior and runtime protobuf compatibility. Launcher tests use fake Java processes and synthetic configuration, not the real server.

| Evidence | Location |
|---|---|
| Java test results | `target/surefire-reports/` |
| Java coverage | `target/site/jacoco/index.html`, `target/site/jacoco/jacoco.xml` |
| Python coverage | `target/python-coverage-html/index.html`, `target/python-coverage.json`, `target/python-coverage.xml` |
| Generated Python stubs | `python_client/generated/` |

For focused diagnosis after stub generation:

```bash
mvn test '-Dtest=ImageGenerationAdapterTest,ImageGenerationServiceTest,GeneratedImageValidatorTest'
python -m unittest discover -s gui/chat-ui -p 'test_*.py' -v
python -m unittest discover -s src/test/python -p 'test_*.py' -v
```

Focused tests do not replace the full gates. See [coverage scope, exceptions, measured results and Windows Maven trust-store commands](image-generation-coverage.md). Java fixtures also exercise the legacy vector API against simulated Milvus; they do not establish a working operational Milvus deployment.

### Local real-browser fixture

This test starts its own Flask process with a fake XLM stub and uses installed Microsoft Edge. It does not call providers. It needs a Playwright module, which is not part of the root Node dependencies. If Playwright is already installed, set `PLAYWRIGHT_MODULE` to that module's absolute directory. Otherwise, the following explicit optional install keeps tooling outside the checkout:

```bash
./node/node.exe ./node/node_modules/npm/bin/npm-cli.js install --prefix "$HOME/.xlm/browser-tools" --no-save playwright
export PLAYWRIGHT_MODULE="$(cygpath -m "$HOME/.xlm/browser-tools/node_modules/playwright")"
./node/node.exe -e "console.log(require(process.env.PLAYWRIGHT_MODULE + '/package.json').version)"
./node/node.exe -e "const {chromium}=require(process.env.PLAYWRIGHT_MODULE); (async()=>{const b=await chromium.launch({channel:'msedge',headless:true}); console.log(await b.version()); await b.close();})().catch(()=>process.exit(1))"
./node/node.exe gui/chat-ui/test_chat_browser.cjs
```

The two verification commands confirm the chosen module and installed browser can launch. `BROWSER_CHANNEL` can select another installed Chromium channel for the local fixture; the default is `msedge`. The test prints its passing scenarios and saves desktop/narrow screenshots to `target/chat-browser/desktop.png` and `narrow.png`. Inspect both images. It uses a Socket.IO shim to avoid a CDN dependency; separate Python tests cover actual Socket.IO client isolation.

## 3. Start the existing local server

Use the [Windows local server launcher](local-server.md) with the operator's existing external directories. Its default locations are `~/.xlm/config` and `~/.xlm/secrets`; explicit arguments or existing `XLM_CONFIG_DIR`/`XLM_SECRETS_DIR` override them.

```bash
powershell.exe -NoProfile -File scripts/local-server.ps1 -Action Check
powershell.exe -NoProfile -File scripts/local-server.ps1 -Action Run
```

`Check` should report **startup prerequisites found**. This checks paths, Java, JAR, `server.properties` and `admin.token`, without reading secret contents. `Run` starts the foreground Java server with the Windows trusted-root store; inspect server startup output for actual listener readiness. Default loopback ports are inference **50052** and Admin **50053**. Ctrl+C stops this foreground server. Do not start a second process on the same registry/ports.

If first-time configuration is missing, provision only the missing prerequisites using [administration and external secret requirements](dynamic-administration.md) and [server.properties.example](server.properties.example). The launcher never recreates credentials, resets the registry, or changes defaults. Arbitrary provider endpoints cannot be edited through Admin. For non-Windows launch or remote TLS configuration, follow the administration guide rather than the Windows trust-store wrapper.

## 4. Start Chat and Admin Console

In another Git Bash terminal, for the default existing secret directory:

```bash
export XLM_ADMIN_ENDPOINT=127.0.0.1:50053
export XLM_ADMIN_TOKEN_FILE="$(cygpath -m "$HOME/.xlm/secrets/admin.token")"
bash gui/chat-ui/run_ui.sh 127.0.0.1 50052 5000 openai gpt-4o-mini
```

If the server uses a different secret directory, set `XLM_ADMIN_TOKEN_FILE` to that same operator-owned absolute token-file path; it is a path, never the token contents. The launcher's environment overrides exist in its child process and are not automatically inherited by this second terminal. Adapt all three ports to the existing server configuration.

Open [Chat](http://127.0.0.1:5000) and [Admin](http://127.0.0.1:5000/admin). Chat's inference catalog works without Admin configuration; Admin editing needs the token-file setting. For remote gRPC targets set `XLM_INFERENCE_CA_FILE` and `XLM_ADMIN_CA_FILE` to trusted server-CA files; keep the Flask listener on loopback.

Confirm connection status and provider/model discovery before sending anything. Reading catalog/status is not a generation request. Admin saves change the persistent catalog and require deliberate operator action; browsing does not. Existing catalogs are not reseeded on upgrade. Add missing generation models through Admin with the independent `image_generation` capability when authorized; no generation default is automatically selected.

Follow the [Chat walkthrough](chat-ui-user-verification.md) for text, attachment/removal, JSON Schema, explicit generation, suggestion dismissal, incompatible selections, 390-pixel layout, and restart recovery. Sending text, analysis, or generation to a real provider can incur charges. Restart verification keeps the Python/browser session running while restarting only the foreground Java process against the same directories; confirm metadata survives and no interrupted request is replayed.

## 5. Java, Python and Node clients

The existing demonstration CLIs exercise registration, catalog/capabilities, text completion/streaming and embeddings where supported. They are **opt-in real inference**, potentially making several billable calls. Use the explicit loopback endpoint because legacy CLI defaults may differ:

```bash
java -cp target/xlm-eco-api-1.0-SNAPSHOT.jar us.daconta.xlmeco.GrpcXlmClient 127.0.0.1 50052 openai gpt-4o-mini 'Reply with OK.'
bash python_client/run_client.sh --host 127.0.0.1 --port 50052 --provider openai --model_name gpt-4o-mini --prompt 'Reply with OK.'
./node/node.exe node_client/app.js --host 127.0.0.1 --port 50052 --provider openai --model_name gpt-4o-mini --prompt 'Reply with OK.'
```

Expected output includes registration, capabilities, text responses and supported embedding results. These legacy examples use plaintext channels; do not use them as remote-TLS clients. They are not image-generation CLIs. For generation and capability discovery, use the generated Java/Python interfaces or Node's runtime-loaded [protobuf contract](../src/main/proto/xlm-eco-api.proto), the [README API examples](../README.md#image-generation), or the opt-in harness below. `listModels` is read-only inference discovery; catalog mutations belong to authenticated `XlmAdminService`, documented in [Admin RPCs](dynamic-administration.md#admin-rpcs).

The legacy vector demonstration is separate and mutates data. Run it only against an authorized disposable Milvus-backed XLM instance with external `feature.vectordb.enabled=true`, `milvus.host` and `milvus.port` configured:

```bash
java -cp target/xlm-eco-api-1.0-SNAPSHOT.jar us.daconta.xlmeco.VectorDbTestClient 127.0.0.1 50052
```

It defines a schema, upserts, reads/searches and deletes test vectors; do not treat it as a read-only health probe. See the [documented legacy vector exceptions](image-generation-coverage.md) before interpreting failures.

## 6. Explicit opt-in real-provider verification

These commands require the running XLM server, enabled catalog entries, already-provisioned provider access/quota, and operator authorization for the calls. They are not part of automated coverage or routine startup. Use `--help` to inspect exact options without making requests.

Generate only the non-billable local attachment fixture first:

```bash
mkdir -p target
python src/test/python/remote_structured_image_check.py --write-sample-image target/verification-red-square.png
```

For a deliberately selected generation model, replace the pair only with an enabled model the operator intends to test:

```bash
python src/test/python/remote_image_generation_check.py --endpoint 127.0.0.1:50052 --model google/gemini-2.5-flash-image --output target/remote-image-generation
```

Success saves decoded images and `evidence.json` under the selected output directory with provider, requested/returned model, request ID, MIME/byte count and latency. Normalized failures save their code/status and exit nonzero. Omitting `--model` tests both seeded OpenAI and Google generation models, so prefer explicit bounded selection. `--provision-models --admin-token-file "$XLM_ADMIN_TOKEN_FILE"` additionally mutates Admin by adding missing model entries; use it only when those catalog writes are authorized. `--ca-file`/`--admin-ca-file` support remote TLS.

The older OpenAI analysis harness makes a successful image-analysis request, a malformed-schema rejection request, a local unsupported-model check and a text regression request:

```bash
python src/test/python/remote_structured_image_check.py --host 127.0.0.1 --port 50052 --model gpt-4o-mini
```

Use it only on loopback: this older harness uses a plaintext channel. Expected output includes normalized results, latency, invalid-request evidence and successful text regression. The generic [Admin matrix/restart harness](dynamic-administration.md#real-provider-harness-and-restart) supports TLS and multiple providers; its default discovery selects eligible models **and then executes inference**, so it is not a read-only catalog command. `--demonstrate-switching` also changes/restores defaults with revision guards.

The live browser harness requires the running real Chat UI, the fixture above, installed Edge and `PLAYWRIGHT_MODULE` from the browser setup. Its default `full` scenario makes exactly four intended provider calls: text, its automatic title, image analysis, and image generation. It explicitly selects **openai/gpt-4o-mini** for text/analysis and **openai/gpt-image-2.5-flare** for generation; that latter catalog entry is not a fresh-seed default. Verify access and exact catalog configuration before deliberately enabling this billable scenario:

```bash
XLM_RUN_REMOTE_CHAT=true XLM_REMOTE_CHAT_SCENARIO=full ./node/node.exe gui/chat-ui/remote_chat_browser.cjs
```

For a separately authorized **one-call generation-only** diagnostic, the same exact prompt is sent once to **openai/gpt-image-2.5-flare**. This mode skips text, automatic title and image analysis; it requires no attachment fixture:

```bash
XLM_RUN_REMOTE_CHAT=true XLM_REMOTE_CHAT_SCENARIO=generation XLM_BROWSER_OUTPUT=target/remote-chat-generation ./node/node.exe gui/chat-ui/remote_chat_browser.cjs
```

`BROWSER_CHANNEL` selects an installed Chromium browser for either live mode; the default is `msedge`. To deliberately run the same one-call diagnostic in installed Chrome, use:

```bash
BROWSER_CHANNEL=chrome XLM_RUN_REMOTE_CHAT=true XLM_REMOTE_CHAT_SCENARIO=generation XLM_BROWSER_OUTPUT=target/remote-chat-generation-chrome ./node/node.exe gui/chat-ui/remote_chat_browser.cjs
```

Changing browsers does not authorize an additional provider call or retry a failed request. See the current verification record before interpreting local browser-specific transport observations.

Both modes perform read-only readiness/catalog checks before submission, stop on HTTP or terminal rendering failure, and never replay a request. `run-status.json` records the selected scenario, intended provider-call budget, actual inference HTTP requests, stage status, monotonic request/header/body timings, response byte counts and sanitized transport/JSON error categories. The title call occurs server-side and is not a separate browser HTTP request. A failure before submission may consume fewer calls. Keep each run's output in a distinct directory when preserving diagnostics.

It writes transcript evidence and desktop/narrow screenshots under `target/remote-chat-browser`; `XLM_CHAT_URL` and `XLM_BROWSER_OUTPUT` override those locations. It fails without the explicit environment flag and does not automatically retry. Record failures as failures; consult the [current verification record](image-generation-verification.md) for actual provider/model results rather than inferring availability from catalog membership.

For every manual scenario record PASSED, FAILED, NOT RUN or BLOCKED and distinguish local fixtures, actual remote provider responses, browser observation, and restart evidence. Preserve credentials outside reports, screenshots and source control. Mentor review and automated gates do not establish human Architect Acceptance.

## Current workstation delivery limitation

The [verification record](image-generation-verification.md#one-authorized-diagnostic-and-local-isolation) records an HTTP connection reset during delayed large responses. It reproduces with an independent Python standard-library server/client, without XLM or Flask. Some immediate browser checks pass, but neither Edge nor Chrome is established as a reliable workaround. Successful provider API generation is separate from successful live Chat display. Do not change provider timeouts, TLS validation or security software merely to make a check pass.

The exact task-local reproduction is preserved outside Maven's clean directory in `../.runtime/http-transfer-diagnostic/raw-http11-delayed-control.py`, with its measured `diagnostics.json`. It uses the already-saved local PNG in `../.runtime/openai-generation-verification/`; it makes no provider call and reads no credentials. From this workstation's repository root, `mkdir -p target` followed by `python ../.runtime/http-transfer-diagnostic/raw-http11-delayed-control.py` reruns that diagnostic and writes a fresh report beneath `target/raw-http11-delayed-control/`. Inspect `error_type`, declared/received bytes and timing in the report; the diagnostic's process exit alone is not a success criterion. These task-local artifacts are not packaged or committed project dependencies.
