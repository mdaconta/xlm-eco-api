"""Opt-in real-provider gRPC check. Requires the XLM server and configured OpenAI key."""

import argparse
import json
import struct
import sys
import time
import uuid
import zlib
from pathlib import Path

import grpc

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "python_client" / "generated"))
import xlm_eco_api_pb2 as pb
import xlm_eco_api_pb2_grpc as rpc


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def generic_image():
    rows = bytearray()
    for y in range(96):
        rows.append(0)
        for x in range(96):
            rows.extend((220, 30, 30) if 32 <= x < 64 and 32 <= y < 64 else (30, 80, 220))
    return (b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 96, 96, 8, 2, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(rows))
            + png_chunk(b"IEND", b""))


SCHEMA = json.dumps({"type": "object", "properties": {"color": {"type": "string"}},
                     "required": ["color"], "additionalProperties": False})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=50052)
    parser.add_argument("--model", default="gpt-4o-mini")
    args = parser.parse_args()
    client_id = str(uuid.uuid4())
    with grpc.insecure_channel(f"{args.host}:{args.port}") as channel:
        stub = rpc.XlmEcosystemServiceStub(channel)
        registered = stub.registerClient(pb.ClientRegistrationRequest(
            client_id=client_id, client_name="remote-image-check"), timeout=10)
        if not registered.success:
            raise RuntimeError("Client registration failed")
        try:
            request = pb.StructuredImageRequest(
                client_id=client_id,
                instructions="What color is the square at the center of this image? Return the color name.",
                image=pb.ImageInput(mime_type="image/png", data=generic_image()),
                provider="openai", model=args.model, json_schema=SCHEMA)
            started = time.monotonic()
            result = stub.generateStructuredImage(request, timeout=90)
            latency_ms = round((time.monotonic() - started) * 1000)
            print(json.dumps({"contract": "StructuredImageRequest v1", "provider": result.provider,
                              "model": result.model, "mime_type": request.image.mime_type,
                              "success": result.success, "status": pb.StructuredImageStatus.Name(result.status),
                              "structured_result": json.loads(result.json_payload) if result.success else None,
                              "error_code": pb.StructuredImageErrorCode.Name(result.error.code),
                              "error_message": result.error.message, "latency_ms": latency_ms}))
            if not result.success or json.loads(result.json_payload).get("color", "").lower() != "red":
                raise RuntimeError("Remote image result failed acceptance")

            unsupported = stub.generateStructuredImage(pb.StructuredImageRequest(
                client_id=client_id, instructions=request.instructions, image=request.image,
                provider="openai", model="not-a-vision-model", json_schema=SCHEMA), timeout=10)
            print(json.dumps({"unsupported_model_code": pb.StructuredImageErrorCode.Name(unsupported.error.code),
                              "unsupported_model_success": unsupported.success}))

            bad_schema = json.dumps({"type": "object", "properties": {"color": {"type": "madeup"}},
                                     "required": ["color"], "additionalProperties": False})
            provider_failure = stub.generateStructuredImage(pb.StructuredImageRequest(
                client_id=client_id, instructions=request.instructions, image=request.image,
                provider="openai", model=args.model, json_schema=bad_schema), timeout=90)
            print(json.dumps({"provider_failure_code": pb.StructuredImageErrorCode.Name(provider_failure.error.code),
                              "provider_http_status": provider_failure.error.provider_http_status,
                              "retryable": provider_failure.error.retryable}))
            if (provider_failure.success or provider_failure.status != pb.STRUCTURED_IMAGE_FAILED \
                    or provider_failure.error.code != pb.PROVIDER_FAILURE \
                    or provider_failure.error.provider_http_status != 400):
                raise RuntimeError("Malformed-schema provider failure was not normalized as expected")

            stub.setPreferredProviders(pb.ProviderSelectionRequest(client_id=client_id,
                provider_capabilities={"openai": pb.ProviderCapabilitiesRequest(capabilities=["chat"])}), timeout=10)
            text = stub.syncChat(pb.ChatRequest(client_id=client_id, prompt="Reply with the word OK.",
                                              provider="openai", model_name=args.model), timeout=90)
            legacy_text_success = (text.completion.strip().upper().strip(".! ") == "OK"
                                   and not text.completion.startswith("Error:"))
            print(json.dumps({"legacy_text_success": legacy_text_success,
                              "legacy_text_completion": text.completion}))
            if not legacy_text_success:
                raise RuntimeError("Legacy text request did not return the expected remote completion")
        finally:
            stub.unregisterClient(pb.ClientUnregistrationRequest(client_id=client_id), timeout=10)


if __name__ == "__main__":
    main()
