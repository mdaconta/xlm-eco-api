# Local XLM server launcher on Windows

`scripts/local-server.ps1` provides a read-only prerequisite check and an explicit foreground server launch. It uses native Windows PowerShell (5.1 or 7). Run commands from the repository root.

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

## First-time provisioning

The human operator owns external configuration and secrets. Follow [XLM administration](dynamic-administration.md) to create and protect the directories, copy/adapt the non-secret [server settings template](server.properties.example), and independently provision `admin.token` plus the selected providers' key files. Do not paste credentials into prompts or put them in this checkout. The launcher does not create directories, generate credentials, overwrite configuration, or reset an existing registry.

Build and verify the packaged server using the repository Maven workflow (`mvn verify`), then run `Check`. If a prerequisite is missing, provision that specific prerequisite and repeat `Check`. On first successful server startup, XLM creates its registry through the existing registry bootstrap; later starts preserve that administrator-owned catalog. See [image-generation verification](image-generation-verification.md) for the distinction between local prerequisites, remote-provider success, and Architect Acceptance.

## Launcher regression checks

```powershell
.\scripts\tests\local-server.test.ps1
```

These tests use isolated synthetic directories and fake Java commands. They cover default Check, explicit Run, environment/default/override resolution, paths with spaces, nonzero native exit status, invocation failure, exact environment restoration, missing prerequisites, Git ancestor rejection, and filesystem-link rejection. They do not read real credentials or launch a real XLM server.
