"""Invoked by the Java integration test against its real loopback gRPC server."""
import sys
from pathlib import Path
import grpc

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'python_client/generated'))
import xlm_eco_api_pb2 as pb
import xlm_eco_api_pb2_grpc as rpc

with grpc.insecure_channel('127.0.0.1:' + sys.argv[1]) as channel:
    stub = rpc.XlmEcosystemServiceStub(channel)
    assert stub.registerClient(pb.ClientRegistrationRequest(client_id='python-bridge'), timeout=5).success
    catalog = stub.listModels(pb.ModelCatalogRequest(client_id='python-bridge', capability='image_generation'), timeout=5)
    assert any(m.provider == 'openai' and m.model == 'gpt-image-1' for m in catalog.models)
    result = stub.generateImage(pb.ImageGenerationRequest(client_id='python-bridge', provider='openai',
        model='gpt-image-1', prompt='Draw a blue circle'), timeout=5)
    assert result.success and result.output_type == pb.GENERATED_IMAGE
    assert result.image.mime_type == 'image/png' and result.image.data.startswith(b'\x89PNG\r\n\x1a\n')
    assert result.provider == 'openai' and result.model == 'gpt-image-1' and result.request_id
    denied = stub.generateImage(pb.ImageGenerationRequest(client_id='python-bridge', provider='openai',
        model='gpt-4o-mini', prompt='Draw a circle'), timeout=5)
    assert not denied.success and denied.error.code == pb.INCOMPATIBLE_CAPABILITY
    assert stub.unregisterClient(pb.ClientUnregistrationRequest(client_id='python-bridge'), timeout=5).success
print('Python -> Java gRPC -> simulated provider -> normalized image: PASS')
