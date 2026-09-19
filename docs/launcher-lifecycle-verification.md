# Launcher lifecycle correction

## Preserved incident evidence

Acceptance candidate before this correction: `9422df32a02f85f8f5f68a2c18e4ab94f4d3bfc9`. The owner's `codex-orphan-server-evidence.txt` was present before this task and remains an unmodified, unstaged owner artifact. No process was stopped while collecting the following evidence.

Live inspection on 2026-09-18 (America/New_York) confirmed:

| Process | PID | Parent | Creation time |
|---|---:|---:|---|
| Java API server | 86176 | 56704 | 2026-09-18 20:40:07.196713 -04:00 |
| Foreground PowerShell launcher | 56704 | 84200 | 2026-09-18 20:40:06.909952 -04:00 |
| Codex app server | 84200 | 64736 | 2026-09-17 21:36:31.793807 -04:00 |

Java command line:

```text
"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe" -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE -jar C:\Users\micha\projects\xlm-eco-api-proj\xlm-eco-api\target\xlm-eco-api-1.0-SNAPSHOT.jar
```

PowerShell command line:

```text
"C:\Users\micha\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe" -NoProfile -Command "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
.\scripts\local-server.ps1 -Action Run"
```

`jcmd 86176 VM.system_properties` was filtered to `user.dir`, which confirmed `C:\Users\micha\projects\xlm-eco-api-proj\xlm-eco-api`. Java owned loopback listeners 50052 and 50053. The JAR was 175,701,703 bytes, last written 2026-09-19T00:39:13.0752394Z. A non-mutating exclusive-read probe failed with Windows sharing-violation HResult -2147024864. The owner separately reported Maven's failure to delete that JAR.

The original Codex tool call is recorded at 2026-09-19T00:40:03.212Z, call ID `call_kXTyeyWAbfSTwBJs54KPkizw`, returning execution session **87099**. It ran `scripts/local-server.ps1 -Action Run` from this checkout to prepare three authorized OpenAI checks after the clean build. Initial output was:

```text
XLM startup prerequisites found. Server readiness and provider credentials have not been checked.
Vector DBs disabled.
XLM ready; inference port=50052, admin port=50053
```

Session 87099 and the PowerShell parent were still alive during this investigation. The launcher had no PID/state file, Stop/Status action, or durable launcher log; stdout/stderr belonged to the execution session. External `registry.lock` was a zero-byte file last written 2026-09-17T13:11:16.1168697Z. It represents registry exclusivity, not process ownership; release of its OS lock does not require deleting the file.

## Root cause and scope

The primary defect was **Codex verification orchestration**: a persistent foreground developer launch was used without a cleanup bracket and without an explicit ownership transfer. Completion of the Codex response did not end the still-running tool session. This was not a detached Java child whose parent had already exited; PowerShell was still waiting for Java.

The existing `Run` behavior matched its documented contract. Its `finally` restores environment variables after Java exits; it does not treat the end of a separate agent task as a shutdown event. The only executable repository callers were synthetic launcher tests using an immediately exiting fake `java.cmd` or an invalid executable. Maven and the real-provider Python/browser harnesses did not invoke the launcher. Manual Run commands appeared in README, the launcher guide, run-and-test guide and Chat walkthrough.

No launcher PID file should have been created under the old contract. No registry/configuration file should be deleted as a cleanup substitute. The missing behavior was explicit ownership of a verification-started process and confirmed cleanup.

After this evidence was written, PID 86176 was revalidated by parent PID, full JAR command and exact creation time, then terminated through its retained process handle. Its waiting PowerShell parent 56704 exited naturally; execution session 87099 completed with exit 1. By 2026-09-19T03:20:20Z neither API listener remained, and the same JAR exclusive-read probe succeeded. No unrelated Java process was selected or killed.

The prior verification also left a separate Chat client: Python PID 68200, parent 73864, created 2026-09-18T20:42:53.908036-04:00, command `"C:\Python313\python.exe" gui/chat-ui/app.py 127.0.0.1 50052 5000 openai gpt-4o-mini`, execution session 61227. It still owned loopback port 5000 and was not a Java child. Its identity was captured separately before cleaning up that known task-owned verification client; a generic API launcher must not kill arbitrary Chat processes.

## Design and acceptance

