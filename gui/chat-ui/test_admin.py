"""Admin Console RPC contract, browser boundary and shared transport tests."""
import importlib.util
import io
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python_client' / 'generated'))
sys.path.insert(0, str(ROOT / 'python_client'))
import grpc
import xlm_eco_api_pb2 as pb
from admin_transport import AdminClient, channel
spec = importlib.util.spec_from_file_location('admin_chat_app', Path(__file__).with_name('app.py'))
chat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chat)


class AdminUiTest(unittest.TestCase):
    def setUp(self):
        self.client = chat.app.test_client()
        self.headers = {'X-XLM-UI-Token': chat.ui_token}
        chat.admin = Mock()
        chat.admin.call.return_value = pb.AdminRevision(revision=2)

    def test_all_views_and_operations_use_admin_rpcs(self):
        self.assertEqual(200, self.client.get('/admin').status_code)
        script = self.client.get('/static/admin.js')
        self.assertEqual(200, script.status_code)
        self.assertIn(b'AdminConsole', script.data)
        script.close()
        reads = [('providers?capability=structured_image', 'listProviders'), ('provider?provider=p', 'getProvider'),
                 ('models?provider=p&capability=structured_image', 'listModels'), ('defaults', 'getDefaults'), ('status', 'getStatus')]
        for path, method in reads:
            self.assertEqual(200, self.client.get('/admin/api/' + path).status_code)
            self.assertEqual(method, chat.admin.call.call_args.args[0])
        self.client.get('/admin/api/models?provider=p&capability=structured_image')
        self.assertEqual('p', chat.admin.call.call_args.args[1].provider)
        self.assertEqual('structured_image', chat.admin.call.call_args.args[1].capability)
        writes = [('provider', 'updateProvider', {'provider': 'p', 'enabled': False, 'configuration': {'timeout_seconds': '30'}, 'expected_revision': 1}),
                  ('model', 'upsertModel', {'model': {'provider': 'p', 'model': 'm', 'enabled': True, 'capabilities': ['structured_image']}, 'expected_revision': 1}),
                  ('default', 'setDefault', {'selection': {'capability': 'structured_image', 'provider': 'p', 'model': 'm'}, 'expected_revision': 1}),
                  ('reload', 'reloadCredentials', {'revision': 1})]
        for path, method, body in writes:
            self.assertEqual(200, self.client.post('/admin/api/' + path, json=body, headers=self.headers).status_code)
            self.assertEqual(method, chat.admin.call.call_args.args[0])
        self.assertEqual(400, self.client.post('/admin/api/model', json={'unknown': True}, headers=self.headers).status_code)
        self.assertEqual(400, self.client.post('/admin/api/model', json=[], headers=self.headers).status_code)

    def test_host_origin_csrf_and_socket_boundaries(self):
        for host in ('evil.example', 'localhost.evil.example', '127.0.0.1:9999'):
            result = self.client.get('/admin', headers={'Host': host})
            self.assertEqual(403, result.status_code)
            self.assertNotIn(chat.ui_token, result.get_data(as_text=True))
        self.assertEqual(403, self.client.get('/admin', headers={'Origin': 'https://evil.example'}).status_code)
        self.assertEqual(403, self.client.post('/admin/api/reload', json={'revision': 1}).status_code)
        self.assertEqual(403, self.client.post('/admin/api/reload', json={'revision': 1}, headers={'X-XLM-UI-Token': 'wrong'}).status_code)
        self.assertFalse(chat.socketio.test_client(chat.app, headers={'Host': 'evil.example'}, auth={'token': chat.ui_token}).is_connected())
        self.assertFalse(chat.socketio.test_client(chat.app, headers={'Origin': 'http://evil.example'}, auth={'token': chat.ui_token}).is_connected())
        self.assertFalse(chat.socketio.test_client(chat.app, auth={'token': 'wrong'}).is_connected())
        valid = chat.socketio.test_client(chat.app, auth={'token': chat.ui_token})
        self.assertTrue(valid.is_connected())
        valid.disconnect()

    def test_safe_admin_error(self):
        class Denied(grpc.RpcError):
            def code(self): return grpc.StatusCode.UNAUTHENTICATED
            def details(self): return 'SYNTHETIC_SECRET'
        chat.admin.call.side_effect = Denied()
        response = self.client.get('/admin/api/status')
        self.assertEqual(401, response.status_code)
        self.assertNotIn('SYNTHETIC_SECRET', response.get_data(as_text=True))

    def test_image_runtime_explicit_and_default_selection(self):
        chat.stub = Mock()
        chat.stub.generateStructuredImage.return_value = pb.StructuredImageResponse(json_payload='{"color":"red"}', provider='p', model='m', success=True, status=pb.STRUCTURED_IMAGE_COMPLETED)
        chat.client_id = 'client'
        for provider, model in [('p', 'm'), ('other', 'other-model'), ('', '')]:
            response = self.client.post('/analyze_image', headers=self.headers, data={
                'image': (io.BytesIO(b'image'), 'test.png', 'image/png'), 'provider': provider, 'model': model,
                'instructions': 'color?', 'json_schema': '{"type":"object"}'})
            self.assertEqual(200, response.status_code)
            sent = chat.stub.generateStructuredImage.call_args.args[0]
            self.assertEqual((provider, model), (sent.provider, sent.model))


class TransportTest(unittest.TestCase):
    def test_no_remote_plaintext_or_downgrade(self):
        with patch('admin_transport.grpc.insecure_channel') as insecure:
            for endpoint in ('example.com:50053', 'localhost:50053', '0.0.0.0:50053', '[::]:50053', '127.0.0.1:0'):
                with self.assertRaises(ValueError): channel(endpoint)
            insecure.assert_not_called()
            channel('127.0.0.1:50053')
            insecure.assert_called_once()
        with patch('admin_transport.Path.read_bytes', return_value=b'synthetic certificate'):
            with patch('admin_transport.grpc.secure_channel') as secure, patch('admin_transport.grpc.insecure_channel') as insecure:
                channel('example.com:50053', 'external-ca.pem')
                secure.assert_called_once()
                insecure.assert_not_called()

    def test_credential_only_in_server_rpc_metadata(self):
        marker = 'synthetic-admin-token-for-tests-only-12345'
        with patch('admin_transport.Path.read_text', return_value=marker):
            stub = Mock()
            client = AdminClient(stub, 'external-token')
            client.call('getStatus', pb.EmptyRequest())
            self.assertEqual((('authorization', 'Bearer ' + marker),), stub.getStatus.call_args.kwargs['metadata'])
        with patch('admin_transport.Path.read_text', return_value='bad'):
            with self.assertRaisesRegex(ValueError, 'unavailable or invalid'): AdminClient(stub, 'external-token')


if __name__ == '__main__':
    unittest.main()
