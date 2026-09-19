"""Exercise the sample client contract with deterministic gRPC responses."""
import contextlib
import io
from pathlib import Path
import runpy
import sys
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'python_client/generated'))
sys.path.insert(0, str(ROOT))
from python_client import xlm_client, admin_transport


class LegacyClientTest(unittest.TestCase):
    def exercise(self, registration=True, selection=True, embedding=True, unregister=True, stream_error=False, cli=False):
        stub = Mock()
        stub.registerClient.return_value = NS(success=registration)
        capabilities = NS(provider_name='fake', service_level=1, capabilities={'embedding': embedding})
        stub.listProviders.return_value = NS(providers=[capabilities])
        stub.getProviderCapabilities.return_value = capabilities
        stub.setPreferredProviders.return_value = NS(success=selection)
        stub.syncChat.return_value = NS(completion='completion')
        def stream():
            yield NS(token='token')
            if stream_error:
                error = xlm_client.grpc.RpcError()
                error.details = lambda: 'test stream failure'
                raise error
        stub.asyncChat.return_value = stream()
        stub.getEmbedding.return_value = NS(embedding=[1.0, 2.0])
        stub.unregisterClient.return_value = NS(success=unregister)
        output = io.StringIO()
        with patch.object(xlm_client.grpc, 'insecure_channel') as channel, patch.object(xlm_client.xlm_eco_api_pb2_grpc, 'XlmEcosystemServiceStub', return_value=stub), contextlib.redirect_stdout(output):
            if cli:
                with patch.object(sys, 'argv', ['xlm_client', '--provider', 'fake', '--model_name', 'model', '--prompt', 'hello']):
                    runpy.run_path(str(ROOT / 'python_client/xlm_client.py'), run_name='__main__')
            else:
                xlm_client.run('localhost', 50051, 'fake', 'model', 'hello')
            if registration and selection:
                request = stub.syncChat.call_args.args[0]
                self.assertEqual((request.provider, request.model_name, request.prompt), ('fake', 'model', 'hello'))
                self.assertEqual(stub.getEmbedding.called, embedding)
                channel.return_value.close.assert_called_once()
            else:
                stub.syncChat.assert_not_called()
        return output.getvalue()

    def test_cli_success_and_request_mapping(self):
        self.assertIn('Successfully unregistered', self.exercise(cli=True))

    def test_registration_and_selection_abort_before_inference(self):
        self.assertIn('Failed to register', self.exercise(registration=False))
        self.assertIn('Failed to set', self.exercise(selection=False))

    def test_stream_failure_and_unsupported_embedding(self):
        output = self.exercise(embedding=False, unregister=False, stream_error=True)
        self.assertIn('test stream failure', output)
        self.assertIn('does not support', output)
        self.assertIn('Failed to unregister', output)

    def test_transport_rejects_malformed_host_and_empty_trust(self):
        for endpoint in ('/bad:443', '@bad:443', ':443', 'host:0', 'host:65536'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                admin_transport.channel(endpoint)
        with patch.object(Path, 'read_bytes', return_value=b''), self.assertRaises(ValueError):
            admin_transport.channel('example.org:443', 'empty.pem')
