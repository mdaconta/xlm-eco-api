"""Focused local verification of the chat UI's structured-image route."""

import importlib.util
import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import grpc

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python_client' / 'generated'))
import xlm_eco_api_pb2 as pb

spec = importlib.util.spec_from_file_location('xlm_chat_app', Path(__file__).with_name('app.py'))
chat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chat)

PNG = b'\x89PNG\r\n\x1a\n' + b'example-image'
SCHEMA = json.dumps({'type': 'object', 'properties': {'description': {'type': 'string'}},
                     'required': ['description'], 'additionalProperties': False})


class FakeStub:
    def __init__(self):
        self.request = None
        self.reply = pb.StructuredImageResponse(
            json_payload='{"description":"a generic test image"}', provider='openai',
            model='gpt-4o-mini-revision', success=True, status=pb.STRUCTURED_IMAGE_COMPLETED)

    def generateStructuredImage(self, request, timeout):
        self.request = request
        assert timeout == 90
        return self.reply


class ChatUiTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeStub()
        chat.stub = self.fake
        chat.client_id = 'test-client'
        chat.provider = 'openai'
        chat.model_name = 'gpt-4o-mini'
        self.client = chat.app.test_client()
        self.headers = {'X-XLM-UI-Token': chat.ui_token}

    def image_form(self, content=PNG, mime='image/png', schema=SCHEMA):
        return {'image': (io.BytesIO(content), 'image.png', mime),
                'instructions': 'Describe the image', 'json_schema': schema}

    def test_page_and_successful_image_request(self):
        page = self.client.get('/')
        self.assertEqual(200, page.status_code)
        self.assertIn(b'Analyze one image', page.data)
        self.assertIn(b'gpt-4o-mini', page.data)
        result = self.client.post('/analyze_image', data=self.image_form(),
                                  headers=self.headers, content_type='multipart/form-data')
        self.assertEqual(200, result.status_code)
        data = result.get_json()
        self.assertTrue(data['success'])
        self.assertEqual({'description': 'a generic test image'}, data['structured_result'])
        self.assertEqual('gpt-4o-mini-revision', data['model'])
        self.assertEqual('STRUCTURED_IMAGE_COMPLETED', data['status'])
        self.assertEqual('test-client', self.fake.request.client_id)
        self.assertEqual('openai', self.fake.request.provider)
        self.assertEqual('gpt-4o-mini', self.fake.request.model)
        self.assertEqual('image/png', self.fake.request.image.mime_type)
        self.assertEqual(PNG, self.fake.request.image.data)
        self.assertEqual('Describe the image', self.fake.request.instructions)
        self.assertEqual(SCHEMA, self.fake.request.json_schema)

    def test_input_and_token_failures_do_not_call_grpc(self):
        self.assertEqual(403, self.client.post('/analyze_image', data=self.image_form(),
            content_type='multipart/form-data').status_code)
        self.assertEqual(400, self.client.post('/analyze_image', data=self.image_form(mime='text/plain'),
            headers=self.headers, content_type='multipart/form-data').status_code)
        self.assertEqual(400, self.client.post('/analyze_image', data=self.image_form(schema='[]'),
            headers=self.headers, content_type='multipart/form-data').status_code)
        self.assertEqual(400, self.client.post('/analyze_image', data=self.image_form(content=b'x' * (3 * 1024 * 1024 + 1)),
            headers=self.headers, content_type='multipart/form-data').status_code)
        self.assertIsNone(self.fake.request)

    def test_normalized_and_transport_failures(self):
        self.fake.reply = pb.StructuredImageResponse(
            provider='openai', model='gpt-4o-mini', success=False,
            status=pb.STRUCTURED_IMAGE_FAILED,
            error=pb.StructuredImageError(code=pb.PROVIDER_QUOTA,
                                          message='Provider quota or spend limit reached', retryable=False,
                                          provider_http_status=429))
        result = self.client.post('/analyze_image', data=self.image_form(),
                                  headers=self.headers, content_type='multipart/form-data')
        self.assertEqual(200, result.status_code)
        self.assertEqual('PROVIDER_QUOTA', result.get_json()['error_code'])
        self.assertFalse(result.get_json()['retryable'])

        class Timeout(grpc.RpcError):
            def code(self):
                return grpc.StatusCode.DEADLINE_EXCEEDED

            def details(self):
                return 'private upstream details'

        with patch.object(self.fake, 'generateStructuredImage', side_effect=Timeout()):
            result = self.client.post('/analyze_image', data=self.image_form(),
                                      headers=self.headers, content_type='multipart/form-data')
        self.assertEqual(504, result.status_code)
        self.assertEqual('DEADLINE_EXCEEDED', result.get_json()['transport_code'])
        self.assertNotIn('private upstream details', result.get_data(as_text=True))

    def test_existing_text_route_still_constructs_chat_request(self):
        connection = chat.socketio.test_client(chat.app, auth={'token': chat.ui_token})
        token = connection.get_received()[0]['args'][0]['token']
        with patch.object(chat.threading, 'Thread') as thread_class:
            response = self.client.post('/send_message', json={'message': 'Hello', 'stream_session': token, 'request_id': 'one'}, headers=self.headers)
        connection.disconnect()
        self.assertEqual(200, response.status_code)
        thread_class.return_value.start.assert_called_once()
        chat_request = thread_class.call_args.kwargs['args'][0]
        self.assertEqual('Hello', chat_request.prompt)
        self.assertEqual('openai', chat_request.provider)
        self.assertEqual('gpt-4o-mini', chat_request.model_name)
        self.assertEqual(403, self.client.post('/send_message', json={'message': 'Hello'}).status_code)


if __name__ == '__main__':
    unittest.main()
