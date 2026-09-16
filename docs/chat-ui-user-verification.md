# Verify structured image analysis in XLM Chat

This walkthrough exercises the new browser UI, the XLM gRPC server, the configured OpenAI vision adapter, and the existing text chat path. It analyzes an image; it does not generate an image. Use two Git Bash terminals on the Windows development host. The configured OpenAI key stays in ignored server configuration; do not paste it into the browser or terminal command.

## 1. Build and create a known image

In terminal 1:

```bash
cd /c/Users/micha/projects/xlm-eco-api-proj/xlm-eco-api
mvn verify
python src/test/python/remote_structured_image_check.py --write-sample-image target/verification-red-square.png
```

Check that Maven reports `BUILD SUCCESS` and Python reports the image path. The sample is a blue 96 x 96 PNG with a red square at the center; it is ignored under `target/`.

## 2. Start XLM

In the same terminal:

```bash
java -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE -jar target/xlm-eco-api-1.0-SNAPSHOT.jar
```

Wait for `XLM Server started ... listening on port 50052`. These Java options use the Windows trusted root store on this host; they keep certificate verification enabled. If your configured server port differs, use that port in the next command.

## 3. Start XLM Chat

In terminal 2:

```bash
cd /c/Users/micha/projects/xlm-eco-api-proj/xlm-eco-api
bash gui/chat-ui/run_ui.sh 127.0.0.1 50052 5000 openai gpt-4o-mini
```

Wait for the Flask UI to report `http://127.0.0.1:5000`, then open that address in a browser on the same host. The page should label provider `openai` and model `gpt-4o-mini`. The UI listens on loopback only.

## 4. Verify image analysis

1. Select **Analyze one image**. The image picker, visual instructions and JSON Schema editor should appear.
2. Choose `target/verification-red-square.png`. Confirm that its thumbnail, `image/png` MIME type and byte count appear before submission.
3. Set visual instructions to `What color is the square at the center of this image? Return the color name.`
4. Replace the schema with:

   ```json
   {"type":"object","properties":{"color":{"type":"string"}},"required":["color"],"additionalProperties":false}
   ```

5. Click **Analyze image**. Expect `{"color":"red"}` in the result box. Confirm `STRUCTURED_IMAGE_COMPLETED`, `openai`, the actual model returned by the provider, and elapsed milliseconds in the metadata line.

For a provider-error check, change the schema's `"string"` to `"madeup"` and submit again. Expect `PROVIDER_FAILURE`, provider HTTP `400`, and `Retryable: false`. Restore the valid schema afterward. Do not use this deliberately invalid schema for a production request.

## 5. Verify existing text chat

Select **Text chat**, enter `Reply with the word OK.`, and click **Send**. Expect an `OK` response to stream into the text box. This exercises the pre-existing `asyncChat` path through the same UI. The title may arrive afterward via the existing `syncChat` call.

Stop the UI and XLM server with Ctrl+C in their terminals after the checks. The [Increment 3A verification record](increment-3a-verification.md) contains the earlier automated and real remote gRPC evidence; the browser steps above add user-visible UI verification.
