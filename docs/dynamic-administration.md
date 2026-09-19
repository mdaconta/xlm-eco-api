# XLM provider/model administration

This increment provides a persistent provider/model catalog, explicit capability defaults, authenticated gRPC administration, and a local Flask Admin Console. Inference never automatically switches providers/models or retries another model on failure.

## Image-generation extension

The catalog includes `image_generation` independently of `chat` (text), `structured_image` (image input with JSON output), and `embedding`. OpenAI and Google implement generation; Anthropic, Grok and Ollama do not advertise it. Provider support describes implemented adapters; a model's explicit capability set determines allowed operations. Neither catalog membership nor provider documentation establishes remote verification.

Fresh registries seed `openai/gpt-image-1` and `google/gemini-2.5-flash-image` with generation only. Existing registries retain administrator state and receive no automatic model/default changes. To add one, select its provider in Models, create a draft with that exact ID and `image_generation`, then save. Optionally select `image_generation` in Defaults and save the desired pair. Read back the saved configuration before restart. Do not add generation to vision-only models on the assumption that image input implies output.

Registered inference clients use read-only `listModels` to discover configured capabilities without an Admin token. Authenticated Admin mutations remain authoritative and revision guarded. Unsupported provider capability assignments are rejected; model availability is operator configured, not remotely discovered.

`generateImage` accepts an explicit pair or the generation default. Provider-only selection requires the same provider's global default; model-only selection is invalid. No fallback or legacy preference routing applies. Google generation needs its external API key even when text/embedding ADC is configured. `timeout_seconds` also bounds generation; `output_tokens` remains a structured-response setting. One validated inline PNG/JPEG is limited to 3 MiB; HTTP envelopes to 4 MiB + 64 KiB. See [design](image-generation-design.md), [API example](../README.md#image-generation), and [verification](image-generation-verification.md).

## Configuration authority and migration

The [Windows local-server helper](local-server.md) checks/reuses the existing user `.xlm` directories and starts XLM without prompting for keys or overwriting configuration.

The server requires two absolute directories outside source control: `XLM_CONFIG_DIR` and `XLM_SECRETS_DIR`. The Human Architect creates and protects them. The server does not generate real credentials, read keys from prompts, or migrate secrets from the old classpath configuration. `config.properties` is excluded from both ordinary and shaded JARs, including stale resource output.

`XLM_CONFIG_DIR/server.properties` holds startup settings. Start from [server.properties.example](server.properties.example). `registry.json` is created on first startup and thereafter is the single catalog authority. Administrative RPCs update this file; the Console never edits it. A version/revision, provider state/configuration, model state/capabilities, global defaults and migrated legacy defaults persist. The provider-neutral catalog is seeded with the models in the verification table plus `gpt-4o-mini`, `text-embedding-ada-002`, `grok-beta`, and `llama3`. Catalog membership is configuration, not proof of current remote availability.

To preserve existing custom models on initial migration, Human operators can put non-secret `<provider>.default_lm_model`, `<provider>.default_embedding_model`, and `<provider>.vision_models` entries in external `server.properties` before first startup. Alternatively add them with `upsertModel`. Arbitrary previously accepted text model names now require registry configuration. Secret entries in server.properties are rejected. The legacy classpath template is historical; it is no longer production bootstrap authority.

Updates require the last read `revision` as `expected_revision` (credential reload uses `revision`). A stale write returns ABORTED. Validation and adapter construction precede atomic file replacement; the new immutable snapshot is published afterward. In-flight calls retain their selected snapshot. Invalid updates or failed persistence leave the published state unchanged. A file lock prevents two server processes from writing the same registry. Corrupt/unreadable state fails startup; it never silently resets to seeds. Orphan temporary files are ignored. The guarantee covers committed process restarts; abrupt power-loss durability remains filesystem-dependent (directory metadata is not explicitly fsynced on Windows). Back up the non-secret registry separately from secrets.

