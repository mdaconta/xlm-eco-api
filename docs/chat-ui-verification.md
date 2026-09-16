# XLM Chat image-analysis verification, 2026-09-16

## Local checks

- `mvn verify -q -o`: passed, including the Increment 3A Java tests and generated Python stubs.
- `python -m unittest discover -s gui/chat-ui -p 'test_*.py' -v`: four tests passed. These cover image request construction and MIME/schema/provider/model propagation, normalized and transport failures, invalid input/token rejection, and the existing text route.
- `git diff --check`: passed (Git reported only expected Windows line-ending conversion warnings).
- Browser inspection: XLM Chat rendered both Text chat and Analyze one image modes. Image mode showed file picker, instructions, schema editor, and configured provider/model label.

## Real remote check through the chat UI

Started the packaged XLM server on port 50052 and the Flask UI on loopback port 5000, configured for `openai` and `gpt-4o-mini`. A multipart POST through `/analyze_image` used the generic 96 x 96 PNG with red center square, `image/png`, a color question, and an object JSON Schema requiring a `color` string. The UI route constructed `generateStructuredImage`, and the remote provider returned `{"color":"red"}`. The normalized UI response reported `STRUCTURED_IMAGE_COMPLETED`, provider `openai`, actual model `gpt-4o-mini-2024-07-18`, and 2512 ms measured UI-to-XLM latency. No key was printed.

Changing the schema type to `madeup` produced `STRUCTURED_IMAGE_FAILED`, `PROVIDER_FAILURE`, provider HTTP 400, and `retryable=false` through the same UI route. In the browser, Text chat streamed `OK.` for `Reply with the word OK.` through the existing Socket.IO path. The subsequent optional title request returned `Untitled` after a gRPC `UNAVAILABLE` error on the first run; the text completion itself succeeded. A repeat in the same XLM server session streamed `OK.` and generated `Concise Acknowledgment Response`, so the title failure was transient in this run.

A browser mode-switch check submitted a longer text request, immediately selected Analyze one image, and waited for the remote text completion. The image result and title areas remained empty, confirming that late text events did not overwrite image-mode content. Switching back to Text chat and submitting the `OK.` request succeeded.

The browser page and modes were inspected directly. The remote image request was submitted to the live UI HTTP route by a local test client, rather than by operating the browser file picker. The [user walkthrough](chat-ui-user-verification.md) gives the remaining hands-on browser confirmation steps, including image preview and result rendering. The remote gRPC acceptance from Increment 3A is recorded separately in [the Increment 3A verification record](increment-3a-verification.md).

Architect Acceptance: `PENDING HUMAN ARCHITECT REVIEW`.
