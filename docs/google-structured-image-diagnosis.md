# Google structured-image diagnostic — 2026-09-17

Scope: diagnose Google HTTP 404 after Human-managed Gemini API-key replacement. The previously delivered administration/Console increment was approved by the Architect; this record does not reopen that approval or establish Google inference acceptance. No production code, protobuf, credentials, registry settings, transport policy or provider selection was changed by this diagnostic.

## Evidence

| Check | Provenance | Result | Limit |
| --- | --- | --- | --- |
| Gemini 2.5 Flash model metadata GET | Human-run Python command, output supplied in conversation | HTTP 200; models/gemini-2.5-flash; generateContent listed among supported methods | Metadata lookup is not inference authorization or success |
| Existing Google adapter local HTTP test | Agent-run Maven focused test | 1 passed, 0 failures/errors/skips | Mock server verifies model operation path, image/schema forwarding, header key, and normalization; not remote availability |
| Same model metadata GET with Java OkHttp | Agent-run direct diagnostic using approved external secret loader | Default HTTP/2: 200; forced HTTP/1.1: 200; expected model matches | Read-only diagnostic outside XLM gRPC |
| Intentionally invalid empty generateContent POST | Agent-run direct diagnostic | HTTP/2 and HTTP/1.1: 400 / INVALID_ARGUMENT | Reaches validation; not successful inference |
| Tiny generic PNG, description prompt and simple object schema | Agent-run client → running XLM gRPC → google adapter | STRUCTURED_IMAGE_FAILED / PROVIDER_FAILURE / HTTP 404; requested gemini-2.5-flash; 348 ms | Failed XLM-path call, no actual returned model or output |
| Equivalent tiny PNG/image/schema POST | Agent-run direct Java diagnostic | HTTP/2 and HTTP/1.1: 404 / NOT_FOUND | Diagnostic only; cannot count toward XLM acceptance |
| Minimal text-only generateContent POST, no image or schema | Agent-run direct Java diagnostic | HTTP/2: 404 / NOT_FOUND | Failure also occurs without image/schema; root cause unresolved |

The tiny image is the existing deterministic harness red-square PNG, not the Human's CD-rack image. Direct successful-generation results were not obtained. Remote diagnostic latency was not retained except for the XLM call listed above. No raw upstream error messages, response bodies, authentication headers or key values are included in evidence. Only fixed classifications and HTTP status were printed. Temporary diagnostic programs and non-secret request fixtures are under ignored target/; credential values were read in memory through ExternalSecrets, never written there.

The earlier Human Console attempts on cd-rack-3 returned 400 with the original credential, then 403 and 404 during Human-managed credential replacement/reload. These changing responses alone do not prove a particular credential or project-permission cause.

## Construction review and local verification

The current adapter builds POST /v1beta/models/gemini-2.5-flash:generateContent with x-goog-api-key in a header. Image parts use inline_data with mime_type/data; generationConfig requests application/json and responseJsonSchema. These shapes are documented in Google's [generateContent reference](https://ai.google.dev/api/generate-content) and [structured-output guidance](https://ai.google.dev/gemini-api/docs/structured-output). The local test exercises the path assembly and payload, but does not establish that every live request detail is correct.

Command (native PowerShell):

```powershell
mvn test '-Dtest=DynamicProviderAdapterTest#googleForwardsModelImageSchemaAndNormalizes' -q
```

Result: exited 0, one test passed. Runtime: Temurin Java 21.0.12 at C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe; Maven 3.9.11 at C:/Tools/apache-maven-3.9.11/bin/mvn.cmd.

## TLS environment

Initial Java probes using the default trust store failed with SSLHandshakeException. A sandboxed Windows-ROOT probe could not initialize its prerequisites. The approved host execution using Windows-ROOT and trustStore=NONE completed the HTTPS diagnostics above. Certificate and hostname validation remained enabled; there was no trust-all code, insecure TLS option or production trust change. These environment failures are separate from the later HTTP 404 results.

## Conclusion and next step

The 404 reproduces in a direct text-only request and over both tested HTTP protocols for the tiny image. It is therefore not isolated to XLM, the uploaded CD-rack image, structured-image schema complexity, or HTTP/2. This does not establish the underlying Google-side/account/model cause or prove all adapter behavior correct. Metadata discovery and the currently published deprecation table are insufficient to assert generation availability for this credential/project.

No transport/schema workaround is justified by this evidence. Further remote calls are paused. Next: verify the inference model options offered in Google AI Studio under the same project before choosing another explicit XLM test. Keep current credentials and project settings unchanged pending evidence. The Console's lack of a prominent running/completed indicator is a separate noted usability issue, not a cause of the Google 404.

Google structured-image Operational Acceptance: PENDING. Original 3×3 remote matrix: PENDING. Prior increment Architect Acceptance: APPROVED by the Human in conversation. This diagnostic record: PENDING HUMAN ARCHITECT REVIEW.
