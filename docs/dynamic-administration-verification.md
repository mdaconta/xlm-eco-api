# Dynamic administration verification

Date: 2026-09-16/17. Baseline `35050f741920006b98eaaff9cdbc744cf4872bee`. This record separates implementation, local automated checks, and real-provider operational acceptance.

## Gates and evidence

- Initial Mentor Design Gate BLOCKED on an undefined Admin security boundary. Architect then required TLS for non-loopback listeners, Admin bearer interceptor authentication and the Google key-disclosure correction within this task.
- Targeted Design Gate PASS WITH FINDINGS; recommended TLS trusted/untrusted/hostname tests incorporated. Earlier preference/default/Console boundary findings incorporated.
- Local focused Registry (6), Admin integration (4), provider-adapter (9), prior structured-image service (5), startup (1) and Security tests ran successfully. Security reports 7 tests with 6 executed and one opt-in network-TLS method skipped. Final `mvn verify -q` exited 0: **32 reported, 31 executed and passed, zero failures/errors, one opt-in network-TLS method skipped**. The final startup regression and all Quality Gate corrections are included.
- Python: 10 existing/new Console/transport tests and 7 harness/protobuf compatibility tests passed. Node runtime compatibility passed for 84 legacy fields and 14 RPCs against the task-start proto; Java regenerated sources/sample client compiled, and old text request behavior was exercised through gRPC. The final Python/Node repeat also passed.
- Registry tests cover re-open/restart persistence, enabled/disabled provider/model state, defaults, malformed/corrupt state, secret absence from persisted JSON, failed atomic replacement, orphan temporary files, process lock refusal, stale concurrent writers, immutable snapshots and credential reload.
- Admin tests exercise every Admin RPC without auth and require UNAUTHENTICATED; focused security tests cover invalid/duplicate/valid auth. Authenticated CRUD/filter/default/revision behavior, model switching/default override, disabled targets and Google secret-marker absence in returned gRPC errors/logs/request URLs are exercised.
- Adapter tests use local fake HTTP, not remote provider calls. Google key is sent only in `x-goog-api-key`; errors omit raw responses/causes. Google/Anthropic image payload/schema behavior and OpenAI model/embedding selection have local coverage.
- No coverage percentage is claimed; the project has no established line/branch coverage report. Behavioral evidence targets the changed invariants; test counts alone are not acceptance.

## TLS environment limitation

An actual loopback TLS trusted-certificate test failed because the workstation Norton interceptor replaced the synthetic server certificate with one issued by `CN=Norton Web/Mail Shield Self-signed Root`. The client trusted only the synthetic XLM certificate and correctly rejected the replacement. This is classified as an environment failure; no certificate validation/trust-store weakening or production workaround was applied.

In-memory TLS using the production gRPC SSL context configuration passed trusted-certificate, untrusted-certificate and wrong-hostname cases. Listener tests reject all insecure non-loopback/wildcard configurations. Plain loopback network gRPC tests exercise Admin bearer authentication. These do not establish network TLS Operational Acceptance.

The real network TLS tests remain opt-in and BLOCKED in this environment. Re-run after the Human provides an approved environment without this interception:

```powershell
$env:XLM_RUN_NETWORK_TLS_TESTS = 'true'
mvn test '-Dtest=SecurityTest'
Remove-Item Env:XLM_RUN_NETWORK_TLS_TESTS
```

Failed/diagnostic outputs are retained locally under ignored `target/`: `security-test-first-failure.log` and `security-tls-diagnostic.log`. Do not publish raw diagnostics; this record includes only the safe issuer classification.

## Required real-provider matrix

At the end of the agent-run automated verification, external credentials were unavailable in the Codex execution environment and no real calls had run. The Human subsequently provisioned external configuration and credentials and performed the Console session recorded below on 2026-09-17. These are **user-reported real-provider results**, supplied in conversation; Codex did not independently observe the remote traffic. Historical Increment 3A results are not counted.

The original nine candidates remain listed; `gpt-4o-mini` is an additional tested model. Image Processing below requires the expected red-square answer, not merely a completed transport request. Structured Output evaluates the displayed result against the common schema. A failed request cannot establish either capability.