Preserve `Check` and persistent developer `Run`. Add a distinct `Verify` bracket with deterministic native process-handle ownership, cleanup on success/failure, and protection against verifier-host termination. An unnamed, noninheritable Windows job is configured with kill-on-close and associated **atomically at process creation** using `STARTUPINFOEX` / `PROC_THREAD_ATTRIBUTE_JOB_LIST`. This avoids the owner-death gap between creating a suspended child and assigning it to a job. See [Microsoft's atomic creation explanation](https://devblogs.microsoft.com/oldnewthing/20230209-00/?p=107812) and [job lifetime contract](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects).

The callback receives native-handle-backed identity/status, not cleanup authority reconstructed from a PID file. It must bound readiness/checks and finish any subprocesses it owns before returning. Normal return/exit 0 is success; exceptions and nonzero native exit status fail. Cleanup confirms the owned process exits within a bound; if both verification and cleanup fail, both causes are retained. No persistent PID/state file is needed. Unsupported job association fails closed. No image API, provider, TLS, registry or Maven-clean behavior changes are intended.

Mentor Design Gate: **PASS WITH FINDINGS**, after replacing the initially proposed non-atomic create/assign sequence. Native handle authority, callback contract and dual-failure reporting recommendations are incorporated in the implementation.

## Impact assessment

- **Maven clean/repeated verification:** a live JVM can lock the target JAR, occupy the configured ports and hold the registry lock. New verification must finish owned-process cleanup before Maven clean begins.
- **Developer startup/shutdown:** persistent Run remains intentional; its operator must stop it before replacing its JAR. Ending an agent response or forcibly killing a parent process is not an ownership transfer or normal shutdown protocol.
- **Chat/Admin:** the surviving API can make a new UI connect to stale code; another API launch can fail on ports or registry exclusivity. API cleanup does not inherently own separately launched UI processes.
- **Automated tests/CI:** existing Maven tests did not create PID 86176. Any automation that explicitly starts the packaged API needs the verification bracket; ordinary fixture tests remain separate. Unsupported lifecycle ownership must fail closed, not fall back to an unowned launch.
- **Windows shutdown/restart:** a full OS shutdown terminates processes and releases OS handles; it does not make leaked work during a session acceptable. No automatic restart registration was created. Hard process termination is not a guarantee that Java shutdown hooks finish.

## Implementation and measured verification

`scripts/local-server.ps1` adds `-Action Verify -VerificationScript <absolute.ps1>` while preserving Check/Run. `scripts/OwnedServerProcess.cs` supplies the native job/process handles and Windows argument quoting. It is compiled by PowerShell `Add-Type`; no installed library or Maven dependency was added. The launcher restores configuration/secret environment variables in its outer `finally`. The callback descriptor exposes only identity and liveness; cleanup cannot accidentally target a reused PID.

Verification environment: Windows 11; PowerShell 7.6.5 and Windows PowerShell 5.1; Eclipse Adoptium Java 21.0.12 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe`; Maven 3.9.11 at `C:\Tools\apache-maven-3.9.11`. All checks below completed on 2026-09-18 local time / 2026-09-19 UTC.

Focused commands, from the repository root (PowerShell):

```powershell
.\scripts\tests\local-server.test.ps1
.\scripts\tests\local-server-lifecycle.test.ps1
powershell.exe -NoProfile -File scripts/tests/local-server.test.ps1
powershell.exe -NoProfile -File scripts/tests/local-server-lifecycle.test.ps1
```

The first two ran in PowerShell 7.6.5; the latter two explicitly ran in Windows PowerShell 5.1. Both existing launcher-suite invocations passed. The new lifecycle suite passed **8 named scenarios per engine, 16 scenario runs total**, with three verification cycles inside each repetition scenario:

| Scenario | Measured result on both engines |
|---|---|
| Successful callback | Owned JVM exits; environment restored; JAR deletable; unrelated JVM survives |
| Callback throws after startup | Original failure retained; same cleanup guarantees |
| Native callback exit 17 | Nonzero status becomes verification failure; same cleanup guarantees |
| Explicit callback exit 0 | Success only after confirmed cleanup |
| Three repeated cycles | Each JAR deletable; no stale launcher PID/state files |
| Persistent developer Run | Foreground owner remains active and JAR stays locked until the test's own outer job is stopped |
| Abrupt verifier-host termination | Exact inner Java process exits; separately owned unrelated JVM survives |
| Missing callback | Rejected before fixture Java starts |

`scripts/tests/fixtures/LifecycleServer.java` retains its own fixture JAR in a real JVM. `verify-lifecycle.ps1` checks readiness against the callback's exact process identity. These tests exercise actual Windows process/file behavior; they do not call providers or read real secrets. PowerShell/native-helper code is not counted by JaCoCo, so the direct scenarios are its executable evidence, not a claimed C# coverage percentage.

### Real packaged-server bracket

Using the existing `~/.xlm/config` and `~/.xlm/secrets`, the corrected equivalent of the original launch path was:

```powershell
.\scripts\local-server.ps1 -Action Verify `
  -VerificationScript (Resolve-Path .\scripts\tests\verify-packaged-server.ps1).Path
```

This uses the same launcher, packaged JAR, Java arguments and external configuration as the original Run invocation, with explicit transient ownership. The committed callback requires the owned PID to listen on `127.0.0.1:50052` and `127.0.0.1:50053` within 30 seconds. This is a startup/lifecycle check, not an authenticated RPC or provider-capability test.

- **Success:** PID 61076, created 2026-09-19T03:26:36.3865730Z. Both owned listeners were observed; the launcher printed `Verification finished; owned XLM server exit confirmed.` and returned 0. A subsequent process check found no PID 61076 and an exclusive JAR read succeeded.
- **Deliberate failure:** a task-local callback invoked the same readiness check and then threw `Intentional packaged-server verification failure after readiness.` PID 35672, created 2026-09-19T03:27:05.5512809Z, reached both listeners; the failure remained visible and the launcher returned 1. A subsequent process check found no PID 35672 and an exclusive JAR read succeeded.
- **Isolation:** the real-JVM lifecycle suite kept a separately owned JVM alive through successful, failed and abrupt-owner verification cleanup. Cleanup never selected Java by name, port or a discovered PID.

No provider calls were made during this correction. The original PID 86176 was ultimately terminated only after the forensic evidence above was preserved. The separate known verification Chat client was also stopped; persistent operator-owned servers are not selected by the new bracket.

### Maven clean and coverage

After the real packaged success/failure checks, the required command ran without exclusions or retries. The previously established Windows trust-store selection preserved normal TLS validation:

```powershell
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
mvn clean verify
```

Result: **BUILD SUCCESS**, 57.417 seconds, finished 2026-09-19T03:28:20Z. The log records deletion of `target`, packaging and replacement of the shaded JAR. The rebuilt JAR is 175,701,703 bytes with last-write time 2026-09-19T03:28:20.2950086Z, superseding the previously locked artifact. Local build output was preserved outside `target` at `../.runtime/lifecycle-clean-verify.log`.

- Surefire: **81 reported, 80 executed, 1 optional TLS test skipped; 0 failures, 0 errors**, across 19 suites.
- JaCoCo lines: **1,656 / 1,786 = 92.72%**.
- JaCoCo branches: **903 / 992 = 91.03%**.
- Both 90% Java coverage gates passed; counts and denominators are unchanged from acceptance candidate `9422df3`.
- No production Java/Python, protobuf, provider, registry, TLS-validation or Maven-clean logic changed. The standalone Python coverage suite was not rerun for this launcher-only correction; no new Python coverage result is claimed.

`git diff --check` passed. The lifecycle tests are explicit PowerShell checks, not part of the 81 Maven-reported tests. Normal Run still requires its operator to stop it before rebuilding; the new bracket does not adopt an existing persistent server. Callback-owned client tools require their own cleanup. Unsupported Windows job association fails closed. Existing optional external TLS/network and image-browser transfer findings are outside this correction and are not reclassified by startup/lifecycle success.

Final read-only process/listener inspection found none of the known incident/verification PIDs (86176, 56704, 68200, 61076, 35672) present and no listeners on 5000, 50052 or 50053. Changed-document local links and the task allowlist secret-pattern scan passed; no generated artifacts or owner evidence are included in the commit scope.

Operational verification of this Windows launcher lifecycle: **PASSED**. Mentor Quality Gate: **PASS**, with no blocking or outstanding recommended findings. The Mentor reviewed the actual implementation and final documentation, independently inspected Maven/coverage reports and checked the diff; dual-engine focused-test execution was implementer-reported. Architect Acceptance: **PENDING HUMAN ARCHITECT REVIEW**.
