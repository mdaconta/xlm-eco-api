# AMAD4Q — Architect-Mentor-Agent Development for Assured Quality

## Purpose

AMAD4Q defines a software-development workflow in which human architectural authority is preserved while coding agents perform bounded implementation work and an independent Mentor agent provides design and quality review.

The process separates:

* architecture and intent;
* implementation;
* independent review;
* verification evidence;
* operational acceptance;
* final human acceptance.

## Roles

### Architect

The Architect is the human decision authority, supported by conversational AI as needed.

The Architect owns:

* operational intent;
* architecture;
* requirements;
* acceptance criteria;
* ADR approval;
* task boundaries;
* final acceptance;
* source-control push authority.

The Architect determines what the system should do and which architectural constraints govern implementation.

### Coding Agent

The Coding Agent is the implementation agent.

The Coding Agent:

* reads governing artifacts;
* designs a bounded implementation;
* passes the Mentor Design Gate;
* implements the approved design;
* adds and runs verification;
* passes the Mentor Quality Gate;
* creates a local task commit;
* reports evidence for Architect review.

The Coding Agent does not own architecture or final acceptance.

### Mentor

The Mentor is an independent read-only engineering reviewer.

The Mentor:

* does not implement;
* does not modify repository files;
* challenges design assumptions;
* evaluates architectural fit;
* reviews implementation quality;
* reviews verification evidence;
* identifies blocking, recommended, and optional findings.

The Mentor remains independent from the Coding Agent.

## Artifact Flow

The normal architecture and implementation sequence is:

`CONOPS → Operational View → High-Level Architecture → System Architecture ↔ ADRs → Requirements / Verification → Supporting Design → Implementation Plan → Bounded Implementation`

Not every repository requires every artifact type.

The governing principle is that architecture and intent precede implementation.

## Architectural Views

Where applicable, architecture should address:

1. Operational
2. Information
3. Behavior
4. Structure
5. Deployment

Quality attributes, constraints, security, traceability, and verification cut across these views.

## Core Principles

### Human architectural authority

The human Architect remains the final authority for architecture, requirements, and acceptance.

Agents may identify risks or recommend changes, but consequential changes require Architect approval.

### Bounded implementation

Tasks should be narrowly scoped.

A Coding Agent must not silently:

* broaden scope;
* implement future increments;
* redesign architecture;
* add infrastructure not required by the task;
* reinterpret acceptance criteria.

### Simplicity as risk control

Complexity must be forced by a demonstrated requirement.

Prefer the simplest adequate design.

Logical boundaries do not automatically require additional:

* services;
* processes;
* databases;
* queues;
* containers;
* hosts;
* frameworks.

### Evidence over assumption

Unknown behavior should be measured or verified rather than guessed.

Implementation existence does not establish verification.

### Architecture feedback

Implementation or verification evidence may reveal an architectural issue.

When that occurs, the affected decision returns to the Architect rather than being silently resolved inside implementation.

## Bounded Task Definition

A consequential implementation task should identify:

* objective;
* scope;
* exclusions;
* governing architecture and requirements;
* required capabilities;
* important invariants;
* acceptance criteria;
* required verification;
* explicitly deferred work.

The task establishes the boundary within which the Coding Agent may design.

## Design Phase

Before consequential production changes, the Coding Agent produces a concise implementation design covering:

* objective;
* affected components/files;
* implementation approach;
* data/interface changes;
* architecture and requirement alignment;
* important failure cases;
* test strategy;
* end-to-end acceptance path;
* unresolved assumptions.

The design then enters the Mentor Design Gate.

## Mentor Design Gate

The Mentor evaluates whether the proposed design is suitable before implementation begins.

The review considers:

* architectural alignment;
* assumptions;
* boundaries;
* unnecessary complexity;
* compatibility;
* persistence;
* lifecycle;
* concurrency;
* security;
* testability;
* acceptance criteria.

Design Gate verdicts:

* `PASS`
* `PASS WITH FINDINGS`
* `BLOCKED`

A Design Gate PASS authorizes implementation only.

It is not final acceptance.

### Findings

Findings are classified as:

* `BLOCKING`
* `RECOMMENDED`
* `OPTIONAL`

