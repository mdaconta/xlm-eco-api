"""Executable old/new Python descriptor and wire compatibility regression.

The baseline is the task-start Git HEAD; override XLM_COMPAT_BASELINE to test another revision.
"""
import os
import subprocess
import sys
import unittest
from pathlib import Path
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
from grpc_tools import protoc

ROOT = Path(__file__).resolve().parents[3]
BASELINE = os.environ.get('XLM_COMPAT_BASELINE', '35050f741920006b98eaaff9cdbc744cf4872bee')
sys.path.insert(0, str(ROOT / 'python_client' / 'generated'))
import xlm_eco_api_pb2 as current
import xlm_eco_api_pb2_grpc as rpc


class CompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory = ROOT / 'target' / 'python-compatibility'
        directory.mkdir(parents=True, exist_ok=True)
        source = subprocess.check_output(['git', 'show', BASELINE + ':src/main/proto/xlm-eco-api.proto'], cwd=ROOT)
        (directory / 'baseline.proto').write_bytes(source)
        output = directory / 'baseline.pb'
        if protoc.main(['protoc', '-I' + str(directory), '--descriptor_set_out=' + str(output), str(directory / 'baseline.proto')]):
            raise RuntimeError('Baseline protobuf compilation failed')
        cls.old = descriptor_pb2.FileDescriptorSet.FromString(output.read_bytes()).file[0]
        cls.new = descriptor_pb2.FileDescriptorProto.FromString(current.DESCRIPTOR.serialized_pb)
        cls.old_pool = descriptor_pool.DescriptorPool()
        cls.old_pool.Add(cls.old)

    def test_every_legacy_field_enum_and_rpc_contract_is_preserved(self):
        def compare_messages(old, new):
            new_messages = {m.name: m for m in new}
            for message in old:
                actual = new_messages[message.name]
                fields = {f.name: f for f in actual.field}
                for field in message.field:
                    target = fields[field.name]
                    for attribute in ('number', 'type', 'type_name', 'label', 'oneof_index', 'proto3_optional'):
                        self.assertEqual(getattr(field, attribute), getattr(target, attribute), message.name + '.' + field.name + ':' + attribute)
                compare_messages(message.nested_type, actual.nested_type)
        compare_messages(self.old.message_type, self.new.message_type)
        enums = {e.name: e for e in self.new.enum_type}
        for enum in self.old.enum_type:
            actual = {v.name: v.number for v in enums[enum.name].value}
            for value in enum.value:
                self.assertEqual(value.number, actual[value.name])
        services = {s.name: s for s in self.new.service}
        for service in self.old.service:
            methods = {m.name: m for m in services[service.name].method}
            for method in service.method:
                self.assertEqual(method, methods[method.name])
        self.assertTrue(hasattr(rpc, 'XlmAdminServiceStub'))
        self.assertEqual(4, current.EmbeddingRequest.DESCRIPTOR.fields_by_name['provider'].number)
        self.assertEqual(5, current.EmbeddingRequest.DESCRIPTOR.fields_by_name['model'].number)
        self.assertEqual(4, current.AdminUpdateProviderRequest.DESCRIPTOR.fields_by_name['expected_revision'].number)

    def test_old_decoder_consumes_current_messages_and_current_decodes_old(self):
        for message in [current.ChatRequest(client_id='c', prompt='text', provider='p', model_name='m'),
                        current.StructuredImageRequest(client_id='c', provider='p', model='m', instructions='describe', image=current.ImageInput(mime_type='image/png', data=b'png'), json_schema='{}'),
                        current.StructuredImageResponse(provider='p', model='m', success=True, status=current.STRUCTURED_IMAGE_COMPLETED, json_payload='{}'),
                        current.EmbeddingRequest(client_id='c', text='hello', provider='p', model='m')]:
            old_class = message_factory.GetMessageClass(self.old_pool.FindMessageTypeByName(message.DESCRIPTOR.full_name))
            old_message = old_class.FromString(message.SerializeToString())
            self.assertEqual(message, type(message).FromString(old_message.SerializeToString()))
            if 'client_id' in old_message.DESCRIPTOR.fields_by_name:
                self.assertEqual('c', old_message.client_id)


if __name__ == '__main__': unittest.main()
