/* Browser state only. Registry validation and persistence remain authoritative in XLM. */
(function (global) {
    'use strict';
    class AdminConsole {
        constructor(document, fetch, token) {
            this.document = document; this.fetch = fetch; this.token = token;
            this.providers = []; this.catalogs = new Map(); this.providerDrafts = new Map(); this.modelDrafts = new Map();
            this.savedDefaults = []; this.revision = '0'; this.defaultRevision = '0'; this.defaultDirty = false;
            this.selectedModels = new Map(); this.selectionLoading = false; this.renderedProvider = null; this.selectionReady = false; this.savedDefaultsRevision = '0';
            this.pending = false; this.selectionEpoch = 0; this.refreshEpoch = 0;
            this.loaded = false; this.loading = false; this.adminReady = false; this.inferenceReady = false;
            this.polling = false; this.connectionGeneration = null;
        }
        el(id) { return this.document.getElementById(id); }
        show(id, value) { this.el(id).textContent = JSON.stringify(value, null, 2); }
        providerId() { return this.el('provider').value; }
        modelId() { return this.el('model').value; }
        modelKey(provider = this.providerId(), model = this.modelId()) { return JSON.stringify([provider, model]); }
        wireRevision(revision) {
            const value = Number(revision);
            if (!Number.isSafeInteger(value) || value < 1) throw new Error('Registry revision cannot be represented safely');
            return value;
        }
        async api(path, body) {
            const response = await this.fetch('/admin/api/' + path, body === undefined ? {} : {
                method: 'POST', headers: {'Content-Type': 'application/json', 'X-XLM-UI-Token': this.token}, body: JSON.stringify(body)
            });
            const result = await response.json();
            if (!response.ok) throw new Error(result.code || result.error || 'Administrative request failed');
            return result;
        }
        async guarded(action) {
            this.el('error').textContent = '';
            try { return await action(); }
            catch (error) { this.el('error').textContent = error.message; return false; }
        }
        options(id, items, key, chosen, label = item => item[key]) {
            const select = this.el(id);
            select.replaceChildren(...items.map(item => {
                const option = this.document.createElement('option'); option.value = item[key]; option.textContent = label(item); return option;
            }));
            // An unavailable selection stays visible instead of silently changing provider/model.
            if (chosen !== undefined && !items.some(item => item[key] === chosen)) {
                const option = this.document.createElement('option'); option.value = chosen; option.textContent = chosen ? chosen + ' (not currently listed)' : '(New model draft)'; select.appendChild(option);
            }
            if (chosen !== undefined) select.value = chosen;
        }
        credentialLabel(provider) {
            if (provider.provider !== 'ollama' && !provider.credential_present) return 'Unconfigured — credential required';
            return provider.enabled ? 'Enabled' : 'Disabled';
        }
        updateProviderDraft(provider) {
            const existing = this.providerDrafts.get(provider.provider);
            if (!existing || !existing.dirty) this.providerDrafts.set(provider.provider, {
                enabled: provider.enabled, config: JSON.stringify(provider.configuration, null, 2), revision: provider.revision, dirty: false
            });
        }
        updateModelDrafts(provider, catalog) {
            for (const model of catalog.models) {
                const key = this.modelKey(provider, model.model), existing = this.modelDrafts.get(key);
                if (!existing || !existing.dirty) this.modelDrafts.set(key, {
                    model: model.model, displayName: model.display_name, capabilities: model.capabilities.join(','), enabled: model.enabled,
                    revision: catalog.revision, dirty: false
                });
            }
        }
        renderProvider() {
            const provider = this.providers.find(p => p.provider === this.providerId()), draft = this.providerDrafts.get(this.providerId());
            if (provider) { this.show('providerDetails', provider); this.el('credentialState').textContent = this.credentialLabel(provider); }
            if (draft) {
                this.el('providerEnabled').checked = draft.enabled; this.el('config').value = draft.config;
                this.el('providerDraft').textContent = (draft.dirty ? 'Unsaved edits; base revision ' : 'Saved revision ') + draft.revision;
            }
        }
        renderModel() {
            const draft = this.modelDrafts.get(this.modelKey());
            if (draft) {
                this.el('modelId').value = draft.model; this.el('displayName').value = draft.displayName;
                this.el('capabilities').value = draft.capabilities; this.el('modelEnabled').checked = draft.enabled;
                this.el('modelDraft').textContent = (draft.dirty ? 'Unsaved edits; base revision ' : 'Saved revision ') + draft.revision;
            }
            this.renderTargets();
        }
        editProvider() {
            if (!this.selectionReady || this.selectionLoading || this.renderedProvider !== this.providerId()) return;
            const draft = this.providerDrafts.get(this.providerId()); if (!draft) return;
            Object.assign(draft, {enabled: this.el('providerEnabled').checked, config: this.el('config').value, dirty: true});
            this.el('providerDraft').textContent = 'Unsaved edits; base revision ' + draft.revision;
        }
        editModel() {
            if (!this.selectionReady || this.selectionLoading || this.renderedProvider !== this.providerId()) return;
            const draft = this.modelDrafts.get(this.modelKey()); if (!draft) return;
            Object.assign(draft, {model: this.el('modelId').value, displayName: this.el('displayName').value,
                capabilities: this.el('capabilities').value, enabled: this.el('modelEnabled').checked, dirty: true});
            this.el('modelDraft').textContent = 'Unsaved edits; base revision ' + draft.revision;
        }
        editDefault() { this.defaultDirty = true; this.renderTargets(); }
        async loadSelection() {
            const provider = this.providerId(), epoch = ++this.selectionEpoch;
            this.selectionLoading = true; this.selectionReady = false; this.setControls();
            try {
            const [details, catalog] = await Promise.all([
                this.api('provider?provider=' + encodeURIComponent(provider)), this.api('models?provider=' + encodeURIComponent(provider))
            ]);
            if (epoch !== this.selectionEpoch || provider !== this.providerId()) return;
            const index = this.providers.findIndex(p => p.provider === provider); if (index >= 0) this.providers[index] = details;
            this.updateProviderDraft(details); this.updateModelDrafts(provider, catalog); this.catalogs.set(provider, catalog);
            // Remember each provider's selected model when navigating away and back.
            const chosen = this.selectedModels && this.selectedModels.get(provider);
            this.options('model', catalog.models, 'model', chosen);
            if (!this.selectedModels) this.selectedModels = new Map(); this.selectedModels.set(provider, this.modelId());
            this.renderedProvider = provider; this.selectionReady = true;
            this.show('models', catalog.models); this.renderProvider(); this.renderModel();
            } finally { if (epoch === this.selectionEpoch) { this.selectionLoading = false; this.setControls(); } }
        }
        acceptDefaults(defaults) {
            // A delayed recovery/read cannot replace a newer committed readback.
            if (BigInt(defaults.revision) < BigInt(this.savedDefaultsRevision)) return;
            this.savedDefaults = defaults.defaults; this.savedDefaultsRevision = defaults.revision;
            if (!this.defaultDirty) this.defaultRevision = defaults.revision;
            this.show('defaults', this.savedDefaults);
        }
        async readDefaultsAndStatus() {
            const [defaults, status] = await Promise.all([this.api('defaults'), this.api('status')]);
            this.acceptDefaults(defaults); this.revision = status.revision;
            this.show('status', status);
            this.el('revision').textContent = 'Last observed registry revision ' + this.revision;
            this.renderTargets();
        }
        async refresh() {
            const epoch = ++this.refreshEpoch;
            const providers = await this.api('providers');
            if (epoch !== this.refreshEpoch) return;
            this.providers = providers.providers; this.revision = providers.revision;
            for (const provider of this.providers) this.updateProviderDraft(provider);
            this.options('provider', this.providers, 'provider', this.providerId() || undefined, p => p.provider + ' — ' + this.credentialLabel(p));
            await Promise.all([this.loadSelection(), this.readDefaultsAndStatus()]); this.loaded = true;
        }
        effectiveTarget() {
            return this.el('useDefault').checked ? this.savedDefaults.find(d => d.capability === 'structured_image') :
                {provider: this.providerId(), model: this.modelId()};
        }
        testBlockReason() {
            if (this.selectionLoading) return 'Loading selected provider and models';
            if (!this.selectionReady || this.renderedProvider !== this.providerId()) return 'Selected provider has not loaded; select it again or refresh to retry';
            const target = this.effectiveTarget();
            if (!target || !target.provider || !target.model) return 'No structured_image target selected';
            const provider = this.providers.find(p => p.provider === target.provider);
            if (!provider) return 'Target provider is not loaded';
            if (provider.provider !== 'ollama' && !provider.credential_present) return 'Unconfigured — credential required';
            if (!provider.enabled) return 'Provider is disabled';
            if (!this.inferenceReady) return 'Inference connection is not ready';
            return '';
        }
        renderTargets() {
            this.el('defaultTarget').textContent = 'Draft: ' + this.el('capability').value + ' → ' + this.providerId() + ' / ' + this.modelId() + ' (base revision ' + this.defaultRevision + ')';
            const target = this.effectiveTarget(), reason = this.testBlockReason();
            this.el('effectiveTarget').textContent = (this.el('useDefault').checked ? 'Saved default: ' : 'Explicit override: ') +
                (target ? target.provider + ' / ' + target.model : 'none configured') + (reason ? ' — ' + reason : '');
            this.setControls();
        }
        setControls() {
            for (const control of this.document.querySelectorAll('input,select,textarea,button')) control.disabled = this.pending;
            const unavailableSelection = this.selectionLoading || !this.selectionReady || this.renderedProvider !== this.providerId();
            for (const id of ['model', 'providerEnabled', 'config', 'modelId', 'displayName', 'capabilities', 'modelEnabled']) this.el(id).disabled = this.pending || unavailableSelection;
            for (const id of ['saveProvider', 'saveModel', 'newModel', 'setDefault', 'clearDefault', 'reload', 'refresh', 'discardDrafts']) this.el(id).disabled = this.pending || unavailableSelection || !this.adminReady;
            // Reads/navigation remain available to recover a failed selection load.
            this.el('refresh').disabled = this.pending || !this.adminReady;
            this.el('discardDrafts').disabled = this.pending || !this.adminReady;
            this.el('testImage').disabled = this.pending || !!this.testBlockReason();
        }
        async mutate(action, requireSelection = true) {
            if (this.pending || this.selectionLoading || !this.adminReady || (requireSelection && (!this.selectionReady || this.renderedProvider !== this.providerId()))) return false;
            this.pending = true; this.setControls();
            try { return await this.guarded(action); }
            finally { this.pending = false; this.setControls(); }
        }
        saveProvider() {
            return this.mutate(async () => {
                const provider = this.providerId(), draft = this.providerDrafts.get(provider);
                await this.api('provider', {provider, enabled: draft.enabled, configuration: JSON.parse(draft.config), expected_revision: this.wireRevision(draft.revision)});
                // Only this saved draft is eligible for readback replacement; unrelated dirty drafts retain base revisions.
                const details = await this.api('provider?provider=' + encodeURIComponent(provider));
                draft.dirty = false; this.updateProviderDraft(details);
                await this.refresh();
            });
        }
        saveModel() {
            return this.mutate(async () => {
                const provider = this.providerId(), draft = this.modelDrafts.get(this.modelKey());
                await this.api('model', {model: {provider, model: draft.model, display_name: draft.displayName, enabled: draft.enabled,
                    capabilities: draft.capabilities.split(',').map(s => s.trim()).filter(Boolean)}, expected_revision: this.wireRevision(draft.revision)});
                const catalog = await this.api('models?provider=' + encodeURIComponent(provider));
                if (!catalog.models.some(model => model.model === draft.model)) throw new Error('Saved model readback unavailable');
                draft.dirty = false; this.updateModelDrafts(provider, catalog);
                this.selectedModels.set(provider, draft.model);
                await this.refresh();
            });
        }
        setDefault(clear) {
            return this.mutate(async () => {
                await this.api('default', {selection: {capability: this.el('capability').value, provider: clear ? '' : this.providerId(), model: clear ? '' : this.modelId()},
                    clear, expected_revision: this.wireRevision(this.defaultRevision)});
                // Readback is required before committed defaults or their base revision change.
                const defaults = await this.api('defaults');
                this.defaultDirty = false; this.acceptDefaults(defaults);
                await this.refresh();
            });
        }
        newModel() {
            if (this.pending || this.selectionLoading || !this.adminReady || !this.selectionReady || this.renderedProvider !== this.providerId()) return;
            const catalog = this.catalogs.get(this.providerId()); if (!catalog) return;
            const key = this.modelKey(this.providerId(), '');
            if (!this.modelDrafts.has(key)) this.modelDrafts.set(key, {model: '', displayName: '', capabilities: 'structured_image', enabled: true, revision: catalog.revision, dirty: true});
            const option = this.document.createElement('option'); option.value = ''; option.textContent = '(New model draft)';
            this.el('model').appendChild(option); this.el('model').value = ''; this.selectedModels.set(this.providerId(), '');
            this.editDefault(); this.renderModel();
        }
        discardDrafts() {
            return this.mutate(async () => {
                this.providerDrafts.clear(); this.modelDrafts.clear(); this.defaultDirty = false;
                if (this.selectedModels.get(this.providerId()) === '') this.selectedModels.delete(this.providerId());
                await this.refresh();
            }, false);
        }
        reload() { return this.mutate(async () => { await this.api('reload', {revision: this.wireRevision(this.revision)}); await this.refresh(); }); }
        async testImage() {
            if (this.pending) return false;
            const reason = this.testBlockReason(); if (reason) { this.el('error').textContent = reason; return false; }
            return this.guarded(async () => {
                const form = new FormData(); form.append('image', this.el('image').files[0]);
                form.append('instructions', this.el('instructions').value); form.append('json_schema', this.el('schema').value);
                form.append('provider', this.el('useDefault').checked ? '' : this.providerId()); form.append('model', this.el('useDefault').checked ? '' : this.modelId());
                const response = await this.fetch('/analyze_image', {method: 'POST', headers: {'X-XLM-UI-Token': this.token}, body: form});
                const result = await response.json();
                this.show('result', {requested_provider: form.get('provider'), requested_model: form.get('model'), ...result});
            });
        }
        async pollStatus() {
            if (this.polling) return; this.polling = true;
            try {
                const response = await this.fetch('/console/status'); if (!response.ok) throw new Error('Status unavailable');
                const status = await response.json(), wasReady = this.adminReady;
                this.adminReady = status.administration.state === 'ready'; this.inferenceReady = status.inference.state === 'ready';
                this.el('connection').textContent = 'Inference: ' + status.inference.state + ' | Administration: ' + status.administration.state + ' | Chat preference: ' + status.chat.state;
                this.renderTargets();
                if (this.adminReady && !this.loaded && !this.loading) {
                    this.loading = true; try { await this.guarded(() => this.refresh()); } finally { this.loading = false; }
                } else if (this.adminReady && !this.pending && (!wasReady || (this.connectionGeneration !== null && this.connectionGeneration !== status.generation))) {
                    // Recovery reads only committed default/status displays, never editor drafts.
                    await this.guarded(() => this.readDefaultsAndStatus());
                }
                this.connectionGeneration = status.generation;
            } catch (_) {
                this.adminReady = false; this.inferenceReady = false;
                this.el('connection').textContent = 'Console connection unavailable; retrying automatically'; this.setControls();
            } finally { this.polling = false; }
        }
        bind() {
            for (const [id, handler] of Object.entries({discardDrafts: () => this.discardDrafts(), newModel: () => this.newModel(), refresh: () => this.guarded(() => this.refresh()), saveProvider: () => this.saveProvider(), saveModel: () => this.saveModel(),
                setDefault: () => this.setDefault(false), clearDefault: () => this.setDefault(true), reload: () => this.reload(), testImage: () => this.testImage()})) this.el(id).addEventListener('click', handler);
            this.el('provider').addEventListener('change', () => { this.editDefault(); this.guarded(() => this.loadSelection()); });
            this.el('model').addEventListener('change', () => { this.selectedModels.set(this.providerId(), this.modelId()); this.editDefault(); this.renderModel(); });
            this.el('capability').addEventListener('change', () => this.editDefault()); this.el('useDefault').addEventListener('change', () => this.renderTargets());
            for (const id of ['providerEnabled', 'config']) this.el(id).addEventListener('input', () => this.editProvider());
            for (const id of ['modelId', 'displayName', 'capabilities', 'modelEnabled']) this.el(id).addEventListener('input', () => this.editModel());
            this.setControls(); this.pollStatus(); this.timer = global.setInterval(() => this.pollStatus(), 2000);
        }
    }
    global.XlmAdminConsole = AdminConsole;
    if (!global.XLM_ADMIN_TEST) {
        const config = JSON.parse(global.document.getElementById('console-config').textContent);
        new AdminConsole(global.document, global.fetch.bind(global), config.ui_token).bind();
    }
})(window);
