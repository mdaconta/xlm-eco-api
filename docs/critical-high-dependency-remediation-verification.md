# Critical and high dependency remediation verification

Date: 2026-09-16. Branch: `security/critical-high-dependencies`. This record covers local verification of the dependency update in the accompanying design. GitHub alert closure requires publication and a Dependabot rescan.

Mentor Quality Gate: **PASS**. The Mentor inspected the manifest, lockfile, and documentation changes, independently checked resolved npm versions and removal of the vulnerable `grpc-tools` dependency path, and found no remaining blocking or recommended items after the live Node client check.

## Resolved dependency graph

| Dependency | Resolved version or result | Evidence |
| --- | --- | --- |
| `@grpc/grpc-js` | 1.12.7 | Regenerated lockfile and installed `node_modules` |
| `protobufjs` | 7.6.6 | Regenerated lockfile and installed `node_modules` |
| `uuid` | 11.1.1 | Regenerated lockfile and installed `node_modules` |
| `grpc-tools`, `tar`, `minimatch`, `brace-expansion` | Absent | Lockfile inspection after removing unused `grpc-tools` |
| `jackson-databind`, `jackson-core`, `jackson-annotations` | 2.18.10 | Maven `dependency:tree` |

## Checks performed

- **Owner-run Git Bash:** bundled npm 10 `install --package-lock-only --ignore-scripts --no-fund` completed. Then `npm ci --ignore-scripts --no-fund` installed 37 packages and audited 38; `npm audit --audit-level=high` reported **0 vulnerabilities**. These npm outputs were supplied by the repository owner. The agent did not independently run an online npm audit because its shell failed certificate verification. TLS verification was not disabled.
- **Agent-run:** `mvn test -q -o '-Dskip.npm=true' '-Dskip.installnodenpm=true'` passed. `mvn verify -q -o '-Dskip.npm=true' '-Dskip.installnodenpm=true'` passed. After the owner ran `npm ci`, ordinary `mvn verify -q -o` passed with no npm skips.
- **Agent-run:** `python -m unittest discover -s gui/chat-ui -p 'test_*.py' -v` passed all 4 tests.
- **Agent-run Node compatibility:** bundled Node loaded the local proto through `@grpc/proto-loader`, found `generateStructuredImage`, and called `uuid.v4()` successfully with the installed packages. The existing `node_client/app.js` then ran against the newly packaged XLM server with the upgraded `@grpc/grpc-js`; provider registration and capability queries succeeded, `syncChat` and `asyncChat` both returned `OK`, an embedding returned, and the client unregistered successfully (exit code 0).
- **Agent-run real remote check:** `python src/test/python/remote_structured_image_check.py` against the newly packaged XLM server passed. A generic `image/png` red-square image and JSON schema sent to `openai` returned `STRUCTURED_IMAGE_COMPLETED`, `success=true`, model `gpt-4o-mini-2024-07-18`, `{"color":"red"}`, and measured image-call latency of 2,511 ms. An unsupported model returned `UNSUPPORTED_MODEL`. A malformed schema sent to the provider returned normalized `PROVIDER_FAILURE`, HTTP 400, `retryable=false`. Existing `syncChat` returned `OK`.
- **Agent-run Git:** `git diff --check` passed. Windows line-ending warnings for `package.json` and `pom.xml` were informational.

## Publication and policy follow-up

The repository owner previously published two unsigned commits to `master` under an administrator bypass. They remain unsigned in history; this change does not rewrite them. The owner chose a solo-maintainer policy and updated the active `push-rule-1` ruleset. The agent read back GitHub's ruleset API after the save: default branch targeting, pull request required with zero approvals, code-owner review disabled, signed commits required, and an empty bypass list. The owner reported registering the existing SSH public key as a GitHub signing key; this account-level registration was not independently queried by the agent. No push, merge, or alert dismissal is claimed in this record.
