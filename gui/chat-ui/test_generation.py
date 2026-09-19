"""Generation, catalog and isolated streaming boundary tests."""
import base64
import io
import unittest
from unittest.mock import Mock, patch
from test_app import chat, pb
import grpc

class RpcFailure(grpc.RpcError):
    def __init__(self, code): self.status = code
    def code(self): return self.status
    def details(self): return 'PRIVATE_PROVIDER_BODY'

class GenerationUiTest(unittest.TestCase):
    def setUp(self):
        chat.session = None
        chat.stub = Mock()
        chat.client_id = 'client'
        chat.provider = 'openai'
        chat.model_name = 'gpt-image-1'
        self.client = chat.app.test_client()
        self.headers = {'X-XLM-UI-Token': chat.ui_token}

    def post(self, body=None):
        return self.client.post('/generate_image', json=body or {'message':'Draw a tree'}, headers=self.headers)

    def test_catalog_uses_registered_inference_not_admin(self):
        chat.stub.listModels.return_value = pb.ModelCatalogResponse(models=[pb.ModelCatalogEntry(provider='openai', model='gpt-image-1', enabled=True, capabilities=['image_generation'])], revision=7)
        with patch.object(chat, 'admin') as admin:
            response = self.client.get('/models')
            admin.call.assert_not_called()
        self.assertEqual(200, response.status_code)
        self.assertEqual(['image_generation'],response.json['models'][0]['capabilities'])
        self.assertEqual('client',chat.stub.listModels.call_args.args[0].client_id)
        self.assertEqual(10,chat.stub.listModels.call_args.kwargs['timeout'])

    def test_generation_success_contract_and_explicit_selection(self):
        chat.stub.generateImage.return_value = pb.ImageGenerationResponse(success=True, output_type=pb.GENERATED_IMAGE, image=pb.GeneratedImage(mime_type='image/png',data=b'png'), provider='google',model='actual',request_id='server-id')
        response = self.post({'message':'Draw a tree','provider':'google','model':'chosen'})
        self.assertEqual(200,response.status_code)
        self.assertEqual('image',response.json['output_type'])
        self.assertEqual(b'png',base64.b64decode(response.json['image_base64']))
        self.assertEqual('actual',response.json['model'])
        self.assertEqual('server-id',response.json['request_id'])
        sent = chat.stub.generateImage.call_args
        self.assertEqual(('client','google','chosen'),(sent.args[0].client_id,sent.args[0].provider,sent.args[0].model))
        self.assertEqual(160,sent.kwargs['timeout'])

    def test_errors_and_invalid_generated_envelope(self):
        chat.stub.generateImage.return_value = pb.ImageGenerationResponse(error=pb.StructuredImageError(code=pb.PROVIDER_QUOTA,message='PRIVATE_PROVIDER_BODY'))
        response = self.post()
        self.assertEqual('PROVIDER_QUOTA',response.json['error_code'])
        self.assertNotIn('PRIVATE_PROVIDER_BODY',response.text)
        for mime,data,output in [('image/webp',b'a',pb.GENERATED_IMAGE),('image/png',b'',pb.GENERATED_IMAGE),('image/png',b'x'*(3*1024*1024+1),pb.GENERATED_IMAGE),('image/png',b'a',0)]:
            chat.stub.generateImage.return_value = pb.ImageGenerationResponse(success=True, output_type=output,image=pb.GeneratedImage(mime_type=mime,data=data))
            self.assertEqual(502,self.post().status_code)
        for code,expected in [(grpc.StatusCode.DEADLINE_EXCEEDED,504),(grpc.StatusCode.UNAVAILABLE,502),(grpc.StatusCode.PERMISSION_DENIED,502)]:
            chat.stub.generateImage.side_effect = RpcFailure(code)
            result = self.post()
            self.assertEqual(expected,result.status_code)
            self.assertNotIn('PRIVATE_PROVIDER_BODY',result.text)
            chat.stub.listModels.side_effect = RpcFailure(code)
            self.assertEqual(expected,self.client.get('/models').status_code)

    def test_optional_title_uses_selected_model_and_sanitizes_failure(self):
        request=pb.ChatRequest(client_id='client',provider='selected-provider',model_name='selected-model')
        chat.stub.syncChat.return_value=pb.ChatResponse(completion='  Short title  ')
        self.assertEqual('Short title',chat.generate_chat_title(request,'Response body'))
        sent=chat.stub.syncChat.call_args.args[0]
        self.assertEqual(('selected-provider','selected-model'),(sent.provider,sent.model_name))
        chat.stub.syncChat.side_effect=RuntimeError('PRIVATE_PROVIDER_BODY')
        self.assertEqual('Untitled',chat.generate_chat_title(request,'Response body'))

    def test_validation_and_recovering_connection(self):
        for body in [[],{'message':12},{'message':' '},{'message':'x'*65537},{'message':'ok','image':'data'},{'message':'ok','provider':[]},{'message':'ok','model':'x'*129}]:
            self.assertEqual(400,self.client.post('/generate_image',json=body,headers=self.headers).status_code)
        chat.stub.generateImage.assert_not_called()
        chat.session = Mock()
        chat.session.check_inference.return_value = False
        chat.session.status.return_value = {'inference':{'state':'recovering'}}
        self.assertEqual(503,self.post().status_code)
        self.assertEqual(503,self.client.get('/models').status_code)
        chat.session = None

    def test_streams_cannot_cross_browser_connections_or_request_ids(self):
        first=chat.socketio.test_client(chat.app,auth={'token':chat.ui_token})
        second=chat.socketio.test_client(chat.app,auth={'token':chat.ui_token})
        token=first.get_received()[0]['args'][0]['token']; second.get_received()
        chat.stub.syncChat.return_value = pb.ChatResponse(completion='A title')
        chat.stub.asyncChat.return_value = [pb.ChatResponsePart(token='one'),pb.ChatResponsePart(token='two')]
        # The synchronous worker call below makes routing deterministic for the test.
        with patch.object(chat.threading,'Thread') as thread:
            response=self.client.post('/send_message',json={'message':'hello','request_id':'entry-1','stream_session':token},headers=self.headers)
        self.assertEqual(200,response.status_code)
        chat.stream_responses(*thread.call_args.kwargs['args'])
        events=first.get_received()
        self.assertEqual(['chat_response','chat_response','chat_done','chat_title'],[e['name'] for e in events])
        self.assertTrue(all(e['args'][0]['request_id']=='entry-1' for e in events))
        self.assertEqual([],second.get_received())
        chat.stub.asyncChat.side_effect=RuntimeError('PRIVATE_PROVIDER_BODY')
        chat.stream_responses(*thread.call_args.kwargs['args'])
        self.assertNotIn('PRIVATE_PROVIDER_BODY',str(first.get_received()))
        first.disconnect()
        self.assertEqual(400,self.client.post('/send_message',json={'message':'hello','request_id':'entry-2','stream_session':token},headers=self.headers).status_code)
        second.disconnect()

