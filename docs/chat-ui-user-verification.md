# Verify XLM Chat text, image input, and image generation

Run from the repository root. Start with the [complete run-and-test guide](run-and-test.md) for dependency installation, automated tests and Chat/Admin environment settings. The [Windows server helper](local-server.md) reuses existing external configuration/secrets; first-time provisioning follows [administration instructions](dynamic-administration.md). Never paste credentials into Chat or task messages. Use a disposable local instance for restart tests. Build/test status and coverage exceptions are tracked in [current verification](image-generation-verification.md), separately from this procedure. Real-provider submissions in this walkthrough require deliberate operator authorization and may incur charges.

## Start

In Git Bash, using the existing `~/.xlm/config` and `~/.xlm/secrets` directories or explicit launcher/environment overrides:

```bash
mvn clean verify
powershell.exe -NoProfile -File scripts/local-server.ps1 -Action Check
powershell.exe -NoProfile -File scripts/local-server.ps1 -Action Run
```

In a second terminal, with Python Flask/Flask-SocketIO and generated gRPC dependencies installed:

```bash
bash gui/chat-ui/run_ui.sh 127.0.0.1 50052 5000 openai gpt-4o-mini
```

Open `http://127.0.0.1:5000`. Adjust ports to your non-secret server configuration. Remote inference needs `XLM_INFERENCE_CA_FILE`; the UI remains loopback only. Admin editing additionally needs `XLM_ADMIN_ENDPOINT`, `XLM_ADMIN_TOKEN_FILE` and a trusted `XLM_ADMIN_CA_FILE` for remote Admin. Chat discovery itself does not need Admin credentials.

## Text and structured image input

1. Select a configured text/vision model such as `openai/gpt-4o-mini`. Leave Generate image unchecked. Send `Reply with the word OK.` and confirm text appears in that request's transcript entry.
2. Create the existing generic fixture: `python src/test/python/remote_structured_image_check.py --write-sample-image target/verification-red-square.png`.
3. Click `+`, choose that PNG, and inspect the compact filename/thumbnail. Remove it, then attach it again to verify cleanup and reselection.
4. Enter `What color is the square at the center?` in the shared prompt. Expand JSON Schema and use `{"type":"object","properties":{"color":{"type":"string"}},"required":["color"],"additionalProperties":false}`.
5. Send. Confirm a structured object naming red plus provider/model/latency. The request is image input (`structured_image`), not image generation. PNG/JPEG/WebP uploads remain limited to one image and 3 MiB.

## Explicit image generation and suggestions

1. Remove the attachment. Select a configured generation model: `openai/gpt-image-1`, `openai/gpt-image-2.5-flare`, or `google/gemini-2.5-flash-image`. The two listed OpenAI models are the operator-selected working choices; do not use `gpt-image-2` or `gpt-image-2-2026-04-21` in this verification. Existing registries need an explicit model addition in Admin; upgrading never overwrites the catalog.
2. Check Generate image and send `Draw a blue circle on a white background.` Confirm a decoded inline image, associated prompt, provider/model, request ID and latency. No raw base64 should be visible.
3. Select a text-capable model and clear Generate image. Enter `Draw a diagram of a tree`. Confirm a suggestion is offered; it must not switch mode/model automatically. Dismiss/ignore it and send as text. The explicit toggle remains authoritative.
4. Verify `Explain image compression`, `Describe this visualization`, and `What image formats does this API support?` do not force generation.
5. With a text-only/vision-only model, enable Generate image and confirm submission is unavailable with an explanation. A direct explicit API call must return `INCOMPATIBLE_CAPABILITY` independently of UI checks.
6. Attach an image while generation is selected. Confirm the unsupported combination is blocked; editing is outside this increment.
7. Exercise a provider rejection/quota/timeout only when deliberately authorized. Confirm a sanitized error stays attached to the correct transcript entry and that no automatic retry or model switch occurs.

## Restart and responsive checks

Use Admin to save a generation model capability and, optionally, an explicit generation default. Read back the saved revision. Stop/restart the same disposable server/configuration directory; confirm saved model/default metadata survives, the Console recovers registration, and a new explicitly submitted request succeeds. An interrupted request must not be replayed.

Check the composer at desktop width and around 390 pixels wide. The `+`, prompt, Send, attachment remove and generation toggle must remain usable without horizontal page overflow. Preserve previous transcript ordering while changing models/modes.

Record provider/requested model/returned model/capability/status/request ID/latency and visible outcome. Mark each scenario PASSED, FAILED, NOT RUN or BLOCKED. Provider documentation, local fake HTTP, browser fixtures and actual remote provider calls are distinct evidence categories.
