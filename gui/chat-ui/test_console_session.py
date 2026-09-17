"""Connection recovery over loopback gRPC, plus deterministic failure/concurrency guards."""
import concurrent.futures
import importlib.util
import io
import logging
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python_client' / 'generated'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import grpc
import xlm_eco_api_pb2 as pb
import xlm_eco_api_pb2_grpc as rpc
from console_session import ConsoleSession


class RpcFailure(grpc.RpcError):
    def __init__(self, code, detail='SYNTHETIC_PRIVATE_DETAIL'):
        self._code, self._detail = code, detail
    def code(self): return self._code
    def details(self): return self._detail


class Service(rpc.XlmEcosystemServiceServicer):
    def __init__(self):
        self.clients = set()
        self.registrations = 0
        self.preference_calls = 0
        self.images = []
        self.allow_chat = True
    def getProviderCapabilities(self, request, context):
        if request.client_id not in self.clients:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, 'Client is not registered')
        return pb.ProviderCapabilitiesResponse(provider_name=request.provider, capabilities={'chat': self.allow_chat})
    def registerClient(self, request, context):
        self.registrations += 1
        success = request.client_id not in self.clients
        self.clients.add(request.client_id)
        return pb.ClientRegistrationResponse(success=success)
    def setPreferredProviders(self, request, context):
        self.preference_calls += 1
        return pb.SelectionResponse(success=self.allow_chat)
    def generateStructuredImage(self, request, context):
        if request.client_id not in self.clients:
            return pb.StructuredImageResponse(error=pb.StructuredImageError(
                code=pb.INVALID_REQUEST, message='Client is not registered'))
        self.images.append(request)
        return pb.StructuredImageResponse(success=True, status=pb.STRUCTURED_IMAGE_COMPLETED,
            provider=request.provider or 'openai', model=request.model or 'saved-default', json_payload='{"color":"red"}')


def server(service, port=0):
    instance = grpc.server(concurrent.futures.ThreadPoolExecutor(max_workers=4))
    rpc.add_XlmEcosystemServiceServicer_to_server(service, instance)
    actual = instance.add_insecure_port(f'127.0.0.1:{port}')
    if not actual:
        raise RuntimeError('Test server could not bind')
    instance.start()
    return instance, actual


def eventually(predicate, timeout=8):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if predicate(): return
        time.sleep(.02)
    raise AssertionError('Lifecycle did not reach expected state')