## Secrets and transport

Human-provisioned secret files are `admin.token`, `openai.key`, `google.key`, `anthropic.key`, and optionally other configured provider IDs followed by `.key`. No real examples are supplied. The administrative token must be independently generated with strong entropy, at least 32 printable non-space characters; length checking does not prove entropy. Restrict directory/file access to the XLM service/operator. Provider files are bounded to 4096 bytes and placeholders count as absent. Secret files are not configuration response fields. `credential_present` reports loaded API-key presence, not authentication validity or quota. Legacy Google text/embedding ADC remains usable with configured project/location; its ambient credentials are not claimed present by the API-key status flag. Structured-image Google uses the explicit key mechanism.

Every Admin RPC, including reads, requires `authorization: Bearer <external token>` metadata. The server interceptor rejects absent, duplicate, malformed or invalid authentication with UNAUTHENTICATED. Metadata is never logged. The token remains server-side in the Console. No credential-write RPC/UI is implemented; after the Human replaces provider files, use `reloadCredentials` to atomically load them for future calls. Admin token rotation requires restart.

Both inference and Admin listeners default to explicit `127.0.0.1`; only explicitly loopback listeners may omit TLS. Any non-loopback/wildcard bind requires certificate and private key, or startup fails. Administrative authentication is still required on loopback. The two services use separate listeners; Admin RPCs are not registered on the inference port. Remote inference is encrypted but this increment does not add inference authorization or change the meaning of self-asserted client registration. Do not expose a quota-bearing inference port to untrusted clients without a separately controlled access boundary.

Console/harness remote channels require a trusted server-CA file and perform normal hostname/certificate validation. They never downgrade to plaintext or override the server identity. Mutual TLS, OAuth and OIDC are not introduced. Listener ports, TLS files, bind addresses, configuration directory and Admin token require restart; provider/model/default/non-secret updates and provider-secret reload affect subsequent requests immediately.

## Admin RPCs

The additive `XlmAdminService` uses the same source proto and Java/Python generation process as existing inference. Node loads it at runtime. Existing RPC paths and field tags remain unchanged.

| RPC | Purpose |
| --- | --- |
| listProviders / getProvider | Enabled state, capabilities, non-secret settings, loaded credential presence, revision; optional capability filter |
| updateProvider | Optional enabled change and allowlisted configuration patch, revision guarded |
| listModels | Optional provider and/or capability filter; model states, capabilities, display names |
| upsertModel | Add/update full model definition, including enabled state; revision guarded |
| getDefaults / setDefault | One provider/model default per capability; set or explicit clear, revision guarded |
| reloadCredentials | Re-read external provider secrets; returns revision only |
| getStatus | Server/schema version, registry revision and readiness; no secret or filesystem paths |

Supported mutable settings: `timeout_seconds` 1–150 for OpenAI image requests, Google/Anthropic HTTP requests and Ollama; `output_tokens` 1–8192 for Google/Anthropic image output; Google `project_id` and `location` for legacy ADC. Defaults are returned in the registry configuration. Unsupported settings are rejected, including API keys and arbitrary endpoints. Endpoint origins and authentication formats remain adapter-owned. OpenAI ordinary chat/embedding retains its existing transport timeout; its configurable timeout applies to image requests.

Unknown/malformed input returns INVALID_ARGUMENT; unknown IDs NOT_FOUND; disabled/incompatible selections or unavailable persistence FAILED_PRECONDITION; stale revisions ABORTED. Responses never return upstream diagnostics or secrets. Structured inference retains its normalized application response/error contract; text provider failures are sanitized gRPC UNAVAILABLE with stable `PROVIDER_FAILURE`, with safe provider/code/HTTP-status details rather than raw provider text.

## Selection semantics

Full explicit provider/model wins over every preference/default. Both IDs, enabled states and capability must validate. A structured-image model without provider is invalid. An explicit provider without model can use the global default only when that default names the same provider. No matching model search is performed.

