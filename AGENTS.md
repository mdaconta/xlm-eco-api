# AGENTS.md — XLM Eco API

This repository follows **AMAD4Q — Architect-Mentor-Agent Development for Assured Quality**.

The human Architect owns architecture, requirements, final acceptance, credentials, and push authority. Codex is the Coding Agent. A separate read-only Codex Mentor performs independent Design and Quality Gates.

## 1. Agent Workflow

Mentor review is required for changes to production code, protobuf/gRPC contracts, provider integrations, security or credential behavior, persistence/configuration behavior, deployment/runtime behavior, external integrations, acceptance evidence, or ADR-controlled behavior.

Mentor review is not required for typo, formatting-only, or other non-consequential documentation changes unless the Architect requests it.

When Mentor review is required:

1. Read the governing documentation and relevant existing implementation.
2. Produce a concise implementation design.
3. Pass the Mentor Design Gate.
4. Implement the approved design.
5. Add and run required tests and verification.
6. Pass the Mentor Quality Gate.
7. Resolve or disposition findings.
8. Create one local task commit.
9. Report results for Architect review.

Do not silently redefine architecture, requirements, API contracts, or task scope.

The shared Mentor is `~/.codex/agents/mentor.toml`. Project-specific rules in this file remain authoritative.

## 2. Governing Documentation

Read applicable repository documentation before implementation.

Precedence:

1. Approved architecture and architectural decisions under `docs/`
2. Approved API/interface and protobuf contracts
3. Approved subsystem or capability designs
4. Verification and compatibility requirements
5. Implementation plans
6. README and user/developer documentation
7. Current bounded task

The `.proto` contract is authoritative for implemented gRPC wire interfaces but does not override approved architectural decisions.

If governing documents materially conflict, identify the conflict, implementation impact, and smallest correction. Stop if the conflict blocks the task.

Do not invent an implementation workaround for an architectural conflict.

Architecture, API, implementation, and verification documentation belongs under the repository `docs/` directory unless explicitly directed otherwise.

## 3. Core Engineering Rules

* **Complexity must be forced by a demonstrated requirement.**
* Prefer the simplest adequate solution.
* Do not implement future capabilities speculatively.
* Do not broaden task scope silently.
* Preserve documented logical and provider boundaries.
* Consequential architecture changes require Architect approval and, where appropriate, an ADR or equivalent design decision.
* Unknown quantitative thresholds are measured rather than guessed.
* `Implemented` and `Verified` are separate states.
* Provider-specific behavior belongs behind provider abstractions.
* Public XLM contracts must remain provider-neutral unless an approved requirement explicitly requires otherwise.
* Application/domain semantics from XLM consumers must not leak into XLM general-purpose APIs.

## 4. Technology and Environment

Unless superseded by an approved architecture decision:

* Primary server implementation: Java.
* Build: Maven.
* External API: protobuf + gRPC.
* Provider integrations: provider-specific adapters behind XLM interfaces.
* Java, Python, and Node clients must remain compatible where currently supported.
* Java and Python protobuf artifacts are generated through the established build process.
* Node may consume the protobuf definition using its established runtime mechanism.
* Mutable configuration and secrets must remain outside source control.

Do not add another framework, persistence technology, message broker, workflow engine, configuration system, or cloud service without a demonstrated requirement and approved design change.

Development workstation: Windows 11.

* Codex tool execution: native Windows PowerShell by default.
* Human interactive shell: Git Bash unless otherwise specified.
* Use Git Bash, WSL, or `cmd.exe` from Codex only when the task explicitly requires it.
* Do not silently switch shells or toolchains to make a command succeed.

Before substantial Java, protobuf, or cross-language client work, report relevant executable locations and versions where they materially affect verification.

Do not disable TLS or certificate validation to make provider or dependency access succeed.

## 5. Provider and Model Boundaries

XLM exists to provide standardized, provider-neutral access to generative-model capabilities.

Provider adapters own:

* provider-specific authentication use;
* provider-specific URLs and transport formats;
* provider-specific request translation;
* provider-specific response parsing;
* provider-specific error interpretation.

Core XLM owns:

* public protobuf/gRPC contracts;
* provider/model selection semantics;
* normalized responses and errors;
* capability validation;
* provider/model metadata exposed through XLM;
* cross-provider behavioral consistency.

Do not place provider-specific HTTP payloads or response handling in the gRPC service layer when that behavior belongs in an adapter.

Do not duplicate general routing, validation, or configuration behavior across provider adapters.

## 6. API and Compatibility Rules

Prefer additive protobuf evolution.

Before changing an existing protobuf message, field, enum, or RPC, consider compatibility with existing Java, Python, Node, and other consumers.

Do not:

* reuse an existing protobuf field number for a different meaning;
* renumber established fields;
* silently change existing field semantics;
* remove an existing RPC or field without explicit architectural approval;
* introduce provider-specific consumer semantics into general-purpose messages.

Where practical, verify generated or consumed client compatibility after protobuf changes.

A compiling server alone is not sufficient evidence of API compatibility.

## 7. Credentials and Secrets

The human Architect retains authority over real provider credentials.

Codex must never:

* request that credentials be pasted into prompts;
* commit credentials or secret-bearing configuration;
* print credentials to output;
* include credentials in test evidence;
* expose credential values through gRPC APIs or UI;
* log credentials;
* copy credentials into documentation.

Code may consume credentials through the approved XLM configuration/secret mechanism after the Architect provisions them.

Administrative functionality may report whether a credential is configured but must not return its value.

Treat accidental credential exposure as a blocking security issue.

## 8. Implementation Design and Design Gate

Before production-code changes requiring Mentor review, produce a concise design covering:

