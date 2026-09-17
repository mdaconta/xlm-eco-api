'use strict';
// Runtime consumption follows node_client/app.js. Baseline is task-start Git HEAD.
const assert = require('assert/strict');
const {execFileSync} = require('child_process');
const path = require('path');
const protobuf = require('protobufjs');
const loader = require('@grpc/proto-loader');
const grpc = require('@grpc/grpc-js');
const root = path.resolve(__dirname, '../../..');
const baseline = process.env.XLM_COMPAT_BASELINE || '35050f741920006b98eaaff9cdbc744cf4872bee';
const oldText = process.argv[2] ? require('fs').readFileSync(process.argv[2], 'utf8') :
  execFileSync('git', ['show', baseline + ':src/main/proto/xlm-eco-api.proto'], {cwd: root, encoding: 'utf8'});
const oldRoot = protobuf.parse(oldText, {keepCase: true}).root.resolveAll();
const newRoot = protobuf.loadSync(path.join(root, 'src/main/proto/xlm-eco-api.proto')).resolveAll();
let fields = 0, methods = 0;
function compare(oldNode) {
  for (const item of oldNode.nestedArray || []) {
    const current = newRoot.lookup(item.fullName);
    assert.ok(current, item.fullName);
    if (item.fieldsArray) {
      for (const field of item.fieldsArray) {
        // protobuf.loadSync camel-cases field names; compare by authoritative field number.
        const match = current.fieldsById[field.id];
        assert.ok(match, field.fullName);
        assert.equal(match.type, field.type, field.fullName);
        assert.equal(match.repeated, field.repeated, field.fullName);
        assert.equal(match.map, field.map, field.fullName);
        assert.equal(match.keyType, field.keyType, field.fullName);
        assert.equal(match.name.replace(/_/g, '').toLowerCase(), field.name.replace(/_/g, '').toLowerCase());
        fields++;
      }
    }
    if (item.methodsArray) {
      for (const method of item.methodsArray) {
        const match = current.methods[method.name];
        assert.ok(match, method.fullName);
        for (const key of ['requestType', 'responseType', 'requestStream', 'responseStream']) assert.equal(match[key], method[key], method.fullName);
        methods++;
      }
    }
    if (item.values) for (const [name, number] of Object.entries(item.values)) assert.equal(current.values[name], number);
    compare(item);
  }
}
compare(oldRoot);
const options = {keepCase: true, longs: String, enums: String, defaults: true, oneofs: true};
const oldDefinition = loader.fromJSON(oldRoot.toJSON(), options);
const currentDefinition = loader.loadSync(path.join(root, 'src/main/proto/xlm-eco-api.proto'), options);
const loaded = grpc.loadPackageDefinition(currentDefinition);
assert.ok(loaded.XlmAdminService.service.getStatus);
assert.ok(loaded.XlmAdminService.service.updateProvider);
for (const method of ['syncChat', 'generateStructuredImage', 'getEmbedding']) {
  const previous = oldDefinition.XlmEcosystemService[method];
  const current = currentDefinition.XlmEcosystemService[method];
  assert.equal(current.path, previous.path);
  const input = method === 'syncChat' ? {client_id: 'client', prompt: 'text', provider: 'provider', model_name: 'model'} :
    method === 'getEmbedding' ? {client_id: 'client', text: 'text', provider: 'provider', model: 'model'} :
    {client_id: 'client', provider: 'provider', model: 'model', image: {mime_type: 'image/png', data: Buffer.from('image')}, instructions: 'describe', json_schema: '{}'};
  const oldDecoded = previous.requestDeserialize(current.requestSerialize(input));
  assert.equal(oldDecoded.client_id, 'client');
  if (method !== 'getEmbedding') assert.equal(oldDecoded.provider, 'provider');
  const newDecoded = current.requestDeserialize(previous.requestSerialize(oldDecoded));
  assert.equal(newDecoded.client_id, 'client');
}
const update = currentDefinition.XlmAdminService.updateProvider;
const decoded = update.requestDeserialize(update.requestSerialize({provider: 'p', enabled: false, expected_revision: '7', configuration: {timeout_seconds: '30'}}));
assert.equal(decoded.enabled, false);
assert.equal(decoded._enabled, 'enabled');
assert.equal(decoded.expected_revision, '7');
assert.equal(decoded.configuration.timeout_seconds, '30');
console.log(`PASS: Node runtime compatibility; ${fields} legacy fields and ${methods} legacy RPCs preserved; old/new serialization and Admin optional fields verified.`);
