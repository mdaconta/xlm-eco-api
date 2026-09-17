"""Opt-in XLM matrix verification; no direct provider requests or fixed provider/model names."""
import argparse
import json
import sys
import time
import uuid
from collections import Counter
from google.protobuf.json_format import MessageToDict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'python_client'))
sys.path.insert(0, str(ROOT / 'python_client' / 'generated'))
import grpc
import xlm_eco_api_pb2 as pb
import xlm_eco_api_pb2_grpc as rpc
from admin_transport import AdminClient, channel
from remote_structured_image_check import generic_image, SCHEMA

INSTRUCTIONS = 'What color is the square at the center of this image? Return the color name.'


def discover(admin, requested=None):
    providers = admin.call('listProviders', pb.AdminListProvidersRequest(capability='structured_image'))
    pairs, pending = [], []
    for provider in providers.providers:
        models = admin.call('listModels', pb.AdminListModelsRequest(provider=provider.provider, capability='structured_image'))
        for model in models.models:
            pair = (provider.provider, model.model)
            if requested is not None and pair not in requested:
                continue
            if not provider.enabled or not model.enabled or not provider.credential_present:
                pending.append({'provider': pair[0], 'requested_model': pair[1], 'result': 'PENDING',
                                'reason': 'disabled or credential unavailable'})
            else:
                pairs.append(pair)
    found = set(pairs) | {(x['provider'], x['requested_model']) for x in pending}
    for provider, model in sorted((requested or set()) - found):
        pending.append({'provider': provider, 'requested_model': model, 'result': 'PENDING',
                        'reason': 'not configured for structured_image'})
    return pairs, pending


def invoke(stub, client_id, provider, model):
    request = pb.StructuredImageRequest(client_id=client_id, provider=provider, model=model,
        instructions=INSTRUCTIONS, image=pb.ImageInput(mime_type='image/png', data=generic_image()), json_schema=SCHEMA)
    started = time.monotonic()
    evidence = {'provider': provider, 'requested_model': model}
    try:
        result = stub.generateStructuredImage(request, timeout=90)
        # Do not persist raw payloads/messages; only bounded schema-derived outcomes and identity.
        payload = None
        try:
            payload = json.loads(result.json_payload) if result.success else None
        except ValueError:
            pass
        conforms = isinstance(payload, dict) and set(payload) == {'color'} and isinstance(payload['color'], str)
        image_pass = conforms and payload['color'].strip().lower() == 'red'
        completed = result.success and result.status == pb.STRUCTURED_IMAGE_COMPLETED
        identity = (not provider or result.provider == provider) and bool(result.model)
        evidence.update(actual_provider=result.provider, actual_model=result.model,
            image_processing='PASS' if image_pass else 'FAIL',
            structured_output='PASS' if conforms else 'FAIL',
            status=pb.StructuredImageStatus.Name(result.status),
            error_code=pb.StructuredImageErrorCode.Name(result.error.code),
            provider_http_status=result.error.provider_http_status,
            result='PASS' if completed and identity and image_pass else 'FAIL')
    except grpc.RpcError as error:
        evidence.update(result='FAIL', status='TRANSPORT_ERROR', error_code=error.code().name,
                        image_processing='FAIL', structured_output='FAIL')
    evidence['latency_ms'] = round((time.monotonic() - started) * 1000)
    return evidence


def switching(admin, stub, client_id, pairs):
    """Same client/channel/process: A1, A2, B1, C1, changed default, explicit override."""
    by_provider = {}
    for pair in pairs:
        by_provider.setdefault(pair[0], []).append(pair)
    if len(by_provider) < 3 or not any(len(v) >= 2 for v in by_provider.values()):
        return {'result': 'PENDING', 'reason': 'requires three enabled credentialed providers and two models on the first'}
    first = next(k for k, v in by_provider.items() if len(v) >= 2)
    others = [k for k in by_provider if k != first]
    sequence = by_provider[first][:2] + [by_provider[others[0]][0], by_provider[others[1]][0]]
    evidence = [invoke(stub, client_id, *pair) for pair in sequence]
    before = admin.call('getDefaults', pb.EmptyRequest())
    old = next((d for d in before.defaults if d.capability == 'structured_image'), None)
    chosen = sequence[-1]
    changed = admin.call('setDefault', pb.AdminSetDefaultRequest(expected_revision=before.revision,
        selection=pb.AdminDefault(capability='structured_image', provider=chosen[0], model=chosen[1])))
    try:
        default_result = invoke(stub, client_id, '', '')
        if (default_result.get('actual_provider') != chosen[0]
                or default_result.get('actual_model') != evidence[-1].get('actual_model')):
            default_result['result'] = 'FAIL'
        evidence.append(default_result)
        evidence.append(invoke(stub, client_id, *sequence[0]))
    finally:
        # Exact revision guards avoid overwriting another administrator's concurrent change.
        admin.call('setDefault', pb.AdminSetDefaultRequest(expected_revision=changed.revision,
            selection=old or pb.AdminDefault(capability='structured_image'), clear=old is None))
    return {'result': 'PASS' if all(e['result'] == 'PASS' for e in evidence) else 'FAIL', 'requests': evidence}