Legacy chat/embedding requests without an explicit provider honor an existing client capability preference; model-only requests can use that preference. Requests with neither IDs nor preference use the global default. Legacy per-provider model defaults are registry migration metadata; disabling them is allowed and later inference fails safely. Active global defaults must be changed/cleared before disabling their targets. `setPreferredProviders` validates an entire update and stores IDs instead of stale adapter objects. Registration/preferences are ephemeral and must be re-established after restart.

`EmbeddingRequest` adds provider/model at tags 4/5; the existing model-parameter `model` value is honored when the new model field is omitted. Existing image request tags remain unchanged. Omitted image IDs now explicitly resolve the image default.

## Operation

After Human provisioning, run from the repository using native PowerShell:

```powershell
$env:XLM_CONFIG_DIR = 'C:\XLM\config'
$env:XLM_SECRETS_DIR = 'C:\XLM\secrets'
java -jar .\target\xlm-eco-api-1.0-SNAPSHOT.jar
```

These example directory names contain no credentials and must already exist. If Java needs the Windows trusted roots on this workstation, use the previously verified `-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`; never disable validation. That trust setting is unrelated to the isolated synthetic TLS test's explicit test-CA trust.

In a separate PowerShell terminal:

```powershell
$env:PYTHONPATH = "$PWD\python_client\generated"
$env:XLM_ADMIN_ENDPOINT = '127.0.0.1:50053'
$env:XLM_ADMIN_TOKEN_FILE = 'C:\XLM\secrets\admin.token'
python .\gui\chat-ui\app.py 127.0.0.1 50052 5000 openai gpt-4o-mini
```

Open `http://127.0.0.1:5000/admin`. Providers exposes enabled state, capabilities, API-key presence and settings. Models selects a provider, edits/adds a model, and changes enablement/capabilities. Defaults changes or clears capability defaults. Status/Test submits one image through inference gRPC with selected IDs or intentionally omitted IDs; it displays requested/returned identities, normalized result/status/error and latency. The Console uses exact Host/Origin checks, CSRF and WebSocket origin/token checks; it assumes trusted local OS users/processes. No network-facing Flask admin service is introduced. For remote gRPC targets set `XLM_ADMIN_CA_FILE` and `XLM_INFERENCE_CA_FILE` to trusted server-CA files.

## Real-provider harness and restart

Use [dynamic-administration-matrix.json](dynamic-administration-matrix.json) for the nine documented candidate inputs. When `--matrix` is omitted, the harness discovers eligible models from the registry and then invokes inference for those models; this is potentially billable, not a read-only discovery check. All inference goes through XLM, and the harness has no hard-coded provider catalog.

```powershell
python .\src\test\python\admin_matrix_check.py --admin-token-file 'C:\XLM\secrets\admin.token' --matrix .\docs\dynamic-administration-matrix.json --demonstrate-switching --snapshot .\target\admin-before-restart.json --output .\target\admin-matrix.json
```

It uses the same generic red-square image, instructions and object schema for every combination. Evidence records requested/actual model, provider, image processing, schema conformance, normalized status/error, latency and pass/fail. Three distinct passing models on each of three providers are required; skipped/missing credentials do not count. Default switching restores the prior default with revision guards. The snapshot is taken after the switching demonstration has restored the default, so its revision matches the final committed state for restart comparison.

After administrative changes (including a non-default disabled model/provider), stop XLM, restart against the same directories, then:

```powershell
python .\src\test\python\admin_matrix_check.py --admin-token-file 'C:\XLM\secrets\admin.token' --matrix .\docs\dynamic-administration-matrix.json --compare-snapshot .\target\admin-before-restart.json --output .\target\admin-after-restart.json
```

A passing state comparison alone is not full acceptance: at least one real post-restart request must pass, and the full matrix must separately reach nine passes. Console demonstrations must use equivalent provider/model choices. Do not include credentials in evidence or publish target logs blindly.

