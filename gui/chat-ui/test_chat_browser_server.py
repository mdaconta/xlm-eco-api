"""Loopback-only fake XLM fixture for the real-browser Chat regression."""
import os
import time
from pathlib import Path
import base64
import sys
from test_app import chat, pb

class BrowserStub:
    def listModels(self, request, timeout):
        return pb.ModelCatalogResponse(models=[
            pb.ModelCatalogEntry(provider='openai',model='gpt-4o-mini' if os.environ.get('XLM_TEST_LIVE_NAMES') else 'text',enabled=True,capabilities=['chat','structured_image']),
            pb.ModelCatalogEntry(provider='openai',model='gpt-image-2.5-flare' if os.environ.get('XLM_TEST_LIVE_NAMES') else 'image',enabled=True,capabilities=['image_generation'])])
    def generateImage(self, request, timeout):
        delay = float(os.environ.get('XLM_BROWSER_DELAY_SECONDS', '0'))
        if not 0 <= delay <= 10:
            raise ValueError('Fixture delay must be between zero and ten seconds')
        time.sleep(delay)
        fixture_path = os.environ.get('XLM_BROWSER_IMAGE_FILE')
        image_data = Path(fixture_path).read_bytes() if fixture_path else base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF1kAAAAASUVORK5CYII=')
        return pb.ImageGenerationResponse(success=True,output_type=pb.GENERATED_IMAGE,
            image=pb.GeneratedImage(mime_type='image/png',data=image_data),
            provider='openai',model='image',request_id='browser-fixture')
    def generateStructuredImage(self, request, timeout):
        return pb.StructuredImageResponse(success=True,status=pb.STRUCTURED_IMAGE_COMPLETED,json_payload='{"description":"test pixel"}',provider='openai',model='text')

if __name__ == '__main__':
    port=int(sys.argv[1]);chat.stub=BrowserStub();chat.client_id='browser-test';chat.provider='openai';chat.model_name='text'
    if os.environ.get('XLM_TEST_LIVE_NAMES'):
        class ReadySession:
            def check_inference(self): return True
            def status(self): return {'inference':{'state':'ready'}}
        chat.session=ReadySession();chat.model_name='gpt-4o-mini'
    chat.app.config['UI_HOSTS']={f'127.0.0.1:{port}'}
    chat.socketio.run(chat.app,host='127.0.0.1',port=port,debug=False,allow_unsafe_werkzeug=True)
