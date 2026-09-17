# Dynamic provider/model administration: existing-system review

Date: 2026-09-16. Baseline: `35050f7`. Scope: XLM only. This records the pre-implementation review, not acceptance evidence. The subsequent Architect decision and implementation results are recorded in [design](dynamic-administration-design.md) and [verification](dynamic-administration-verification.md).

## Governing documents and state

Read `AGENTS.md`, `docs/AMAD4Q.md`, Increment 3A gap/design/verification records, and the Chat UI design. The requested `docs/AMAD4Q-Process.md` does not exist; the available process is `docs/AMAD4Q.md`. Pre-existing `AM .codex/config.toml`, `AM docs/AMAD4Q.md`, and untracked `AGENTS.md` are owner work and must not be staged as this task's changes.

## Findings

| Area | Current implementation | Required change |
| --- | --- | --- |
| Provider loading | `GenerativeProviderFactory` retains a static ServiceLoader and mutable adapter instances. Prefix filtering iterates Properties entries, omitting inherited template defaults. Service keeps a startup provider map. | One registry snapshot and fresh adapter instances for changed configuration; correct bootstrap property handling. |
| Discovery | `listProviders` reads loaded adapters; `getProviderCapabilities` bypasses that map via the factory. Capabilities originate in classes. No model listing. | Both legacy discovery and new Admin discovery must read the same registry. Capabilities must be constrained by implemented adapters. |
| Text selection | `syncChat` and `asyncChat` use client preferred-provider objects, ignoring `ChatRequest.provider`; adapters generally consume `model_name`. | Explicit request selection wins. Store preference IDs, not adapter references. Preserve legacy preferred-provider operation when explicit selection is absent. |
| Embedding selection | Preferred-provider map selects adapter; OpenAI uses its configured embedding model, ignoring model parameters. No explicit provider field. | Add provider/model fields without changing old tags; use registry defaults with documented legacy preference precedence. |
| Image selection | Required explicit provider/model; registration; 3 MiB media and 64 KiB instructions/schema; OpenAI-only adapter with `vision_models` allowlist. | Registry capability/model validation, optional default resolution, retain explicit behavior and response normalization. |
| Metadata | Adapter capabilities, template defaults, OpenAI `vision_models`, and UI CLI arguments are separate sources. Anthropic is a nonfunctional stub with false capabilities. | Registry owns catalog, states, and capability defaults; adapters own wire behavior only. |
| Persistence | Classpath `config.properties` over template; no administrative persistence or revisions. Maven resources have no secret-config exclusion. | External non-secret atomic JSON registry; separate external credentials; no credential-bearing resources in packaged JARs. |
| Client state | Concurrent client/preference maps; client IDs are self-asserted; registration check/put is not atomic. Preferences hold mutable adapters. | Preserve registration protocol, use atomic registration and immutable preference IDs; registration is never Admin authorization. |
| UI | Flask/Socket.IO, loopback HTTP, per-run CSRF token. Fixed CLI provider/model; image POST uses inference gRPC. | Add Admin views calling Admin gRPC, runtime selectors, normalized test result and latency. Keep credentials out of browser state. |
| Clients/build | Maven generates Java/Python; Python imports generated underscore modules; Node proto-loader uses the source proto. | Additive service/messages/fields; real generated/imported and runtime compatibility tests, including old text requests. |
| Verification | Five Java test methods cover OpenAI mock HTTP, network gRPC, image limits/errors and legacy text. Four Flask tests cover existing UI routes. Historical 3A remote evidence covers one OpenAI model. | Registry/recovery/concurrency/auth/config tests, two more adapters, Admin RPC/UI tests, nine fresh remote combinations and switching/restart evidence. |

## Security blocker before production changes

The 3A design's administration section explicitly requires management authentication/authorization. `GrpcXlmServer` uses `ServerBuilder.forPort` without a restricted bind, TLS, or auth interceptor. Client registration does not establish identity or administrative authority. A new write API cannot safely inherit that boundary. No approved Admin transport/identity/provisioning contract exists in the reviewed docs. See the companion design for a concrete local-only proposal requiring Architect direction.

