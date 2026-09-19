# Image-generation verification

Status: implementation and aggregate automated gates pass; live browser generation FAILED with an independently reproduced host/HTTP transport reset, so full Operational Acceptance is BLOCKED by the environment. The resetting component remains unidentified. Architect Acceptance: PENDING HUMAN ARCHITECT REVIEW.

## Evidence categories

| Provider/model | Configured in fresh seed | XLM text | XLM structured image input | XLM image generation | Provider-documented generation | Remote generation this task |
|---|---|---|---|---|---|---|
| openai / gpt-image-1 | Yes; added to owner catalog | No | No | Implemented; local HTTP/gRPC fixture verified | Yes | PASSED on explicitly authorized retry |
| openai / gpt-image-2 | No; explicitly added to owner catalog | No | No | Same implemented Images adapter | Yes | FAILED: HTTP 403 access denial |
| openai / gpt-image-2.5-flare | No; explicitly added to owner catalog | No | No | Same implemented Images adapter | Yes | PASSED through XLM gRPC |
| google / gemini-2.5-flash-image | Yes; added to owner catalog | No | No | Implemented; local HTTP fixture verified | Yes | PASSED through XLM gRPC |
| openai / gpt-4o-mini | Yes | Yes | Yes | Rejected locally | Not claimed | NOT RUN |
| anthropic / claude-sonnet-4-6 | Yes | No | Yes | Rejected locally | Not claimed | NOT RUN |

The owner explicitly authorized adding the two generation-only model entries through authenticated Admin to the existing catalog and one request to each provider. No defaults were changed. Google model input support does not imply XLM structured JSON support. Prior remote structured-analysis evidence remains in its historical records and does not verify the new generation operation.

## Real remote generation (2026-09-18)

The updated packaged Java server used the owner's existing external configuration and secrets. The opt-in Python harness called XLM gRPC, not providers directly, with the prompt “Draw one blue circle on a plain white background. No text.” Catalog revision after the two additions was 22.

- Google requested/returned `gemini-2.5-flash-image`: PASSED, `GENERATED_IMAGE`, `image/png`, 803,024 bytes, 4,648 ms end-to-end, request ID `8e944daf-bfae-4a87-8148-6791a961cbf2`. The saved 1024×1024 image was visually inspected: blue circle on white background. This proves the API/provider path, not a live browser submission.
- Initial OpenAI `gpt-image-1` attempt: FAILED, `PROVIDER_AUTHENTICATION`, upstream HTTP 403, no image, 1,729 ms, request ID `bb661706-38c7-49f1-9404-64739d925d2d`. No actual generated model was observed. The sanitized result does not establish the specific account cause.

After the owner adjusted provider permissions, they explicitly authorized one retry and one call each to two additional generation-only catalog models. Catalog revision became 24; defaults remained unchanged. These were three calls without automatic retries:

| Requested model | Result | PNG bytes | End-to-end latency | Request ID |
|---|---|---:|---:|---|
| `gpt-image-1` | PASSED | 1,062,227 | 8,836 ms | `02ea3f6f-b81b-46e2-bc88-1f3c1f69e627` |
| `gpt-image-2` | FAILED: `PROVIDER_AUTHENTICATION`, HTTP 403 | 0 | 644 ms | `44c2c114-a211-466b-9ccd-d76a5e8b898b` |
| `gpt-image-2.5-flare` | PASSED | 759,370 | 5,116 ms | `c6fdb360-c0e5-42ac-98eb-36ddf316bc72` |

OpenAI's image response does not independently report the model: the normalized model is the requested selection fallback. Google's response reports its model version. The harness's `actual_model` field is the normalized XLM value, not proof of independent provider-reported identity. Failure rows do not establish that generation occurred. Latencies are individual observations, not a comparative benchmark.

Operator direction after these checks: use the two working OpenAI selections, `gpt-image-1` and `gpt-image-2.5-flare`. Do not use `gpt-image-2` or `gpt-image-2-2026-04-21` for further verification. The dated model was not added or called. This direction does not diagnose a provider bug or silently disable an existing catalog entry.

Replay command (billable; only with operator authorization): `python src/test/python/remote_image_generation_check.py`. It uses the existing catalog. `--provision-models --admin-token-file <external-token-file>` explicitly adds missing models through Admin; it never changes defaults or overwrites existing model entries. TLS CA/endpoint options are available through `--help`. Result metadata and generated images go to `target/remote-image-generation`; errors never print credential values or raw upstream details.