| Provider | Model | Image Processing | Structured Output | Latency | Result |
| --- | --- | ---: | ---: | ---: | --- |
| openai | gpt-4.1 | PASS | PASS | 2,124 ms | PASS (user-reported) |
| openai | gpt-4.1-mini | FAIL | PASS | 1,209 / 946 ms | FAIL: yellow / orange instead of red |
| openai | gpt-4.1-nano | NOT VERIFIED | NOT VERIFIED | 165 ms | FAIL: HTTP 403, cause unresolved |
| google | gemini-2.5-pro | NOT RUN | NOT RUN | — | PENDING |
| google | gemini-2.5-flash | NOT RUN | NOT RUN | — | PENDING |
| google | gemini-2.5-flash-lite | NOT RUN | NOT RUN | — | PENDING |
| anthropic | claude-sonnet-4-6 | NOT RUN | NOT RUN | — | PENDING |
| anthropic | claude-opus-4-6 | NOT RUN | NOT RUN | — | PENDING |
| anthropic | claude-haiku-4-5-20251001 | NOT RUN | NOT RUN | — | PENDING |
| openai | gpt-4o-mini (additional) | PASS | PASS | 3,793 / 959 ms | PASS (user-reported) |

**Acceptance count: two passing combinations across one provider; zero providers have three passing models.** At least three passing models on each of three providers are still required. Individual-model testing was deferred at the Human's request; no requirement was waived. Sources supporting candidate selection are in the [gap analysis](dynamic-administration-gap-analysis.md).

### Manual Console session, 2026-09-17

The Human reported external config/secrets directory creation and Windows ACLs limited to their account, SYSTEM and Administrators. Loopback configuration used inference port 50052 and Admin port 50053. Server output reported readiness; the Console listed five providers through its authenticated Admin API. OpenAI initially showed `credential_present: false`, then `true` after Human provisioning and credential reload. This establishes reported presence/loading, not disclosure of the key. No credential values are included in this record.

Codex generated `target/admin-console-test.png` using the existing harness's sample-image mode: a red center square on a blue background. The walkthrough used the common color instruction and strict object schema requiring only a string `color`. The Human selected the file in the Console and supplied these results in sequence:

| Attempt | Requested model (provider: openai) | Returned model | Normalized XLM status / error | Output | Latency | Assessment |
| --- | --- | --- | --- | --- | ---: | --- |
| 1 | gpt-4o-mini | gpt-4o-mini-2024-07-18 | STRUCTURED_IMAGE_COMPLETED | color=red | 3,793 ms | PASS |
| 2 | gpt-4.1-mini | No upstream model returned; response echoes requested ID | STRUCTURED_IMAGE_FAILED / PROVIDER_AUTHENTICATION / HTTP 403 | none | 386 ms | FAIL; Human later reported enabling model access for the key |
| 3 | gpt-4.1-mini | gpt-4.1-mini-2025-04-14 | STRUCTURED_IMAGE_COMPLETED | color=yellow | 1,209 ms | Schema PASS; semantic FAIL |
| 4 | gpt-4.1-mini | gpt-4.1-mini-2025-04-14 | STRUCTURED_IMAGE_COMPLETED | color=orange | 946 ms | Schema PASS; semantic FAIL after file reselection |
| 5 | gpt-4o-mini | gpt-4o-mini-2024-07-18 | STRUCTURED_IMAGE_COMPLETED | color=red | 959 ms | Control PASS with same selected file |
| 6 | gpt-4.1 | gpt-4.1-2025-04-14 | STRUCTURED_IMAGE_COMPLETED | color=red | 2,124 ms | PASS |
| 7 | gpt-4.1-nano | No upstream model returned; response echoes requested ID | STRUCTURED_IMAGE_FAILED / PROVIDER_AUTHENTICATION / HTTP 403 | none | 165 ms | FAIL; cause unresolved |

Completed results reported `success: true`, an empty error message, default error enum `STRUCTURED_IMAGE_ERROR_UNSPECIFIED`, and `provider_http_status: 0` (not a reported upstream success HTTP status). Failed results reported `success: false`, `retryable: false`, and safe message `Provider returned HTTP 403`. Successful transport/schema validation does not certify semantic correctness.

The Human confirmed the loaded key belongs to the project shown in their model-access screenshot, which displayed gpt-4.1-nano enabled. No cause for nano's 403 is established. The adapter currently maps all 401/403 responses to PROVIDER_AUTHENTICATION; this diagnostic limitation must not be treated as proof of a missing model permission.