Blocking findings must be corrected and re-reviewed or escalated to the Architect.

Recommended findings must be dispositioned as:

* `Incorporated`
* `Deferred`
* `Rejected`

with rationale.

## Implementation

After Design Gate approval, the Coding Agent implements only the approved scope.

The implementation should preserve:

* architectural boundaries;
* compatibility requirements;
* security constraints;
* documented invariants;
* task exclusions.

Material changes to the approved design reopen the affected Design Gate scope.

## Verification

Verification should proceed from focused to broad:

1. unit/component tests;
2. affected-module tests;
3. integration/contract tests;
4. end-to-end scenario;
5. broader build/verification suite.

Applicable quality evidence may include:

* coverage;
* static analysis;
* dependency analysis;
* vulnerability analysis;
* architecture checks;
* mutation testing;
* concurrency tests;
* performance tests;
* recovery tests;
* real external-system verification.

Coverage and tooling are evidence, not substitutes for correctness.

## Mentor Quality Gate

After implementation and verification, the Mentor reviews:

* implementation against approved design;
* architecture compliance;
* correctness;
* tests;
* regression risk;
* security;
* persistence/data integrity;
* error handling;
* performance;
* maintainability;
* unnecessary complexity;
* verification evidence.

Quality Gate verdicts:

* `PASS`
* `PASS WITH FINDINGS`
* `FAIL`
* `BLOCKED`

A Quality Gate PASS means the reviewed implementation and available evidence satisfy the Mentor review.

It does not establish Operational or Architect Acceptance.

Material changes after the Quality Gate reopen the affected review scope.

## Acceptance Levels

AMAD4Q distinguishes four different decisions.

### 1. Design Gate

Question:

> Is this design acceptable to implement?

Authority:

Mentor review within Architect-approved task scope.

### 2. Quality Gate

Question:

> Does the completed implementation and evidence satisfy engineering review?

Authority:

Mentor.

### 3. Operational Acceptance

Question:

> Does the required workflow actually work in the target or specified verification environment?

Authority:

Verification evidence and, where required, human operation.

### 4. Architect Acceptance

Question:

> Is this work accepted as the correct increment for the system?

Authority:

Human Architect.

Architect Acceptance is the final project-level acceptance decision.

## Git Control

The Coding Agent may:

* inspect repository state;
* stage task-related changes;
* create one local task commit after the required Quality Gate passes.

The Coding Agent must not:

* push;
* rewrite history;
* discard unrelated user work;
* force-reset;
* amend previous commits without explicit instruction.

The local commit is the artifact presented for Architect review.

Only the human Architect decides whether to push.

## Implementation Progression

A preferred incremental development pattern is:

`Foundation → Component Increment → End-to-End Scenario → Measure / Learn → Next Increment`

Each increment should prove a useful capability rather than merely add disconnected code.

Verification evidence from one increment informs the next increment.

## Feedback and Optimization

AMAD4Q is iterative.

When evidence reveals:

* incorrect assumptions;
* inadequate architecture;
* unsuitable technology;
* missing requirements;
* poor quality attributes;
* operational problems;

the process returns to the appropriate earlier artifact or decision.

Feedback should refine the system deliberately rather than produce uncontrolled implementation drift.

## Definition of Ready

A consequential task is ready to implement when:

* objective and scope are clear;
* governing architecture is identified;
* acceptance criteria are defined;
* dependencies are available or explicitly pending;
* unresolved architectural blockers are addressed;
* implementation design exists;
* Mentor Design Gate has passed.

## Definition of Done

A task is done when applicable conditions are satisfied:

* approved scope is implemented;
* no unapproved architecture change exists;
* Design Gate passed;
* blocking design findings are resolved;
* required tests and verification passed;
* Quality Gate passed;
* blocking quality findings are resolved;
* operational evidence is complete or explicitly pending;
* one local task commit exists;
* commit SHA and repository state are reported;
* Architect Acceptance remains pending until the human Architect reviews the result.

## Core AMAD4Q Model

> **Human + conversational AI define intent and architecture. The Coding Agent designs and implements bounded work. An independent Mentor challenges the design and implementation. Verification provides evidence. The human Architect decides what is accepted and pushed.**