## Executed local verification

- Adapter tests use a real local HTTP server and inspect request authentication placement, provider payloads, model provenance, validated PNG/JPEG output, missing/multiple/corrupt image rejection, malformed/oversized JSON envelopes, safe HTTP failure and quota classification, and redirect rejection.
- Service tests use network gRPC, verify explicit selection/defaults, unsupported capability rejection without HTTP, discovery/Admin metadata, and independent model capability persistence after registry reopen.
- `ImageGenerationServiceTest.pythonClientCrossesActualJavaGrpcBoundary` invokes the generated Python client against the Java server, exercising catalog discovery, successful image normalization, denial and unregistration. The upstream provider is simulated.
- Generated-image validation tests exercise real PNG/JPEG decoding, MIME mismatch, invalid base64, compressed byte bounds, oversized dimensions, truncated images and unsupported formats.
- Full coverage, final browser results, and Mentor Quality Gate are recorded at closeout below. Configured gates must not be mistaken for passing results. See [coverage baseline and measured results](image-generation-coverage.md).

## Required operational verification

Use the [Chat walkthrough](chat-ui-user-verification.md) against the operator-provisioned XLM instance. Record provider, requested/returned model, capability, request ID, status, measured latency and observed result; never record keys, headers or raw upstream exceptions. Exercise text, attachment recognition, explicit generation, suggestion/override, unsupported model, and saved metadata after an actual server restart. A restart must not silently create a generation default or replay an in-flight request.

Current remote API status: Google generation and OpenAI `gpt-image-1` / `gpt-image-2.5-flare` PASSED; `gpt-image-2` remains FAILED with access denial. The packaged server was stopped and restarted through the launcher using the same external configuration; subsequent catalog additions continued from the persisted revision. Read-only Chat discovery then returned revision 24 and the four saved generation-only entries. Local fixture browser evidence remains separately labeled.

## Live browser workflow (2026-09-18)

The owner authorized four potentially billable OpenAI operations: text, its automatic title, one red-square analysis and one `gpt-image-2.5-flare` blue-circle generation. The first browser attempt returned HTTP 503 at the Console readiness probe before any provider invocation. It was not replayed automatically. Logs and the handler boundary established that no billable request was submitted in that attempt.

The corrected harness records stages and HTTP submission counts before work, checks readiness and preserves sanitized failure evidence. The subsequent authorized attempt observed three inference HTTP submissions (the text route also invokes its title):

| Scenario | Observed result |
|---|---|
| Catalog and explicit selection | PASSED; configured models discovered |
| Generation suggestion accepted on text model | PASSED; incompatible submission disabled |
| User override back to text | PASSED; prompt returned `OK` through `openai/gpt-4o-mini` |
| Automatic scoped title | PASSED; `A Simple Acknowledgment` appeared on that text entry |
| Real file chooser and image input | PASSED; JSON `{"color":"red"}`, returned model `gpt-4o-mini-2024-07-18`, 1,396 ms |
| `gpt-image-2.5-flare` generation | FAILED at browser result/display boundary: HTTP 200 observed, then sanitized generic request failure before result metadata was displayed; image-decode wait timed out |
| Live narrow layout after generation | NOT RUN because the earlier generation assertion failed |

Evidence is preserved outside the repository in `../.runtime/remote-chat-browser/run-status.json` and `failure.png`. No automatic provider retry occurred. HTTP 200 alone does not establish a valid generation result or successful display. Prior API success does not replace this missing end-to-end evidence.

Generation timeout boundaries are distinct: provider HTTP call/read timeout defaults to 150 seconds, Chat's generation gRPC deadline is 160 seconds, and the browser harness action wait is 170 seconds. Browser `fetch` has no application-installed abort timer here. The failed browser run received HTTP 200 about 4.9 seconds after submission, then showed the generic read/request error; the later 170-second wait timeout does not identify the original cause. Do not increase a timeout or blame provider latency without identifying the boundary that failed.

### One authorized diagnostic and local isolation

The owner separately authorized one additional `gpt-image-2.5-flare` call. The generation-only harness submitted exactly one request, with no text/title/analysis calls. It observed HTTP 200 and Content-Length 1,009,477 after 6,590 ms, followed by `net::ERR_CONNECTION_RESET`. This reset was recorded **before** the task-owned headless browser was stopped to release a hung diagnostic read. The original evidence is preserved in `../.runtime/remote-chat-generation-diagnostic/run-status-before-browser-stop.json`; the final ledger is in that same directory. Later errors caused by browser cleanup are not evidence of the original reset's source.