### Open walkthrough findings

- Confirmed by code inspection: refreshing the Console after saving a default rebuilds provider/model dropdowns without preserving selection; the first provider (anthropic in the observed list) becomes selected. This is a UI selection defect, not server fallback.
- The Defaults button label and shared model editing/test selection controls confuse saved capability defaults with explicit test targets. Saved defaults correctly remain unchanged when only a test dropdown changes.
- Explicit model flow was traced from Console form through gRPC registry resolution into the OpenAI request's model field. The nano response names the requested model; there is no evidence that the saved gpt-4.1-mini default caused its 403.
- Default state reported during the session: chat=openai/gpt-4o-mini, embedding=openai/text-embedding-ada-002, structured_image=openai/gpt-4.1-mini. This is an observed session snapshot, not independently read persistent state or a recommendation.
- At the evidence-only update, safe upstream failure classification and Console selection/labels were unresolved. The subsequent cleanup below corrects Console selection/labels and recovery; more specific upstream 403 classification remains deferred. No production code was changed during the evidence-only update itself.

## Switching, restart and acceptance

Local gRPC tests demonstrate model changes and default/explicit override on one running service; fake-HTTP adapter tests demonstrate provider wire behavior. Console tests exercise selectable provider/model fields through inference RPCs and configuration through Admin RPCs. The subsequent user-reported Console session demonstrates explicit OpenAI model switching without a reported server restart, including successful calls before and after a switch. Cross-provider switching and default-call/explicit-override acceptance remain pending. The harness implements the required A1/A2/B1/C1/default/override sequence and validates returned default model identity against the preceding explicit request.

Registry re-opening proves local persisted state, defaults, enabled states and API-key presence. The later user-reported walkthrough below verifies a full Java restart preserving OpenAI configuration, credential presence, the image default and a disabled model, followed by a successful real request after restarting the old Console. The full remote matrix, cross-provider workflow and independently observed Java/browser automatic recovery remain separately pending. Follow the [operation/harness/restart instructions](dynamic-administration.md).

Initial Quality Gate: FAIL on remaining Ollama/Grok text errors and loss of safe diagnostic fields. Incorporated: typed `SafeProviderFailure` carrying only allowlisted provider, enum code and bounded HTTP status; no raw message/cause. Ollama/Grok non-2xx sync/stream failures cannot succeed or expose bodies; Google/OpenAI preserve safe diagnostics through gRPC. Synthetic-marker gRPC regressions pass. Recommended Vector DB migration documentation incorporated and legacy service registration retained. A final bootstrap regression permits non-secret output-token settings while rejecting credential entries. Targeted implementation Mentor Quality Gate before the manual walkthrough: **PASS**, with no outstanding findings at that review. The subsequent walkthrough findings above remain open and are not covered by that historical verdict. Mentor directly inspected corrective code and final Surefire reports and independently ran diff-check; Maven command exit, Python/Node execution and artifact checks were implementer evidence. Operational Acceptance: PENDING required real-provider and network-TLS evidence. Architect Acceptance: PENDING HUMAN ARCHITECT REVIEW.

No Video Inventory changes, real credential creation, or push. Owner's pre-existing staged AMAD4Q/config entries are preserved and excluded from the task commit.

Final artifact scan: ordinary and shaded JARs exclude `config.properties`, `admin.token`, and registry state. Task-source credential-pattern scan found no key/private-key patterns; this bounded scan supplements secret-flow tests rather than proving arbitrary secret detection. `git diff --check` passed. The original task was staged using a 40-file allowlist, extended to 44 files for the Console cleanup; pre-existing owner changes are preserved separately. The initial signed local commit attempts were blocked: sandboxed attempts produced no signature file; an approved elevated attempt reached the configured SSH signer but the passphrase-protected key could not be unlocked. Those initial attempts created no commit object or task SHA; signing was not disabled and no push occurred. At Console cleanup closeout, a read-only SSH-agent check confirmed an unlocked identity is now available; the signed commit is retried only after the final Quality Gate. No passphrase is requested in prompts. The exact commit command uses `--only --pathspec-from-file=target/task-allowlist.txt` to exclude the owner's staged entries.

## Console recovery and selection verification (cleanup continuation)

