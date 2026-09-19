# Welcome to the Cross-LM (XLM) Ecosystem API Project!

XLM provides provider-neutral gRPC operations for text, structured image analysis, image generation, and embeddings, with Java, Python, and Node clients. A persistent provider/model catalog controls capability selection; an authenticated administration API and local Console manage it. XLM Chat supports image attachments for analysis and explicit text-to-image generation. Implemented adapters and configured models are separate from remote operational verification; see the [current verification record](docs/image-generation-verification.md).

## Table of Contents
- [Introduction](#introduction)
- [Architecture](#architecture)
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Model capabilities](#model-capabilities)
- [Structured image analysis](#structured-image-analysis-increment-3a)
- [Image generation](#image-generation)
- [XLM Chat](#xlm-chat)
- [Verification and coverage](#verification-and-coverage)
- [Conclusion](#conclusion)

## Introduction

Here is the LLM Ecosystem diagram from my article entitled [What is the LLM Ecosystem?](https://www.daconta.us/Articles/The-LLM-Ecosystem.html).
![The LLM Ecosystem](https://www.daconta.us/images/LLM-Ecosystem-Components.jpg)

## Architecture

Java, Python, and Node clients call the Java server through provider-neutral protobuf/gRPC contracts. XLM validates registration, model selection and capabilities before invoking a provider adapter. Adapters translate provider requests and normalize responses. The authenticated Admin service manages the persistent provider/model catalog; inference discovery reads that catalog without exposing secrets. The local Flask Chat and Admin Console use these same service boundaries. See the [current capability design](docs/image-generation-design.md) and [administration architecture](docs/dynamic-administration-design.md).

## Features

This project is under active development and will change considerably over the next 
several months so make sure you check back to get the latest. 

Implemented: synchronous/streaming text, one-image structured JSON, explicit image generation, model capability discovery, persistent administration, and the compact Chat attachment composer. Here is the broader Feature Roadmap:
1. [x] Chat API
    1. [x] Synchronous Completion
    2. [x] Asynchronous/Streaming Completion
    3. [ ] LM Metadata
         1. Provider Name
         2. Model name
         3. Type (LLM, SLM, etc.)
         4. Max Prompt Size
         5. Knowledge Cutoff Date
         6. Configured capabilities are implemented; richer model profiles remain future work.
         7. Max Completion Token Limit
2. [ ] Conversation API
    1. [ ] Shared chats
        1. Chat History
        2. Chat IDs
        3. Chat encryption/Security
        4. Chat labels
        5. Chat Owner
        6. Group Chats?
    2. [ ] System Message
    3. [ ] Completion Format
        1. Code
        2. Markdown
        3. HTML
        4. Formulas
        5. Images in a shared, persisted conversation (future). Standalone `generateImage` and inline Chat display are implemented separately.
        6. Tables
        7. JSON
3. [ ] LM Customization API
    1. [ ] Token Probabilities API
    2. [ ] LM Parameters API
       1. [ ] Temperature - Controls the randomness or creativity of the model’s output.
       2. [ ] Max tokens - Specifies the maximum number of tokens the model can return in its output.
       3. [ ] Frequency Penalty - Penalizes the model for using words that have already appeared frequently in the text.
       4. [ ] Presence Penalty - Encourages the model to avoid repeating the same tokens or phrases that have already been used in the conversation, promoting more diverse responses.
    3. [ ] Omni-Model API
4. [ ] Personas API
    1. Persona metadata
       1. System text
       2. Name
       3. Description
       4. Type
       5. Owner
5. [ ] Embeddings API
    1. Embedding model
    2. Embedding metadata 
        1. Vector size
        2. Embedding type
6. [ ] RAG API
    1. Index metadata
        1. Embedding model 
        2. Index labels
        3. Index Owner
        4. Index Sharing
        5. Index Security
        6. Document metadata 
        7. Images
        8. OCR
        9. Tables
        10. Code 
    2. Chunk metadata
    3. Vector DB API
    4. Citations
7. [ ] Prompt Memory API
8. [ ] Prompt Classification API
9. [ ] Guardrails API
    1. Prompt Guardrails
    2. Completion Guardrails 
10. [ ] Custom Functions API
11. [ ] Web Search API
12. [ ] Assistants API
    1. Code Interpretation/Execution
13. [ ] Code Generation API
14. [ ] Validation API
15. [ ] Agent Framework API

## Installation

To build this software you will first have to insure you have the following pre-requisites:
1. Latest Version of Java. You can download it [here](https://www.oracle.com/java/technologies/downloads/).
2. Latest Version of Python. You can download it [here](https://www.python.org/downloads/). Install the Python gRPC build tooling with:
   ```bash
   python -m pip install -r python_client/requirements.txt
   ```
3. Latest Version of NodeJS. You can download it [here](https://nodejs.org/en/download/package-manager).
4. gRPC. You can download it [here](https://github.com/grpc/grpc/releases).
5. Maven. The project has a pom file. The two key POM lifecycle commands are Compile and Package.
   The package command creates a runnable Jar file that you can use to run both the client and the server.
6. Get Accounts and API keys with all the major LLM/SLM providers.
7. Provision external non-secret configuration and secret directories following [XLM administration](docs/dynamic-administration.md). The server requires `XLM_CONFIG_DIR` and `XLM_SECRETS_DIR`; it no longer loads credentials from classpath resources. Never put keys or Admin tokens in source control or prompts.

## Usage

Use the [complete run-and-test guide](docs/run-and-test.md) for dependency checks, the Windows server helper, Chat/Admin startup, all language clients, local coverage/browser tests, and explicitly opt-in remote verification.

To run the gRPC server you type:
```bash
java -jar ./target/xlm-eco-api-1.0-SNAPSHOT.jar
```
Set the external directory environment variables before launching. Loopback listeners default to inference port 50052 and authenticated Admin port 50053. All non-loopback listeners require TLS. See the [configuration and migration guide](docs/dynamic-administration.md).

On Windows, the [local server helper](docs/local-server.md) reuses the existing `~/.xlm/config` and `~/.xlm/secrets` directories (or explicit environment/path overrides), checks prerequisites without reading keys, and starts Java with the Windows trusted-root store. From Git Bash:

```bash
powershell.exe -NoProfile -File scripts/local-server.ps1 -Action Check
powershell.exe -NoProfile -File scripts/local-server.ps1 -Action Run
```

It does not recreate credentials, reset the catalog, or stop unrelated processes. First-time provisioning remains operator-owned and is described in the helper guide.

`Run` stays alive until its operator stops it. Automation that starts a server for verification must use the [owned Verify bracket](docs/local-server.md#verification-ownership), which confirms cleanup before returning. Stop persistent servers before `mvn clean verify` so Windows can release the packaged JAR.

To run the java test gRPC client you type:
```bash
java -cp ./target/xlm-eco-api-1.0-SNAPSHOT.jar us.daconta.xlmeco.GrpcXlmClient 127.0.0.1 50052 openai "gpt-4o-mini" "Who is FDR?"
```

The python grpc stubs are created via maven and stored in the python_client/generated directory.
To run the python client, there is a simple bash script to setup the path. 
```bash
./python_client/run_client.sh --host 127.0.0.1 --port 50052 --provider openai --model_name gpt-4o-mini --prompt "Tell me about space exploration."
```

The node grpc stubs will be created dynamically during runtime.

```
node ./node_client/app.js --host 127.0.0.1 --port 50052 --provider openai --model_name gpt-4o-mini --prompt "Would you say I have a plethora of pinatas?"
```

Note: there will be a client created for every language supported by gRPC (Python, C#, C, Go, Rust, etc.)

## Structured image analysis (Increment 3A)

`generateStructuredImage` is an additive unary gRPC method. Register a client, then send a `StructuredImageRequest` with nonempty instructions, one `ImageInput`, explicit `provider` and `model`, and a JSON Schema object serialized in `json_schema`. Inline image bytes are limited to 3 MiB and must match `image/png`, `image/jpeg`, or `image/webp`; instructions and schema are limited to 64 KiB each. The authoritative mutable registry controls enabled models and capabilities. OpenAI, Google and Anthropic structured-image adapters are implemented; remote acceptance is tracked separately. Explicit IDs override the configured `structured_image` default; omitting both uses that default. No fallback is performed. See [Admin Console, API and harness usage](docs/dynamic-administration.md) and [verification status](docs/dynamic-administration-verification.md).

On success, `StructuredImageResponse` contains a JSON-object `json_payload`, provider/model identity, `success=true`, and `STRUCTURED_IMAGE_COMPLETED`. On failure it contains `STRUCTURED_IMAGE_FAILED` and a normalized error code, safe message, retryability flag and provider HTTP status where available. Provider quota/spend failures are nonretryable; transient rate limits are retryable. The adapter relies on the provider's strict JSON Schema mode for schema conformance and checks that returned content is a JSON object. Existing `syncChat` and `asyncChat` contracts remain available.

`mvn verify` regenerates Java and Python protobuf stubs and runs the local network tests. Node loads the updated `.proto` at runtime. To run the opt-in real remote check, start the packaged server with a configured OpenAI key, then run `python src/test/python/remote_structured_image_check.py`. The script generates a small generic PNG in memory; it does not print credentials. On Windows installations where Java's bundled trust store lacks the trusted root used for the provider connection, Java can use the Windows trusted root store with `-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`; certificate verification stays enabled. Remote success also requires available provider quota.

The design, gap analysis and verification record are in `docs/increment-3a-*.md`.

The local [XLM Chat verification walkthrough](docs/chat-ui-user-verification.md) covers image input, generation, unsupported capabilities and text regression. Image input returns structured JSON; the separate image-generation operation returns pixels.

## Model capabilities

Configured models advertise independent capabilities using the repository's existing names:

| XLM capability | Meaning | Explicit operation |
|---|---|---|
| `chat` | Text generation (`TEXT_GENERATION`) | `syncChat` / `asyncChat` |
| `structured_image` | One image input with structured JSON output (`IMAGE_INPUT`) | `generateStructuredImage` |
| `image_generation` | Text-to-image generation (`IMAGE_GENERATION`) | `generateImage` |
| `embedding` | Vector embedding | `getEmbedding` |

Register a client, then call inference `listModels(ModelCatalogRequest(client_id=..., capability="image_generation"))` to discover configured models. Each entry carries provider, model, display name, effective enabled state, and capability list. This read-only RPC needs no Admin token. It does not promise credentials, quota or provider availability. Administrators use authenticated Admin `listModels`/`upsertModel` to edit the catalog.

Vision support does not imply generation. The new seed includes `openai/gpt-image-1` and `google/gemini-2.5-flash-image` for generation only; neither is advertised for XLM structured JSON image analysis. Existing registry files are not reseeded: add the desired model and capability through Admin. No image-generation default is selected automatically. Only OpenAI and Google implement generation in this increment; other adapters reject that capability. Unsupported selected models return `INCOMPATIBLE_CAPABILITY`; disabled/unknown models and provider failures have distinct normalized errors. XLM never silently changes the selected model.

## Image generation

`generateImage` is an explicit unary text-to-image operation. `ImageGenerationRequest` carries `client_id`, `provider`, `model`, and a nonblank `prompt` up to 64 KiB UTF-8. Explicit provider/model wins; omit both to use an administrator-configured `image_generation` default. Provider-only selection requires a default for that same provider; model-only selection is invalid. There is no English-keyword routing in the server.

Success returns `output_type=GENERATED_IMAGE`, one `image` with `mime_type` and inline `data`, provider/model provenance, and a server `request_id`. Images are validated PNG/JPEG, at most 3 MiB, 4096 pixels per side and 16 megapixels. The request ID identifies execution, not retry deduplication. Failures carry the shared `StructuredImageError` type and no image. The OpenAI adapter requests one 1024×1024 low-quality PNG; Google returns one inline image. No provider URLs or provider-specific options are exposed. Oversized, corrupt, missing or multiple image outputs fail safely.

Example using the generated Python client after `mvn compile` and model provisioning:

```python
import sys
import uuid
from pathlib import Path
sys.path.insert(0, "python_client/generated")
sys.path.insert(0, "python_client")
from admin_transport import channel
import xlm_eco_api_pb2 as pb
import xlm_eco_api_pb2_grpc as rpc

# Loopback example; pass a trusted CA file to channel() for a remote TLS endpoint.
with channel("127.0.0.1:50052") as connection:
    client = rpc.XlmEcosystemServiceStub(connection)
    client_id = str(uuid.uuid4())
    assert client.registerClient(pb.ClientRegistrationRequest(client_id=client_id), timeout=5).success
    try:
        result = client.generateImage(pb.ImageGenerationRequest(
            client_id=client_id, provider="openai", model="gpt-image-1",
            prompt="Draw a blue circle on a white background."), timeout=160)
        if not result.success:
            raise RuntimeError(pb.StructuredImageErrorCode.Name(result.error.code))
        assert result.output_type == pb.GENERATED_IMAGE
        suffix = {"image/png": ".png", "image/jpeg": ".jpg"}[result.image.mime_type]
        Path("generated-image" + suffix).write_bytes(result.image.data)
        print(result.provider, result.model, result.image.mime_type, result.request_id)
    finally:
        client.unregisterClient(pb.ClientUnregistrationRequest(client_id=client_id), timeout=5)
```

## XLM Chat

Start the existing local Flask Chat client as described in the [walkthrough](docs/chat-ui-user-verification.md). Select a configured provider/model and enable **Generate image** to create an image. The composer blocks unsupported capability selections without switching models. An imperative prompt such as “Draw a diagram” offers a suggestion; accepting it is optional. Informational prompts such as “Explain image compression” remain text. Prompt auditing is a UI convenience; API clients always choose an explicit RPC.

The composer uses `[ + ] [ prompt… ] [ Send ]`. Choose one PNG/JPEG/WebP with `+` to analyze it, inspect its thumbnail/filename, or remove it. Send the attachment with the shared prompt for structured recognition; expand the JSON Schema control when needed. Attachment analysis and image generation are separate operations; combining them is blocked in this increment. Generated images appear inline with their prompt, provider/model and execution identity. Failed requests remain associated with their transcript entry, and errors are sanitized.

## Verification and coverage

Use Java 17-compatible source tooling (verified here with Java 21), Maven and Python. Generate stubs before running Python tests. Install development coverage tooling, then run the independent language gates:

```bash
python -m pip install -r python_client/requirements.txt -r requirements-test.txt Flask==3.1.2 Flask-SocketIO==5.5.1
python -m coverage --version
mvn clean verify
python scripts/check_python_coverage.py
node gui/chat-ui/test_admin_ui.cjs
node gui/chat-ui/test_chat_ui.cjs
node src/test/node/client_compatibility.js
```

JaCoCo enforces aggregate handwritten Java **LINE >=90% and BRANCH >=90%**, writing `target/site/jacoco/index.html` and `target/site/jacoco/jacoco.xml`. Generated protobuf classes are excluded. Python uses the existing unittest suites with coverage.py branch measurement, covering `python_client` and `gui/chat-ui`, excluding generated stubs and tests. Its runner enforces **statement >=90% and branch >=90% independently**, and produces HTML, JSON and XML reports under `target`; see [coverage evidence and exact report paths](docs/image-generation-coverage.md). A configured threshold is not proof that the current run passes. Baseline, latest results, exceptions and uncovered-path review are recorded there.

Local HTTP fixtures and real loopback gRPC tests are separate from real provider tests. See the [verification matrix](docs/image-generation-verification.md) for exercised capabilities and pending remote/browser/coverage acceptance. No provider capability is marked remotely verified based solely on documentation or mocks.

## Conclusion

Feedback on the project is welcome. Visit the [author's website](https://www.daconta.us/) for contact information.
