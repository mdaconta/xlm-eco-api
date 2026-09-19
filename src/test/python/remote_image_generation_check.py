"""Opt-in real-provider verification through XLM; never calls providers directly."""
import argparse
import json
from pathlib import Path
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'python_client/generated'), str(ROOT / 'python_client')]
import grpc
from admin_transport import channel, AdminClient
import xlm_eco_api_pb2 as pb
import xlm_eco_api_pb2_grpc as rpc


def run():
    parser = argparse.ArgumentParser()
    parser.add_argument('--endpoint', default='127.0.0.1:50052')
    parser.add_argument('--ca-file')
    parser.add_argument('--admin-endpoint', default='127.0.0.1:50053')
    parser.add_argument('--admin-ca-file')
    parser.add_argument('--admin-token-file')
    parser.add_argument('--provision-models', action='store_true', help='Explicitly add only missing generation models through Admin')
    parser.add_argument('--output', default='target/remote-image-generation')
    parser.add_argument('--model', action='append', help='Explicit provider/model pair; repeat for a bounded matrix')
    options = parser.parse_args()
    models = [tuple(value.split('/', 1)) for value in options.model] if options.model else [('openai', 'gpt-image-1'), ('google', 'gemini-2.5-flash-image')]
    import re
    if any(len(pair) != 2 or pair[0] not in ('openai', 'google') or not re.fullmatch(r'[A-Za-z0-9._-]{1,128}', pair[1]) for pair in models):
        parser.error('Use an explicit openai/model or google/model identifier')
    if options.provision_models:
        with channel(options.admin_endpoint, options.admin_ca_file) as connection:
            admin = AdminClient(rpc.XlmAdminServiceStub(connection), options.admin_token_file)
            for provider, model in models:
                catalog = admin.call('listModels', pb.AdminListModelsRequest(provider=provider))
                if not any(m.model == model for m in catalog.models):
                    admin.call('upsertModel', pb.AdminUpsertModelRequest(expected_revision=catalog.revision,
                        model=pb.AdminModel(provider=provider, model=model, display_name=model,
                            enabled=True, capabilities=['image_generation'])))
    destination = Path(options.output)
    destination.mkdir(parents=True, exist_ok=True)
    evidence = []
    with channel(options.endpoint, options.ca_file) as connection:
        stub = rpc.XlmEcosystemServiceStub(connection)
        client_id = 'image-check-' + str(uuid.uuid4())
        if not stub.registerClient(pb.ClientRegistrationRequest(client_id=client_id), timeout=5).success:
            raise RuntimeError('Registration failed')
        try:
            catalog = stub.listModels(pb.ModelCatalogRequest(client_id=client_id, capability='image_generation'), timeout=5)
            for provider, model in models:
                entry = {'provider': provider, 'requested_model': model, 'capability': 'image_generation',
                         'catalog_revision': catalog.revision, 'verification': 'FAILED'}
                start = time.monotonic()
                try:
                    result = stub.generateImage(pb.ImageGenerationRequest(client_id=client_id, provider=provider,
                        model=model, prompt='Draw one blue circle on a plain white background. No text.'), timeout=160)
                    entry.update(actual_model=result.model, request_id=result.request_id,
                        success=result.success, error_code=pb.StructuredImageErrorCode.Name(result.error.code),
                        provider_http_status=result.error.provider_http_status,
                        mime_type=result.image.mime_type, image_bytes=len(result.image.data))
                    if result.success and result.output_type == pb.GENERATED_IMAGE and result.image.data:
                        suffix = {'image/png': '.png', 'image/jpeg': '.jpg'}[result.image.mime_type]
                        (destination / (provider + '-' + model + suffix)).write_bytes(result.image.data)
                        entry['verification'] = 'PASSED'
                except grpc.RpcError as error:
                    entry['transport_code'] = error.code().name
                entry['latency_ms'] = round((time.monotonic() - start) * 1000)
                evidence.append(entry)
                print(json.dumps(entry), flush=True)
        finally:
            stub.unregisterClient(pb.ClientUnregistrationRequest(client_id=client_id), timeout=5)
    (destination / 'evidence.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    return 0 if all(row['verification'] == 'PASSED' for row in evidence) else 1


if __name__ == '__main__':
    try:
        sys.exit(run())
    except Exception:
        print('Verification setup failed; inspect endpoint, TLS, external token and catalog permissions. No secret details displayed.', file=sys.stderr)
        sys.exit(2)