class ChatBoundaryTest(unittest.TestCase):
    def setUp(self):
        chat.session=None;chat.admin=None;chat.stub=Mock();chat.client_id='client'
        self.client=chat.app.test_client();self.headers={'X-XLM-UI-Token':chat.ui_token}

    def test_unconfigured_and_unknown_admin_status(self):
        self.assertEqual('unconfigured',self.client.get('/console/status').json['inference']['state'])
        self.assertEqual(503,self.client.get('/admin/api/status').status_code)
        chat.admin=Mock()
        self.assertEqual(404,self.client.get('/admin/api/unknown').status_code)
        chat.session=Mock();chat.session.status.return_value={'inference':{'state':'ready'}}
        self.assertEqual('ready',self.client.get('/console/status').json['inference']['state'])
        chat.session=None

    def test_malformed_analysis_and_size_boundary(self):
        def form(**values):
            return {'image':(io.BytesIO(b'png'),'i.png','image/png'),'instructions':'describe','json_schema':'{}',**values}
        for body in [{},form(instructions=''),form(json_schema='{'),form(json_schema='x'*65537)]:
            self.assertEqual(400,self.client.post('/analyze_image',data=body,headers=self.headers).status_code)
        self.assertEqual(413,self.client.post('/generate_image',data=b'x'*(4*1024*1024+1),content_type='application/json',headers=self.headers).status_code)
        for payload in ['[]','{']:
            chat.stub.generateStructuredImage.return_value=pb.StructuredImageResponse(success=True,json_payload=payload)
            self.assertEqual(502,self.client.post('/analyze_image',data=form(),headers=self.headers).status_code)
        chat.session=Mock();chat.session.check_inference.return_value=False;chat.session.status.return_value={'inference':{'state':'recovering'}}
        self.assertEqual(503,self.client.post('/analyze_image',data=form(),headers=self.headers).status_code)
        chat.session=None

    def test_main_uses_secure_channels_and_closes_lifecycle_on_failure(self):
        import os
        import runpy
        import sys
        from pathlib import Path
        module=str(Path(__file__).with_name('app.py'))
        with patch.object(sys,'argv',['app.py']):
            with self.assertRaises(SystemExit) as error: runpy.run_path(module,run_name='__main__')
            self.assertEqual(1,error.exception.code)
        for admin_enabled in [False,True]:
            env={'XLM_ADMIN_ENDPOINT':'127.0.0.1:50053','XLM_ADMIN_TOKEN_FILE':'external-token'} if admin_enabled else {}
            with patch.dict(os.environ,env,clear=True), patch.object(sys,'argv',['app.py','127.0.0.1','50051','5010','openai','text']), patch('admin_transport.channel') as channel, patch('admin_transport.AdminClient') as admin, patch('console_session.ConsoleSession') as lifecycle, patch('flask_socketio.SocketIO.run',side_effect=RuntimeError('server stopped')):
                with self.assertRaisesRegex(RuntimeError,'server stopped'): runpy.run_path(module,run_name='__main__')
                lifecycle.return_value.start.assert_called_once();lifecycle.return_value.close.assert_called_once()
                self.assertEqual(2 if admin_enabled else 1,channel.return_value.close.call_count)
                if admin_enabled: admin.assert_called_once()