def snapshot(admin):
    """One revision-consistent, non-secret administrative snapshot for restart comparison."""
    providers = admin.call('listProviders', pb.AdminListProvidersRequest())
    models = admin.call('listModels', pb.AdminListModelsRequest())
    defaults = admin.call('getDefaults', pb.EmptyRequest())
    status = admin.call('getStatus', pb.EmptyRequest())
    if len({providers.revision, models.revision, defaults.revision, status.revision}) != 1:
        raise ValueError('Registry changed during snapshot; repeat after administrative updates finish')
    convert = lambda message: MessageToDict(message, preserving_proto_field_name=True,
                                           always_print_fields_with_no_presence=True)
    return {'providers': sorted([convert(p) for p in providers.providers], key=lambda p: p['provider']),
            'models': sorted([convert(m) for m in models.models], key=lambda m: (m['provider'], m['model'])),
            'defaults': sorted([convert(d) for d in defaults.defaults], key=lambda d: d['capability']),
            'revision': status.revision, 'schema_version': status.schema_version}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint', default='127.0.0.1:50052')
    parser.add_argument('--ca-file')
    parser.add_argument('--admin-endpoint', default='127.0.0.1:50053')
    parser.add_argument('--admin-ca-file')
    parser.add_argument('--admin-token-file', required=True)
    parser.add_argument('--matrix', type=Path, help='JSON array of {provider, model}; omitted means discover all eligible models')
    parser.add_argument('--demonstrate-switching', action='store_true', help='Temporarily change structured_image default and restore it using revision guards')
    parser.add_argument('--snapshot', type=Path, help='Write current non-secret registry snapshot for restart verification')
    parser.add_argument('--compare-snapshot', type=Path, help='Require exact persisted state from a prior snapshot, then run matrix requests')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    requested = None
    if args.matrix:
        requested = {(x['provider'], x['model']) for x in json.loads(args.matrix.read_text(encoding='utf-8'))}
    with channel(args.endpoint, args.ca_file) as inference_channel, channel(args.admin_endpoint, args.admin_ca_file) as admin_channel:
        admin = AdminClient(rpc.XlmAdminServiceStub(admin_channel), args.admin_token_file)
        stub = rpc.XlmEcosystemServiceStub(inference_channel)
        state = snapshot(admin)
        persistence_result = None
        if args.compare_snapshot:
            persistence_result = 'PASS' if state == json.loads(args.compare_snapshot.read_text(encoding='utf-8')) else 'FAIL'
        pairs, pending = discover(admin, requested)
        client_id = str(uuid.uuid4())
        if not stub.registerClient(pb.ClientRegistrationRequest(client_id=client_id, client_name='administration-matrix'), timeout=10).success:
            raise RuntimeError('XLM registration failed')
        try:
            rows = [invoke(stub, client_id, *pair) for pair in pairs] + pending
            counts = Counter(row['provider'] for row in rows if row['result'] == 'PASS')
            evidence = {'matrix': rows, 'accepted_providers': sum(count >= 3 for count in counts.values()),
                        'result': 'PASS' if sum(count >= 3 for count in counts.values()) >= 3 else 'PENDING'}
            if persistence_result is not None:
                evidence['restart_persistence'] = persistence_result
                evidence['post_restart_inference'] = 'PASS' if any(row['result'] == 'PASS' for row in rows) else 'PENDING'
                if persistence_result != 'PASS':
                    evidence['result'] = 'FAIL'
            if args.demonstrate_switching:
                evidence['runtime_switching'] = switching(admin, stub, client_id, pairs)
                if evidence['runtime_switching']['result'] != 'PASS':
                    evidence['result'] = evidence['runtime_switching']['result']
            if args.snapshot:
                args.snapshot.write_text(json.dumps(snapshot(admin), indent=2) + '\n', encoding='utf-8')
            args.output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
            return 0 if evidence['result'] == 'PASS' else 2
        finally:
            stub.unregisterClient(pb.ClientUnregistrationRequest(client_id=client_id), timeout=10)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (grpc.RpcError, ValueError, OSError, KeyError, TypeError):
        print('Verification could not complete; check configuration, transport and registry state. No upstream details are emitted.', file=sys.stderr)
        sys.exit(2)
