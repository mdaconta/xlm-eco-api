"""Deterministic evidence checks; these do not count as remote acceptance."""
import unittest
from unittest.mock import Mock
import admin_matrix_check as harness
pb = harness.pb


class HarnessTest(unittest.TestCase):
    def test_discovery_filters_and_marks_missing_credentials(self):
        admin = Mock()
        def call(method, request):
            if method == 'listProviders':
                self.assertEqual('structured_image', request.capability)
                return pb.AdminProviders(providers=[pb.AdminProvider(provider='p', enabled=True, credential_present=True), pb.AdminProvider(provider='q', enabled=True)])
            self.assertEqual('structured_image', request.capability)
            return pb.AdminModels(models=[pb.AdminModel(provider=request.provider, model='m', enabled=True)])
        admin.call.side_effect = call
        pairs, pending = harness.discover(admin, {('p', 'm'), ('q', 'm'), ('x', 'missing')})
        self.assertEqual([('p', 'm')], pairs)
        self.assertEqual(2, len(pending))
        self.assertTrue(all(row['result'] == 'PENDING' for row in pending))

    def test_strict_schema_and_image_acceptance(self):
        stub = Mock()
        for payload, expected in [('{"color":"red"}', 'PASS'), ('{"color":"blue"}', 'FAIL'), ('{"color":"red","extra":1}', 'FAIL'), ('{"color":1}', 'FAIL'), ('bad', 'FAIL')]:
            stub.generateStructuredImage.return_value = pb.StructuredImageResponse(json_payload=payload, provider='p', model='actual', success=True, status=pb.STRUCTURED_IMAGE_COMPLETED)
            row = harness.invoke(stub, 'client', 'p', 'm')
            self.assertEqual(expected, row['result'])
            self.assertEqual('actual', row['actual_model'])
            self.assertNotIn('json_payload', row)
            self.assertEqual('m', stub.generateStructuredImage.call_args.args[0].model)

    def test_switching_restores_default_with_exact_revision(self):
        admin, stub = Mock(), Mock()
        def call(method, request):
            if method == 'getDefaults':
                return pb.AdminDefaults(revision=4, defaults=[pb.AdminDefault(capability='structured_image', provider='old', model='old-model')])
            return pb.AdminRevision(revision=5)
        admin.call.side_effect = call
        def invoke(request, timeout):
            return pb.StructuredImageResponse(json_payload='{"color":"red"}', provider=request.provider or 'c', model=request.model or 'c1', success=True, status=pb.STRUCTURED_IMAGE_COMPLETED)
        stub.generateStructuredImage.side_effect = invoke
        result = harness.switching(admin, stub, 'client', [('a', 'a1'), ('a', 'a2'), ('b', 'b1'), ('c', 'c1')])
        self.assertEqual('PASS', result['result'])
        sent = [call.args[0] for call in stub.generateStructuredImage.call_args_list]
        self.assertEqual([('a','a1'),('a','a2'),('b','b1'),('c','c1'),('',''),('a','a1')], [(r.provider,r.model) for r in sent])
        restored = admin.call.call_args.args[1]
        self.assertEqual(5, restored.expected_revision)
        self.assertEqual('old', restored.selection.provider)

    def test_switching_rejects_wrong_default_model_on_correct_provider(self):
        admin, stub = Mock(), Mock()
        admin.call.side_effect = lambda method, request: (pb.AdminDefaults(revision=4) if method == 'getDefaults' else pb.AdminRevision(revision=5))
        def invoke(request, timeout):
            return pb.StructuredImageResponse(json_payload='{"color":"red"}', provider=request.provider or 'c', model=request.model or 'wrong-model', success=True, status=pb.STRUCTURED_IMAGE_COMPLETED)
        stub.generateStructuredImage.side_effect = invoke
        result = harness.switching(admin, stub, 'client', [('a', 'a1'), ('a', 'a2'), ('b', 'b1'), ('c', 'c1')])
        self.assertEqual('FAIL', result['result'])
        self.assertEqual('FAIL', result['requests'][4]['result'])

    def test_restart_snapshot_requires_consistent_revision(self):
        admin = Mock()
        admin.call.side_effect = [pb.AdminProviders(revision=2), pb.AdminModels(revision=2),
                                  pb.AdminDefaults(revision=2), pb.AdminStatus(revision=2, schema_version=1)]
        state = harness.snapshot(admin)
        self.assertEqual(2, state['revision'])
        self.assertEqual([], state['providers'])
        admin.call.side_effect = [pb.AdminProviders(revision=2), pb.AdminModels(revision=3),
                                  pb.AdminDefaults(revision=3), pb.AdminStatus(revision=3, schema_version=1)]
        with self.assertRaises(ValueError): harness.snapshot(admin)


if __name__ == '__main__': unittest.main()
