# Critical and high dependency alert remediation

Status: Mentor Design Gate PASS WITH FINDINGS, 2026-09-16. Scope is the current critical and high Dependabot alerts shown by the repository owner. Easy moderate fixes that ride on the same dependency changes are welcome; remaining moderate alerts are nonblocking by owner direction. No remote push, ruleset edit, history rewrite, or alert dismissal is part of this change.

## Findings and bounded changes

| Manifest | Observed dependency | Decision |
| --- | --- | --- |
| `package-lock.json` | `tar@6.2.1`, `minimatch@3.1.2`, and `brace-expansion` descend from `grpc-tools@1.12.4` through `@mapbox/node-pre-gyp` / `rimraf`. | Remove `grpc-tools` from root `package.json`; XLM's Node client dynamically loads the local proto with `@grpc/proto-loader` and has no `grpc-tools` usage. Regenerate the lockfile with npm. A temporary offline trial pruned these packages. |
| `package-lock.json` | `protobufjs@7.4.0` descends from `@grpc/proto-loader@0.7.13`. | Add a root npm override for `protobufjs` at `^7.5.6`, then regenerate the lockfile. This stays within proto-loader's existing `^7.2.5` range. The patched 7.5.6 line addresses the visible critical and high protobufjs advisories. |
| `package-lock.json` | Direct `@grpc/grpc-js@1.12.2`. | Set `~1.12.7` and regenerate the lockfile; 1.12.7 is patched for both visible high crash advisories on this line. Tilde avoids known vulnerable 1.13.0-4 and 1.14.0-3 ranges that a broad caret could permit. |
| `package-lock.json` | Direct `uuid@11.0.2`, a visible moderate finding. | Raise the direct minimum to `^11.1.1`, the patched release on the current major line; the client only calls `v4()`. |
| `pom.xml` | Direct `jackson-databind@2.17.2`. | Raise to 2.18.10 on the patched 2.18 line. This fixes the two visible high Jackson advisories and additional moderate findings. Keep Jackson core/annotations aligned if dependency resolution shows a conflict; avoid broader Maven upgrades. |

Advisory evidence: GitHub's public advisory database identifies [protobufjs 7.5.6](https://github.com/advisories/GHSA-75px-5xx7-5xc7), [gRPC JS 1.12.7](https://github.com/advisories/GHSA-5375-pq7m-f5r2), [uuid 11.1.1](https://github.com/advisories/GHSA-w5hq-g745-h8pq), and [Jackson 2.18.8](https://github.com/advisories/GHSA-rmj7-2vxq-3g9f) as patched baselines. [FasterXML's 2.18.10 release](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.18.10) includes further fixes, so it is the chosen Jackson target. The critical `tar` advisory's patch is 7.5.19, but removing the unused root tool eliminates its entire path rather than forcing a new major `tar` version through `@mapbox/node-pre-gyp`.

## Verification and limits

Regenerate `package-lock.json` with npm 10, then verify the exact lockfile package versions and that `tar`, `minimatch`, and `brace-expansion` are absent. Run `npm ci` and the Node client compatibility check against the XLM server. Run Maven `verify`, the Python UI tests, and the existing remote structured-image check if needed to cover Jackson serialization compatibility. Run an authoritative fresh `npm audit` and check GitHub's Dependabot view after the owner publishes through a pull request; alert closure cannot be claimed from local manifest edits alone.

The Codex shell's npm registry connection failed certificate verification, so the owner regenerated the lockfile and ran `npm ci` and `npm audit` in Git Bash without disabling TLS. Maven verification succeeded after resolving dependencies with the Windows root trust store. Future commits should be signed and proposed through a pull request; the two already-published unsigned commits remain historical unless a separate, explicitly approved history rewrite is undertaken.