* objective, scope, and exclusions;
* affected modules/files and implementation approach;
* protobuf/API changes;
* provider/model impacts;
* persistence/configuration impacts where applicable;
* compatibility considerations;
* important invariants and failure cases;
* planned tests and end-to-end scenario;
* risks and unresolved assumptions.

The Mentor Design Gate must pass before implementation.

Design Gate verdicts: `PASS`, `PASS WITH FINDINGS`, or `BLOCKED`.

Blocking findings require correction and targeted re-review or escalation to the Architect.

Recommended findings must be dispositioned as `Incorporated`, `Deferred`, or `Rejected` with rationale.

A Design Gate PASS authorizes implementation only.

## 9. Testing and Verification

Use the smallest relevant test first, then expand:

1. focused unit/component tests;
2. affected provider or service tests;
3. protobuf/client compatibility tests;
4. integration/contract tests;
5. required real-provider or end-to-end scenario;
6. broader Maven verification.

For changed deterministic logic, inspect line and branch coverage where available, review uncovered paths, and add tests for meaningful missing behavior.

Coverage is evidence, not the definition of correctness.

Use additional quality checks where applicable, including static analysis, dependency analysis, security testing, concurrency testing, performance testing, and recovery testing.

Introduce new quality tooling incrementally. Do not suppress findings merely to make a gate pass.

Mocks and stubs are valid for automated tests but do not replace required real-provider verification when the acceptance criterion concerns actual provider/model behavior.

## 10. External Provider Verification

When a task changes or claims support for an external provider or model, verification must distinguish:

* mocked/provider-simulated tests;
* XLM integration tests;
* real remote provider tests.

Do not claim a provider/model capability was verified from configuration, documentation, or mocks alone.

Real-provider evidence should record, where applicable:

* provider;
* requested model;
* actual model returned;
* capability exercised;
* normalized result or error;
* latency;
* success criteria.

Never include credentials in evidence.

Provider quota, authentication, network, or account limitations must be reported separately from implementation correctness.

## 11. Mentor Quality Gate

After implementation and verification, run the Mentor Quality Gate.

The Mentor must assess:

* task completion;
* architecture/design compliance;
* protobuf/API compatibility;
* provider abstraction integrity;
* correctness;
* tests and coverage;
* credential/security behavior;
* concurrency and persistence where applicable;
* operational risk;
* performance;
* maintainability;
* unnecessary complexity;
* regression risk;
* end-to-end evidence.

Quality Gate verdicts: `PASS`, `PASS WITH FINDINGS`, `FAIL`, or `BLOCKED`.

Blocking findings require correction and targeted re-review.

Material changes after the Quality Gate reopen the affected review scope.

A Quality Gate PASS establishes only the reviewed implementation quality and available evidence; it does not establish Operational or Architect Acceptance.

## 12. Operational Verification and Acceptance

For required manual, remote-provider, or environment-specific verification, record:

* prerequisite state;
* exact command or user action;
* expected result and success criteria;
* relevant environment limitations.

Manual Verification status: `PASSED`, `FAILED`, `NOT RUN`, or `BLOCKED`.

A timeout or interrupted mutating command does not prove the operation stopped. Determine actual state before retrying.

When an environment or provider limitation causes failure, do not weaken security or validation merely to obtain a passing result.

AMAD4Q distinguishes:

1. **Design Gate** — implementation may proceed.
2. **Quality Gate** — reviewed implementation/evidence pass Mentor review.
3. **Operational Acceptance** — required workflow succeeds in the specified environment.
4. **Architect Acceptance** — human Architect accepts the committed work and decides whether to push.

Do not report a Mentor verdict as Operational or Architect Acceptance.

## 13. Traceability

Where governing XLM requirements, ADRs, capability definitions, or acceptance criteria exist, tests and evidence should reference them.

Do not mark a capability or requirement Verified merely because implementation exists.

For provider/model support, distinguish clearly between:

* configured;
* implemented;
* automatically tested;
* remotely verified.

## 14. Git Rules

Codex may inspect status/diffs, stage task-related changes, and create one local task commit after the required Quality Gate passes.

Codex must never:

* run `git push`;
* force-reset user work;
* discard unrelated changes;
* rewrite Git history;
* amend a prior commit unless explicitly instructed.

Before committing:

1. inspect `git status`;
2. inspect the final diff;
3. confirm only task-related files are staged;
4. confirm the required Quality Gate passed;
5. create one clear local task commit.

The local commit is the artifact presented to the human Architect.

Only the human Architect decides whether to push.

## 15. Completion Report

At task completion report:

1. objective, implemented scope, and exclusions;
2. Design Gate verdict and finding dispositions;
3. files changed and governing decisions addressed;
4. protobuf/API/provider changes;
5. tests, build, coverage, and quality results;
6. real-provider/end-to-end and Manual Verification results;
7. Quality Gate verdict and finding dispositions;
8. Operational Acceptance status;
9. remaining limitations or risks;
10. commit SHA/message and final `git status`.

Architect Acceptance must be reported as:

`PENDING HUMAN ARCHITECT REVIEW`

Codex must not claim Architect Acceptance or permission to push.

## 16. Definition of Done

A task is complete when applicable conditions are satisfied:

* approved scope implemented with no unapproved architecture change;
* Design Gate passed and blocking Design findings resolved;
* applicable protobuf/client compatibility has been verified;
* applicable automated tests and verification passed;
* required real-provider evidence is complete or explicitly pending;
* Quality Gate passed and blocking Quality findings resolved;
* required operational evidence is complete or explicitly pending;
* one local task commit exists and its SHA/working-tree state are reported.

Final acceptance remains with the human Architect.

> **Optimize for verified cross-model capability added without violating architectural intent, not for volume of generated code.**
