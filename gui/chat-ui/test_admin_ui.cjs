'use strict';
// Behavioral controller tests: no browser or package dependencies required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
class Element {
    constructor(id) { this.id = id; this.value = ''; this.checked = false; this.textContent = ''; this.files = []; this.children = []; this.listeners = {}; this.disabled = false; }
    replaceChildren(...children) { this.children = children; this.value = children.length ? children[0].value : ''; }
    appendChild(child) { this.children.push(child); }
    addEventListener(event, handler) { this.listeners[event] = handler; }
}
class Document {
    constructor() { this.elements = new Map(); }
    getElementById(id) { if (!this.elements.has(id)) this.elements.set(id, new Element(id)); return this.elements.get(id); }
    createElement() { return new Element(); }
    querySelectorAll() { return [...this.elements.values()]; }
}
class Form { constructor() { this.values = new Map(); } append(key, value) { this.values.set(key, value); } get(key) { return this.values.get(key); } }
const context = {window: {XLM_ADMIN_TEST: true, setInterval() {}}, FormData: Form, console};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'static/admin.js'), 'utf8'), context);
const Controller = context.window.XlmAdminConsole;
const clone = value => JSON.parse(JSON.stringify(value));
const deferred = () => { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; };
function fixture() {
    const document = new Document(), calls = [];
    let revision = 10, failure = null, delay = null;
    const providers = ['first', 'second', 'third'].map(provider => ({provider, enabled: true, credential_present: provider !== 'third', configuration: {timeout_seconds: '30'}, capabilities: ['structured_image']}));
    const models = providers.flatMap(p => ['one', 'two'].map(model => ({provider: p.provider, model, display_name: model, enabled: true, capabilities: ['structured_image']})));
    let defaults = [{capability: 'structured_image', provider: 'first', model: 'one'}];
    const status = {inference: {state: 'ready'}, administration: {state: 'ready'}, chat: {state: 'ready'}, generation: 1};
    async function fetch(url, options = {}) {
        const body = options.body && typeof options.body === 'string' ? JSON.parse(options.body) : options.body;
        calls.push({url, body, options});
        if (delay && delay.url === url) { const selected = delay; delay = null; await selected.wait.promise; }
        if (failure && failure.url === url) { const selected = failure; failure = null; return {ok: false, json: async () => ({code: selected.code})}; }
        let result;
        if (url === '/console/status') result = status;
        else if (url === '/analyze_image') result = {success: true, provider: body.get('provider'), model: body.get('model')};
        else {
            const [route, query] = url.replace('/admin/api/', '').split('?'), args = new URLSearchParams(query);
            if (body) {
                const expected = body.expected_revision === undefined ? body.revision : body.expected_revision;
                if (String(expected) !== String(revision)) return {ok: false, json: async () => ({code: 'ABORTED'})};
                if (route === 'provider') Object.assign(providers.find(p => p.provider === body.provider), {enabled: body.enabled, configuration: body.configuration});
                if (route === 'model') { const existing = models.find(m => m.provider === body.model.provider && m.model === body.model.model); if (existing) Object.assign(existing, body.model); else models.push(body.model); }
                if (route === 'default') defaults = body.clear ? defaults.filter(d => d.capability !== body.selection.capability) : [...defaults.filter(d => d.capability !== body.selection.capability), body.selection];
                result = {revision: String(++revision)};
            } else if (route === 'providers') result = {providers: providers.map(p => ({...p, revision: String(revision)})), revision: String(revision)};
            else if (route === 'provider') result = {...providers.find(p => p.provider === args.get('provider')), revision: String(revision)};
            else if (route === 'models') result = {models: models.filter(m => m.provider === args.get('provider')), revision: String(revision)};
            else if (route === 'defaults') result = {defaults, revision: String(revision)};
            else if (route === 'status') result = {ready: true, revision: String(revision)};
        }
        return {ok: true, json: async () => clone(result)};
    }
    const ui = new Controller(document, fetch, 'synthetic-ui-csrf');
    ui.adminReady = true; ui.inferenceReady = true;
    const el = id => document.getElementById(id);
    el('capability').value = 'structured_image'; el('instructions').value = 'My instructions'; el('schema').value = '{"type":"object"}'; el('image').files = [{name: 'retained.png'}];
    return {ui, el, calls, providers, status, setFailure: (url, code) => { failure = {url, code}; }, setDelay: (url, wait) => { delay = {url, wait}; }};
}
async function chooseSecond(f) {
    await f.ui.refresh(); f.el('provider').value = 'second'; await f.ui.loadSelection();
    f.el('model').value = 'two'; f.ui.selectedModels.set('second', 'two'); f.ui.renderModel();
}
function assertRetained(f) {
    assert.equal(f.el('provider').value, 'second'); assert.equal(f.el('model').value, 'two');
    assert.equal(f.el('capability').value, 'structured_image'); assert.equal(f.el('useDefault').checked, true);
    assert.equal(f.el('instructions').value, 'My instructions'); assert.equal(f.el('schema').value, '{"type":"object"}'); assert.equal(f.el('image').files[0].name, 'retained.png');
}
(async () => {
    let passed = 0;
    async function test(name, action) { await action(); console.log('PASS ' + name); passed++; }
    await test('each save/reload retains non-first selection, test mode and upload', async () => {
        for (const operation of ['saveProvider', 'saveModel', 'setDefault', 'reload']) {
            const f = fixture(); await chooseSecond(f); f.el('useDefault').checked = true;
            if (operation === 'saveProvider') { f.el('config').value = '{"timeout_seconds":"31"}'; f.ui.editProvider(); }
            if (operation === 'saveModel') { f.el('displayName').value = 'Edited'; f.ui.editModel(); }
            await f.ui[operation](false); assert.equal(f.el('error').textContent, ''); assertRetained(f);
            assert.equal(f.calls.filter(c => c.options.method === 'POST').length, 1);
            const payload = f.calls.find(c => c.options.method === 'POST').body;
            assert.equal(typeof (payload.expected_revision === undefined ? payload.revision : payload.expected_revision), 'number');
        }
    });
    await test('unrelated dirty edits and their revision survive another save and conflict', async () => {
        const f = fixture(); await chooseSecond(f);
        f.el('displayName').value = 'Keep my draft'; f.ui.editModel();
        const revision = f.ui.modelDrafts.get(f.ui.modelKey()).revision;
        f.el('config').value = '{"timeout_seconds":"32"}'; f.ui.editProvider(); await f.ui.saveProvider();
        assert.equal(f.el('displayName').value, 'Keep my draft'); assert.equal(f.ui.modelDrafts.get(f.ui.modelKey()).revision, revision);
        await f.ui.saveModel(); assert.equal(f.el('error').textContent, 'ABORTED'); assert.equal(f.el('displayName').value, 'Keep my draft');
        assert.equal(f.ui.modelDrafts.get(f.ui.modelKey()).revision, revision);
        assert.equal(f.calls.filter(c => c.url === '/admin/api/model' && c.body).length, 1);
    });
    await test('failed default mutation and failed readback never optimistically change saved display', async () => {
        for (const url of ['/admin/api/default', '/admin/api/defaults']) {
            const f = fixture(); await chooseSecond(f); const before = f.el('defaults').textContent;
            f.ui.editDefault(); f.setFailure(url, 'UNAVAILABLE'); await f.ui.setDefault(false);
            assert.equal(f.el('defaults').textContent, before); assert.equal(f.ui.defaultRevision, '10'); assert.equal(f.ui.defaultDirty, true);
        }
    });
    await test('saved default target and explicit override generate different request fields', async () => {
        const f = fixture(); await chooseSecond(f); f.ui.renderTargets();
        assert.match(f.el('effectiveTarget').textContent, /Explicit override: second \/ two/);
        await f.ui.testImage(); let call = f.calls.at(-1); assert.equal(call.body.get('provider'), 'second'); assert.equal(call.body.get('model'), 'two');
        assert.equal(call.options.headers['X-XLM-UI-Token'], 'synthetic-ui-csrf');
        f.el('useDefault').checked = true; f.ui.renderTargets(); assert.match(f.el('effectiveTarget').textContent, /Saved default: first \/ one/);
        await f.ui.testImage(); call = f.calls.at(-1); assert.equal(call.body.get('provider'), ''); assert.equal(call.body.get('model'), '');
    });
    await test('stale provider response cannot overwrite a later selection', async () => {
        const f = fixture(); await f.ui.refresh(); const wait = deferred();
        f.setDelay('/admin/api/provider?provider=second', wait); f.el('provider').value = 'second'; const old = f.ui.loadSelection();
        f.el('provider').value = 'third'; await f.ui.loadSelection(); wait.resolve(); await old;
        assert.equal(f.el('provider').value, 'third'); assert.match(f.el('providerDetails').textContent, /third/);
        assert.match(f.el('credentialState').textContent, /Unconfigured — credential required/);
    });
    await test('missing credentials are labeled and cannot accidentally submit image test', async () => {
        const f = fixture(); await f.ui.refresh(); f.el('provider').value = 'third'; await f.ui.loadSelection();
        const before = f.calls.length; await f.ui.testImage(); assert.equal(f.calls.length, before);
        assert.equal(f.el('testImage').disabled, true); assert.equal(f.el('saveProvider').disabled, false); assert.equal(f.el('reload').disabled, false);
    });
    await test('pending mutation disables controls and rejects overlapping writes', async () => {
        const f = fixture(); await chooseSecond(f); const wait = deferred(); f.setDelay('/admin/api/provider', wait);
        const saving = f.ui.saveProvider(); assert.equal(f.el('model').disabled, true); assert.equal(f.el('image').disabled, true);
        await f.ui.saveModel(); assert.equal(f.calls.filter(c => c.url === '/admin/api/model' && c.body).length, 0);
        wait.resolve(); await saving; assert.equal(f.el('model').disabled, false);
    });
    await test('status heartbeat preserves forms and recovery only reads status/defaults', async () => {
        const f = fixture(); await chooseSecond(f); f.el('config').value = '{"timeout_seconds":"99"}'; f.ui.editProvider(); f.ui.editDefault();
        const oldRevision = f.ui.providerDrafts.get('second').revision;
        await f.ui.pollStatus(); f.calls.length = 0; await f.ui.pollStatus();
        assert.deepEqual(f.calls.map(c => c.url), ['/console/status']);
        f.status.generation++; await f.ui.pollStatus();
        assert.equal(f.el('config').value, '{"timeout_seconds":"99"}'); assert.equal(f.ui.providerDrafts.get('second').revision, oldRevision);
        assert.equal(f.ui.defaultRevision, '10'); assert.equal(f.el('provider').value, 'second');
        assert.equal(f.calls.some(c => c.url === '/admin/api/providers'), false);
    });
    await test('initial unavailable administration loads once on recovery', async () => {
        const f = fixture(); f.status.administration.state = 'unconfigured'; f.ui.adminReady = false;
        await f.ui.pollStatus(); assert.equal(f.ui.loaded, false);
        f.status.administration.state = 'ready'; await f.ui.pollStatus(); assert.equal(f.ui.loaded, true);
        f.calls.length = 0; await f.ui.pollStatus(); assert.deepEqual(f.calls.map(c => c.url), ['/console/status']);
    });
    await test('explicit discard reloads stale drafts while preserving selections and test inputs', async () => {
        const f = fixture(); await chooseSecond(f); f.el('useDefault').checked = true;
        f.el('config').value = '{"timeout_seconds":"99"}'; f.ui.editProvider();
        f.el('displayName').value = 'discard this'; f.ui.editModel(); await f.ui.reload();
        assert.equal(f.ui.providerDrafts.get('second').revision, '10');
        await f.ui.discardDrafts(); assertRetained(f);
        assert.equal(f.ui.providerDrafts.get('second').revision, '11'); assert.equal(f.el('displayName').value, 'two');
        assert.equal(f.ui.providerDrafts.get('second').dirty, false);
    });
    await test('new-model draft survives refresh then selects newly saved model', async () => {
        const f = fixture(); await chooseSecond(f); f.ui.newModel();
        f.el('modelId').value = 'new-model'; f.el('displayName').value = 'New model'; f.ui.editModel();
        await f.ui.refresh(); assert.equal(f.el('model').value, ''); assert.equal(f.el('modelId').value, 'new-model');
        await f.ui.saveModel(); assert.equal(f.el('error').textContent, ''); assert.equal(f.el('provider').value, 'second');
        assert.equal(f.el('model').value, 'new-model'); assert.equal(f.el('modelId').value, 'new-model');
    });
    await test('delayed old defaults read cannot overwrite newer saved readback', async () => {
        const f = fixture(); await chooseSecond(f); const wait = deferred(), original = f.ui.api.bind(f.ui); let delay = true;
        f.ui.api = async (route, body) => { const value = await original(route, body); if (route === 'defaults' && delay) { delay = false; await wait.promise; } return value; };
        const oldRead = f.ui.readDefaultsAndStatus(); await Promise.resolve(); await f.ui.setDefault(false);
        wait.resolve(); await oldRead;
        assert.equal(f.ui.savedDefaults[0].provider, 'second'); assert.equal(f.ui.savedDefaults[0].model, 'two');
    });
    await test('revision outside safe integer range fails without making a write', async () => {
        const f = fixture(); await chooseSecond(f); f.ui.providerDrafts.get('second').revision = '9007199254740993';
        await f.ui.saveProvider(); assert.match(f.el('error').textContent, /cannot be represented safely/);
        assert.equal(f.calls.filter(c => c.options.method === 'POST').length, 0);
    });
    await test('pending provider switch disables editors and rejects cross-provider edit events', async () => {
        const f = fixture(); await f.ui.refresh(); const wait = deferred();
        f.setDelay('/admin/api/provider?provider=second', wait); f.el('provider').value = 'second'; const switching = f.ui.loadSelection();
        for (const id of ['model', 'providerEnabled', 'config', 'modelId', 'displayName', 'capabilities', 'modelEnabled', 'saveProvider', 'saveModel']) assert.equal(f.el(id).disabled, true, id);
        assert.equal(f.el('provider').disabled, false);
        f.el('config').value = '{"timeout_seconds":"999"}'; f.ui.editProvider();
        f.el('displayName').value = 'old editor injected into new provider'; f.ui.editModel();
        assert.equal(f.ui.providerDrafts.get('second').dirty, false);
        assert.equal(f.ui.modelDrafts.has(f.ui.modelKey('second', 'one')), false);
        wait.resolve(); await switching;
        assert.equal(f.el('displayName').value, 'one'); assert.equal(JSON.parse(f.el('config').value).timeout_seconds, '30');
        assert.equal(f.ui.renderedProvider, 'second'); assert.equal(f.el('config').disabled, false);
    });
    await test('failed provider load keeps old editors blocked until successful navigation retry', async () => {
        const f = fixture(); await f.ui.refresh(); f.el('provider').value = 'second';
        f.setFailure('/admin/api/provider?provider=second', 'UNAVAILABLE'); await f.ui.guarded(() => f.ui.loadSelection());
        assert.equal(f.ui.selectionReady, false); assert.equal(f.ui.renderedProvider, 'first');
        for (const id of ['model', 'providerEnabled', 'config', 'modelId', 'displayName', 'capabilities', 'modelEnabled', 'saveProvider', 'saveModel', 'setDefault', 'testImage']) assert.equal(f.el(id).disabled, true, id);
        assert.equal(f.el('provider').disabled, false); assert.equal(f.el('refresh').disabled, false);
        f.el('config').value = '{"timeout_seconds":"888"}'; f.ui.editProvider();
        f.el('displayName').value = 'stale editor'; f.ui.editModel();
        const writesBefore = f.calls.filter(c => c.options.method === 'POST').length;
        await f.ui.saveProvider(); await f.ui.saveModel();
        assert.equal(f.calls.filter(c => c.options.method === 'POST').length, writesBefore);
        assert.equal(f.ui.providerDrafts.get('second').dirty, false);
        await f.ui.loadSelection(); assert.equal(f.ui.selectionReady, true); assert.equal(f.ui.renderedProvider, 'second');
        assert.equal(f.el('config').disabled, false); assert.equal(f.el('displayName').value, 'one');
        assert.equal(JSON.parse(f.el('config').value).timeout_seconds, '30');
    });
    console.log(`${passed} controller behavioral tests passed`);
})().catch(error => { console.error(error); process.exitCode = 1; });