See [verification](dynamic-administration-verification.md) for actual evidence and pending operational work. No model ranking, routing/fallback, consumer-domain behavior, database, or multiple-image support was added.

Existing vector RPC registration is retained on the inference listener. To migrate an enabled vector backend, place `feature.vectordb.enabled=true`, `milvus.host`, and `milvus.port` in external `server.properties`; these are startup settings, not provider/model Admin settings. No vector-storage implementation or authority change is included. Disabled vector-backend behavior remains the legacy behavior.

## Console selections and restart recovery

The Console keeps the current provider/model selection separate from the persisted defaults. Selecting a model is an explicit test choice, not a registry update or a default change. The model editor is a draft until saved. Saving a default requires a capability and a proposed provider/model; only successful Admin read-back changes the persisted-default display. Failed writes retain drafts. Revision conflicts require reviewing/reloading the affected draft, not an automatic overwrite or write retry. Saving a different section does not discard unrelated edits, the chosen file, test mode, instructions, or schema.

Providers without a required credential are labeled unconfigured, even if enabled. Their registry entries remain editable but cannot be accidentally used for a remote image test from the Console. Google/Anthropic provisioning is deferred; no replacement key is requested or generated by the Console.

The Python process monitors XLM using non-billable registration/capability RPCs approximately every two seconds (each RPC has a three-second timeout). A restarted service loses client registration; the Console re-registers the same session UUID and restores its startup text preference automatically. Inference registration, text preference configuration, and Admin authentication have separate status. A disabled text provider does not block management or other explicit image requests. Admin credentials/TLS remain mandatory as previously documented; genuine configuration/authentication failures are shown and require operator correction.

The page polls `/console/status` to update lifecycle status without reloading the page or replacing form state. Logs from `xlm.console.lifecycle` contain only state transitions, finite error codes and registration/recovery events. They contain no credential values, authentication metadata, raw exceptions, prompts or upstream responses. No inference or administrative mutation is automatically replayed; an in-flight call may fail during restart, and the operator decides whether to submit a new request after recovery. A restart that happens entirely between probes is detected by the lost registration on the next probe or before the next inference submission.

### Deterministic manual restart procedure

Install the updated Console once by restarting its Python process and opening the updated page. Subsequent steps must keep that Python process and browser page running. Use the existing Java server/configuration and a provisioned provider only if a real inference check is desired; credentials are unnecessary for lifecycle/configuration checks.

1. Confirm inference and administration status are ready. Select a non-first provider/model. Note the selected IDs, persisted defaults, provider configuration, and enabled state. Enter an unsaved display-name draft, choose a test file, and note test mode, instructions and schema.
2. Stop only the Java XLM process. Within the probe/timeout interval, the page must show disconnected/recovering and the Python terminal must log `state=disconnected`. Do not refresh the page or restart Python.
3. Restart Java with the same external directories. The Python log must show re-registration and recovery. Inference and administration status return to ready automatically; no operator registration action is required.
4. Verify provider/model selection, unsaved draft, upload and test settings remain unchanged. Saved settings/defaults must match the pre-restart record when deliberately read back; do not overwrite an unsaved draft to perform this check. A genuine conflicting external edit must reject a stale save rather than silently rebase it.
5. Optionally submit a new image test to an already provisioned provider. It must not return Client is not registered. This is a new intentional call; no call from the outage is replayed. Missing Google/Anthropic credentials do not block this procedure.

Focused executable checks:

```bash
python -m unittest discover -s gui/chat-ui -p 'test_*.py'
node gui/chat-ui/test_admin_ui.cjs
```

The loopback recovery test stops and restarts a deterministic gRPC server and asserts disconnected/ready status, fresh registration, restored preferences, unchanged explicit/default request selection, no replay, and clean monitor shutdown. It does not claim independent Java/remote-provider acceptance.