class RecoveryTest(unittest.TestCase):
    def test_live_console_detects_stop_reregisters_and_preserves_requests(self):
        spec = importlib.util.spec_from_file_location('recovery_chat_app', Path(__file__).with_name('app.py'))
        chat = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(chat)
        first = Service()
        service, port = server(first)
        channel = grpc.insecure_channel(f'127.0.0.1:{port}', options=[
            ('grpc.initial_reconnect_backoff_ms', 100), ('grpc.min_reconnect_backoff_ms', 100),
            ('grpc.max_reconnect_backoff_ms', 200)])
        session = ConsoleSession(rpc.XlmEcosystemServiceStub(channel), 'stable-client', 'openai', interval=.05, timeout=.3)
        chat.session, chat.stub, chat.client_id = session, session.stub, session.client_id
        client = chat.app.test_client()
        headers = {'X-XLM-UI-Token': chat.ui_token}
        def image(provider, model):
            return client.post('/analyze_image', headers=headers, data={
                'provider': provider, 'model': model, 'instructions': 'color?', 'json_schema': '{"type":"object"}',
                'image': (io.BytesIO(b'test-image'), 'test.png', 'image/png')})
        try:
            with self.assertLogs('xlm.console.lifecycle', logging.INFO) as logs:
                session.start()
                eventually(lambda: session.status()['inference']['state'] == 'ready')
                self.assertEqual(1, first.registrations)
                self.assertEqual(200, image('openai', 'explicit-model').status_code)
                service.stop(0).wait()
                eventually(lambda: session.status()['inference']['state'] == 'disconnected')
                self.assertEqual('disconnected', client.get('/console/status').json['inference']['state'])
                self.assertEqual(503, image('openai', 'explicit-model').status_code)
                second = Service()
                service, _ = server(second, port)
                eventually(lambda: session.status()['generation'] == 2)
                self.assertEqual(1, second.registrations)
                self.assertEqual(1, second.preference_calls)
                self.assertEqual('ready', client.get('/console/status').json['inference']['state'])
                explicit = image('openai', 'explicit-model').json
                default = image('', '').json
                self.assertTrue(explicit['success'])
                self.assertEqual('explicit-model', explicit['model'])
                self.assertEqual('saved-default', default['model'])
                self.assertEqual('stable-client', second.images[0].client_id)
                self.assertEqual(b'test-image', second.images[0].image.data)
                self.assertEqual(2, len(second.images))
            text = '\n'.join(logs.output)
            for event in ('state=disconnected', 're_registration_started', 're_registration_completed', 'event=recovered'):
                self.assertIn(event, text)
            self.assertNotIn('SYNTHETIC_PRIVATE_DETAIL', text)
        finally:
            session.close()
            self.assertFalse(session._thread.is_alive())
            channel.close()
            service.stop(0).wait()

    def test_fast_restart_and_concurrent_checks_register_once(self):
        state = {'registered': False}
        stub = Mock()
        def probe(*args, **kwargs):
            if not state['registered']:
                raise RpcFailure(grpc.StatusCode.FAILED_PRECONDITION, 'Client is not registered')
            return pb.ProviderCapabilitiesResponse(capabilities={'chat': True})
        def register(*args, **kwargs):
            state['registered'] = True
            return pb.ClientRegistrationResponse(success=True)
        stub.getProviderCapabilities.side_effect = probe
        stub.registerClient.side_effect = register
        stub.setPreferredProviders.return_value = pb.SelectionResponse(success=True)
        session = ConsoleSession(stub, 'same-id', 'openai')
        self.assertTrue(session.check_inference())
        state['registered'] = False  # Server restarted between probes without a transport failure.
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            self.assertTrue(all(pool.map(lambda _: session.check_inference(), range(8))))
        self.assertEqual(2, stub.registerClient.call_count)
        self.assertEqual(2, stub.setPreferredProviders.call_count)
        self.assertEqual(2, session.status()['generation'])

    def test_registration_timeout_after_commit_probes_same_uuid(self):
        state = {'registered': False}
        stub = Mock()
        def probe(*args, **kwargs):
            if not state['registered']:
                raise RpcFailure(grpc.StatusCode.FAILED_PRECONDITION, 'Client is not registered')
            return pb.ProviderCapabilitiesResponse(capabilities={'chat': True})
        def committed_timeout(*args, **kwargs):
            state['registered'] = True
            raise RpcFailure(grpc.StatusCode.DEADLINE_EXCEEDED)
        stub.getProviderCapabilities.side_effect = probe
        stub.registerClient.side_effect = committed_timeout
        stub.setPreferredProviders.return_value = pb.SelectionResponse(success=True)
        session = ConsoleSession(stub, 'stable-id', 'openai')
        self.assertFalse(session.check_inference())
        self.assertTrue(session.check_inference())
        self.assertEqual(1, stub.registerClient.call_count)
        self.assertEqual(1, stub.setPreferredProviders.call_count)

    def test_duplicate_response_after_registration_race_is_probed(self):
        stub = Mock()
        stub.getProviderCapabilities.side_effect = [RpcFailure(grpc.StatusCode.FAILED_PRECONDITION,
            'Client is not registered'), pb.ProviderCapabilitiesResponse(capabilities={'chat': True})]
        stub.registerClient.return_value = pb.ClientRegistrationResponse(success=False)
        stub.setPreferredProviders.return_value = pb.SelectionResponse(success=True)
        self.assertTrue(ConsoleSession(stub, 'stable', 'openai').check_inference())

    def test_admin_auth_and_chat_config_do_not_block_inference_or_leak_details(self):
        stub, admin = Mock(), Mock()
        stub.setPreferredProviders.return_value = pb.SelectionResponse(success=False, message='SYNTHETIC_PRIVATE_DETAIL')
        admin.call.side_effect = RpcFailure(grpc.StatusCode.UNAUTHENTICATED)
        session = ConsoleSession(stub, 'stable', 'disabled-chat-provider', admin)
        with self.assertLogs('xlm.console.lifecycle', logging.INFO) as logs:
            state = session.check()
        self.assertEqual('ready', state['inference']['state'])
        self.assertEqual('configuration_error', state['chat']['state'])
        self.assertEqual('authentication_error', state['administration']['state'])
        self.assertNotIn('SYNTHETIC_PRIVATE_DETAIL', '\n'.join(logs.output) + str(state))
        session.check()
        stub.registerClient.assert_not_called()
        admin.call.side_effect = None
        stub.setPreferredProviders.return_value = pb.SelectionResponse(success=True)
        state = session.check()
        self.assertEqual('ready', state['chat']['state'])
        self.assertEqual('ready', state['administration']['state'])

    def test_chat_provider_disable_enable_updates_status_without_reregistration(self):
        stub = Mock()
        stub.getProviderCapabilities.return_value = pb.ProviderCapabilitiesResponse(capabilities={'chat': True})
        stub.setPreferredProviders.return_value = pb.SelectionResponse(success=True)
        session = ConsoleSession(stub, 'stable', 'openai')
        self.assertTrue(session.check_inference())
        self.assertEqual('ready', session.status()['chat']['state'])
        stub.getProviderCapabilities.return_value = pb.ProviderCapabilitiesResponse(capabilities={'chat': False})
        self.assertTrue(session.check_inference())
        self.assertEqual('configuration_error', session.status()['chat']['state'])
        self.assertEqual('ready', session.status()['inference']['state'])
        stub.getProviderCapabilities.return_value = pb.ProviderCapabilitiesResponse(capabilities={'chat': True})
        self.assertTrue(session.check_inference())
        self.assertEqual('ready', session.status()['chat']['state'])
        stub.registerClient.assert_not_called()
        self.assertEqual(2, stub.setPreferredProviders.call_count)

    def test_start_while_unavailable_recovers_without_reconfiguration(self):
        stub = Mock()
        stub.getProviderCapabilities.side_effect = RpcFailure(grpc.StatusCode.UNAVAILABLE)
        stub.setPreferredProviders.return_value = pb.SelectionResponse(success=True)
        session = ConsoleSession(stub, 'stable', 'openai', interval=.02, timeout=.1)
        try:
            session.start()
            original_thread = session._thread
            session.start()
            self.assertIs(original_thread, session._thread)
            eventually(lambda: session.status()['inference']['state'] == 'disconnected')
            stub.getProviderCapabilities.side_effect = None
            eventually(lambda: session.status()['inference']['state'] == 'ready')
            self.assertEqual('stable', session.client_id)
        finally:
            session.close()
        self.assertFalse(session._thread.is_alive())

    def test_no_inference_is_replayed_on_transport_failure(self):
        spec = importlib.util.spec_from_file_location('no_replay_app', Path(__file__).with_name('app.py'))
        chat = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(chat)
        chat.stub = Mock()
        chat.client_id = 'client'
        chat.stub.generateStructuredImage.side_effect = RpcFailure(grpc.StatusCode.UNAVAILABLE)
        chat.session = Mock()
        chat.session.check_inference.return_value = True
        response = chat.app.test_client().post('/analyze_image', headers={'X-XLM-UI-Token': chat.ui_token}, data={
            'image': (io.BytesIO(b'bytes'), 'test.png', 'image/png'), 'provider': 'p', 'model': 'm',
            'instructions': 'color?', 'json_schema': '{}'})
        self.assertEqual(502, response.status_code)
        chat.stub.generateStructuredImage.assert_called_once()
        self.assertNotIn('SYNTHETIC_PRIVATE_DETAIL', response.get_data(as_text=True))


if __name__ == '__main__':
    unittest.main()