Google and Anthropic credential provisioning is intentionally deferred by the Architect. They must remain visibly unconfigured in the Console; no real calls to them are required to verify or close this cleanup. This does not convert the original real-provider matrix to PASS.

Additional user-reported walkthrough evidence before the cleanup:

- OpenAI timeout changed to 120 seconds through Admin RPCs and survived refresh and Java-server restart. OpenAI remained enabled with credential-present true; observed revision after restart was 12.
- Persisted structured_image default changed to openai/gpt-4o-mini. An omitted-provider/model request returned actual gpt-4o-mini-2024-07-18, color=red, COMPLETED, 2,863 ms. Explicit openai/gpt-4.1 overrode that default, returned actual gpt-4.1-2025-04-14, color=red, COMPLETED, 1,293 ms.
- Disabled gpt-4.1 returned MODEL_DISABLED, success=false, 2 ms. Its disabled state and the default survived server restart. The Human subsequently restored the enabled state.
- With the old Console left running during server restart, inference returned INVALID_REQUEST / Client is not registered, 15 ms. Restarting that Console restored operation: omitted selection returned gpt-4o-mini-2024-07-18, color=red, COMPLETED, 1,590 ms. This manual workaround exposed the lifecycle defect addressed by this cleanup; it is not evidence of automatic recovery in the old build.
- Disabled Google returned PROVIDER_DISABLED for gemini-2.5-flash, success=false, 2 ms. The Human restored Google to enabled. Provider disablement across restart was not manually exercised.
- Setting text-embedding-ada-002 as the structured_image default returned FAILED_PRECONDITION at revision 15; a subsequent refresh confirmed the previous default unchanged.
- Google and Anthropic each reported credential_present=false. No credentials or upstream payloads were collected.

The cleanup's automated recovery tests use a real loopback gRPC transport with a deterministic test service implementing the existing registration/capability contracts. They prove Console stop/detect/reconnect/re-register behavior and explicit/default request preservation without billable inference. They are distinct from the Human's Java-server and remote-model results above. Browser/controller state tests and an exact Java-server manual procedure provide the complementary UI/state evidence.

### Cleanup verification results

Mentor Design Gate: PASS WITH FINDINGS. Incorporated independent inference/chat/Admin status, stable-UUID recovery after ambiguous registration, and dirty-draft base-revision protection. A preliminary Quality recommendation to update chat readiness after provider disablement was incorporated with a ready/disabled/enabled regression. The final UI review found a blocking slow-selection editor ownership race. Incorporated correction: disable unresolved editors, enforce rendered-provider draft ownership, and retain read/navigation recovery after failed loads. Mentor directly inspected the correction and independently reran all 15 controller tests successfully. Final Console cleanup Quality Gate: PASS; no outstanding blocking or recommended findings.

Executable evidence after backend corrections: 18 Python GUI/transport/recovery tests passed; 7 harness/compatibility tests passed; Maven verify exited 0 with 32 reported, 31 passed and one existing opt-in network TLS test skipped. Node compatibility passed for 84 old fields and 14 old RPCs. The Node compatibility runner's initial attempt to spawn Git was denied by the execution environment; supplying its supported baseline-file argument passed without changing production code. The Python suite occasionally reports an existing oversized-upload test temporary-file ResourceWarning; no assertion failed. All 15 Node controller behavioral tests passed after the selection-race correction, including save/update retention, conflicts, failed/default read-back, stale responses, new model addition, deferred credential availability, lifecycle polling, and delayed/failed selection editor ownership. These execute the production JavaScript against a deterministic DOM/fetch fixture, not a full browser. The documented Java/browser manual procedure remains available for operator acceptance. The optional additional browser guard for disabled/incompatible models is deferred: server validation remains authoritative and returns normalized rejection. Lifecycle status reads share the bounded probe lock; an outage may delay a status response by the bounded RPC timeouts.

The 44-file task allowlist excludes the owner's AGENTS.md, AMAD4Q process and .codex configuration changes. A bounded source credential-pattern scan found no hits. No real Google/Anthropic credentials were requested, created, read or required for cleanup. Deferred provider provisioning does not block Console cleanup closure; original full-matrix Operational Acceptance is still pending, and Architect Acceptance remains PENDING HUMAN ARCHITECT REVIEW.