class SessionFailureBoundariesTest(unittest.TestCase):
    def test_unknown_provider_does_not_block_explicit_inference(self):
        from console_session import ConsoleSession
        stub=Mock();stub.getProviderCapabilities.side_effect=RpcFailure(grpc.StatusCode.NOT_FOUND)
        session=ConsoleSession(stub,'client','removed-provider')
        self.assertTrue(session.check_inference())
        self.assertEqual('NOT_FOUND',session.status()['chat']['code'])
        stub.registerClient.assert_not_called();session.close()

    def test_failed_registration_is_reported_without_replay(self):
        from console_session import ConsoleSession
        class Unregistered(RpcFailure):
            def details(self): return 'Client is not registered'
        stub=Mock();stub.getProviderCapabilities.side_effect=Unregistered(grpc.StatusCode.FAILED_PRECONDITION)
        session=ConsoleSession(stub,'client','provider')
        self.assertFalse(session.check_inference());stub.registerClient.assert_called_once()
        self.assertEqual('REGISTRATION_REJECTED',session.status()['inference']['code'])

    def test_preference_failure_classification_preserves_inference_boundary(self):
        from console_session import ConsoleSession
        for code,ready,state in [(grpc.StatusCode.UNAVAILABLE,False,'disconnected'),(grpc.StatusCode.PERMISSION_DENIED,True,'authentication_error'),(grpc.StatusCode.INVALID_ARGUMENT,True,'configuration_error')]:
            stub=Mock();stub.getProviderCapabilities.return_value=pb.ProviderCapabilitiesResponse(capabilities={'chat':True})
            stub.setPreferredProviders.side_effect=RpcFailure(code)
            session=ConsoleSession(stub,'client','provider')
            self.assertEqual(ready,session.check_inference())
            self.assertEqual(state,session.status()['chat']['state'])
            self.assertNotIn('PRIVATE_PROVIDER_BODY',str(session.status()))

if __name__ == '__main__': unittest.main()