The diagnostic harness itself previously waited without a bound for response-body inspection after a failed transfer. This was corrected with a five-second diagnostic-observation bound; it does not change provider or application timeouts, retry a request, or cancel a provider call. Terminal UI failures now end the test without waiting for an image indefinitely.

No further provider calls were made during isolation. Local fixtures used a previously saved 759,370-byte PNG:

| Local control | Result |
|---|---|
| Five immediate realistic-size transfers, actual Socket.IO and socket shim | PASSED; complete 1,012,727-byte JSON, valid base64 and decoded image |
| Seven-second fake-provider delay, Edge with actual Socket.IO | FAILED; HTTP 200 after 7,008 ms, declared 1,012,730 bytes, then connection reset |
| Same delay, Edge with socket shim | FAILED; HTTP 200 after 7,010 ms, same reset; active Socket.IO traffic is unnecessary |
| Same delay, Chrome full browser fixture | PASSED once; complete 1,012,730-byte JSON read 72 ms after headers, image and narrow layout passed |
| Same delay, Python urllib against fixture | FAILED; HTTP 200 after 7,010 ms, `ConnectionResetError` at 25,961 ms |
| Same delay, Chrome generation-only fixture | FAILED; HTTP 200 after 7,008 ms, `net::ERR_CONNECTION_RESET` at 25,962 ms and browser `Response.json` `TypeError` |
| Independent Python stdlib HTTP/1.1 server and urllib client; no XLM, Flask, Socket.IO, gRPC, browser, authentication or provider | FAILED; HTTP 200 and complete server write/flush of 1,012,730 bytes at 7,020 ms; client received 917,504 bytes before `ConnectionResetError` at 25,982 ms |

The raw control uses HTTP/1.1, `Connection: close`, JSON content type, a seven-second delay and the same response byte count. Its script and evidence are preserved as disposable task artifacts in `target/raw-http11-delayed-control.py` and `target/raw-http11-delayed-control/diagnostics.json`; an earlier raw HTTP/1.0 control also failed. This reproduces the failure independently of the XLM application/framework stack. No application timeout or image validation change was made to compensate for it.

These results do not establish an Edge-only problem or Chrome as a reliable workaround. The response reset is distinct from a provider/gRPC deadline; the exact host/network/transport component remains unidentified. The first uninstrumented local image-visibility timeout had no saved transfer evidence and is not counted as proof of the same failure. Full live browser generation remains FAILED and Operational Acceptance is environment-BLOCKED; no TLS, hostname verification, or security software was disabled. A new live test requires separate operator authorization after the environment is repaired or changed.

## Automated and documentation closeout

Clean `mvn -q clean verify` passed (80 executed tests, one opt-in skip); Python coverage runner passed 41 tests. Java line/branch coverage is 92.72%/91.03%; Python statement/branch coverage is 99.79%/98.46%. See [coverage evidence](image-generation-coverage.md) for baseline, all per-class exceptions and the separately failed optional network TLS diagnostic. Node compatibility preserved 84 legacy fields and 14 RPCs. Admin controller, Chat controller, immediate local real-browser fixture, generation-only harness fixture, and Windows launcher checks passed. The delayed diagnostic failures above are separate from these passing checks.

The README was rendered and visually inspected; local links and anchors were checked, and external README targets were requested. Two obsolete 404 links (architecture image and contact form) were replaced with current architecture text/document links and the author's reachable website. The [run-and-test guide](run-and-test.md) consolidates actual commands, expected output, report locations and the distinction between local and potentially billable checks. Generated artifacts and secret-bearing configuration are excluded from the task files.

Mentor Design Gate: **PASS WITH FINDINGS**; routing compatibility and pre-decode dimension validation findings were incorporated. Final Mentor Quality Gate: **PASS WITH FINDINGS**, with no remaining implementation or verification-tooling blockers. UI selection/refresh preservation, attachment cleanup, image failure handling, scoped titles, browser fixtures, bounded diagnostics and documentation recommendations were incorporated. Individually below-90% classes, existing vector defects and the separately documented TLS limitation remain explicit acceptance items; they were not excluded to raise coverage. The final review independently inspected the raw HTTP reproduction and supports a local commit with **Operational Acceptance: environment-BLOCKED**. Architect Acceptance remains **PENDING HUMAN ARCHITECT REVIEW**; publication is manual.