Existing Google REST text code embeds `apiKey` in query URLs (`GoogleProvider.java:103`, `:198`) and includes the HTTP Response in IOException messages (`:124`, `:214`). The service returns exception messages and logs exceptions (`XlmEcosystemServiceImpl.java:137-138`, `:260-261`). This is a credential-disclosure path on HTTP failure, identified by source inspection; no real credential was exercised or exposed during this review. Raw upstream errors on other legacy paths also need safe handling. Fix and regression-test before invoking real credentials through the expanded surface. Do not silently preserve secret disclosure in the name of compatibility.

Classpath secret configuration is ignored by Git but not excluded by Maven packaging. Externalize secrets and exclude this resource from artifacts. Do not build or redistribute a credential-bearing artifact as part of verification. No secret values were read into tool output; only the local config file's existence was checked. Credential availability and account access remain unverified.

## Candidate remote matrix (documentation support only)

Candidates are explicit test inputs, not claims of account access or remote success. Model availability must be rechecked at execution; replace unsupported candidates explicitly and retain failure evidence.

| Provider | Model | Image Processing | Structured Output | Latency | Result |
| --- | --- | ---: | ---: | ---: | --- |
| openai | gpt-4.1 | NOT RUN | NOT RUN | — | PENDING |
| openai | gpt-4.1-mini | NOT RUN | NOT RUN | — | PENDING |
| openai | gpt-4.1-nano | NOT RUN | NOT RUN | — | PENDING |
| google | gemini-2.5-pro | NOT RUN | NOT RUN | — | PENDING |
| google | gemini-2.5-flash | NOT RUN | NOT RUN | — | PENDING |
| google | gemini-2.5-flash-lite | NOT RUN | NOT RUN | — | PENDING |
| anthropic | claude-sonnet-4-6 | NOT RUN | NOT RUN | — | PENDING |
| anthropic | claude-opus-4-6 | NOT RUN | NOT RUN | — | PENDING |
| anthropic | claude-haiku-4-5-20251001 | NOT RUN | NOT RUN | — | PENDING |

Official sources reviewed: OpenAI model pages for [GPT-4.1](https://developers.openai.com/api/docs/models/gpt-4.1), [mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini), and [nano](https://developers.openai.com/api/docs/models/gpt-4.1-nano); Google [structured output support](https://ai.google.dev/gemini-api/docs/generate-content/structured-output), [Pro](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-pro), [Flash](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash), and [Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-lite); Anthropic [structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs) and [vision](https://platform.claude.com/docs/en/build-with-claude/vision). These document image input and structured output capabilities, not successful XLM invocations.

## Prerequisites and compatibility risks

- Tool inventory: Java 21.0.12 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe`; Maven 3.9.11 at `C:\Tools\apache-maven-3.9.11\bin\mvn.cmd`; Python 3.13.7 at `C:\Python313\python.exe`; repository `node/node.exe` v20.9.0 (not on PATH). Java source target remains 17.
- Approve Admin security/provisioning boundary; provision credentials outside Codex/source control; verify safe status without values; trusted TLS, quotas, and nine-model access required.
- New strict catalog validation can reject previously arbitrary text model names. Bootstrap existing configured models, document migration, and preserve existing valid client flows. Never permit unknown models solely to satisfy compatibility tests.
- Preference/default precedence and partial request selection must be deterministic. No fallback after selection errors or provider errors.
- JSON schema dialect differences must surface normalized failures; no schema weakening. Verify full schema conformance independently in the acceptance harness.
- Current tests were inspected, not rerun. Historical verification is not this increment's evidence. Operational Acceptance: BLOCKED pending implementation, security decisions, and remote evidence. Architect Acceptance: PENDING HUMAN ARCHITECT REVIEW.
