# Local XLM server launcher on Windows

`scripts/local-server.ps1` provides a read-only prerequisite check, an explicit persistent foreground server launch, and an owned transient verification bracket. It uses native Windows PowerShell (5.1 or 7) on Windows 10/11; the project workstation is Windows 11. Run commands from the repository root.

```powershell
# Default action: check existing prerequisites without starting Java.
.\scripts\local-server.ps1

# Explicit foreground startup; Ctrl+C stops this foreground server.
.\scripts\local-server.ps1 -Action Run
```

Directory selection is `-ConfigDir` / `-SecretsDir`, then the corresponding `XLM_CONFIG_DIR` / `XLM_SECRETS_DIR` process environment variable, then `$env:USERPROFILE\.xlm\config` / `$env:USERPROFILE\.xlm\secrets`. The default JAR is `target\xlm-eco-api-1.0-SNAPSHOT.jar` beneath this checkout. Every supplied path must be absolute. Paths containing spaces work as individual arguments:

```powershell
.\scripts\local-server.ps1 -Action Check `
  -ConfigDir 'C:\XLM data\config' `
  -SecretsDir 'C:\XLM data\secrets' `
  -JarPath 'C:\XLM builds\xlm-eco-api-1.0-SNAPSHOT.jar'
```

`Check` resolves filesystem links, rejects configuration/secret directories inside any Git checkout (including worktree `.git` files), and checks for Java, the JAR, `server.properties`, and `admin.token`. It does not read secret contents or test credentials. Its success message is **startup prerequisites found**, not server readiness. The server remains responsible for validating configuration, credential format, listener ports, TLS material, and registry state.

`Run` performs the same checks, supplies the chosen directories only to the foreground process environment, and invokes Java with `-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`. Normal certificate and hostname validation remains enabled. It restores the previous environment, including previously absent variables, when Java exits or invocation fails, and preserves Java's exit status. Server startup output supplies the actual listener/readiness evidence. The launcher neither starts the Chat UI nor manages background processes; an existing server remains under its original operator's control.

## Verification ownership

Use `Verify` whenever automation starts an API only for verification. Do not start `Run` in one tool invocation and rely on task completion to stop it. Ending a Codex response or closing an unrelated terminal is not a stop operation. A deliberate persistent launch must have an identified operator and explicit ownership handoff.

```powershell
# Bounded, read-only startup check using the existing config/secrets and default ports.
.\scripts\local-server.ps1 -Action Verify `
  -VerificationScript (Resolve-Path .\scripts\tests\verify-packaged-server.ps1).Path
# Only after Verify finishes and confirms cleanup:
mvn clean verify
```

`Verify` requires an absolute `.ps1` callback path and accepts the same config/secrets/JAR overrides as Run. The callback receives `param($ServerProcess)`: read-only `Id`, `StartTimeUtc` and `HasExited` properties backed by the retained native process handle. It must implement bounded readiness and assertions, finish its own child tools before returning, and throw or return a nonzero native exit status on failure. Native status is reset to zero before invocation. A normal return or exit 0 is success only after server cleanup is confirmed. When both verification and cleanup fail, both failures are reported.

The packaged startup callback checks that **that exact PID** owns loopback ports 50052 and 50053 within 30 seconds; it makes no provider calls. For other ports or additional API checks, supply a purpose-specific callback. Do not use a callback to leave client tools running silently. Chat/Admin clients launched separately are not owned by the API launcher.

An unnamed, noninheritable Windows job associates the API atomically at creation. Its native handle owns the process tree; no PID file, process-name search or port-based kill is used. The bracket closes the job in `finally` and waits up to ten seconds for the retained API process to exit. Windows also closes the job handle if the verifier host dies. Unsupported job association fails before an unowned process can run. Verify has no detach/ownership-transfer switch; use intentional developer Run for persistence.

Verification cleanup terminates the disposable API; it is not a graceful application shutdown protocol and does not promise Java shutdown-hook completion. Complete outstanding checks before returning. The hidden verification process does not inherit terminal streams; use callback assertions for evidence. The normal Run console behavior is unchanged. No launcher PID/state file is created, so no stale launcher PID file remains between cycles. The registry lock file is separate and may remain on disk after its OS lock is released.

See the [incident evidence, lifecycle design and measured tests](launcher-lifecycle-verification.md). Stop persistent servers before Maven clean; never kill arbitrary Java processes or suppress Maven deletion failures.

## First-time provisioning

The human operator owns external configuration and secrets. Follow [XLM administration](dynamic-administration.md) to create and protect the directories, copy/adapt the non-secret [server settings template](server.properties.example), and independently provision `admin.token` plus the selected providers' key files. Do not paste credentials into prompts or put them in this checkout. The launcher does not create directories, generate credentials, overwrite configuration, or reset an existing registry.

Build and verify the packaged server using the repository Maven workflow (`mvn verify`), then run `Check`. If a prerequisite is missing, provision that specific prerequisite and repeat `Check`. On first successful server startup, XLM creates its registry through the existing registry bootstrap; later starts preserve that administrator-owned catalog. See [image-generation verification](image-generation-verification.md) for the distinction between local prerequisites, remote-provider success, and Architect Acceptance.

## Launcher regression checks

```powershell
.\scripts\tests\local-server.test.ps1
.\scripts\tests\local-server-lifecycle.test.ps1
```

The basic launcher tests use isolated synthetic directories and fake Java commands. They cover default Check, explicit Run, environment/default/override resolution, paths with spaces, nonzero native exit status, invocation failure, exact environment restoration, missing prerequisites, Git ancestor rejection, and filesystem-link rejection. They do not read real credentials or launch a real XLM server.

The separate lifecycle suite requires JDK `java`, `javac` and `jar` on PATH. It builds an isolated fixture JAR and uses real JVMs to check successful and failed callback cleanup, native exit status, environment restoration, repeated cycles, JAR deletion, unrelated-JVM survival, persistent Run, abrupt verifier termination, and missing-callback rejection. It does not use the real external configuration or provider credentials. Run both scripts under Windows PowerShell 5.1 and PowerShell 7 when changing the launcher or native ownership helper.
