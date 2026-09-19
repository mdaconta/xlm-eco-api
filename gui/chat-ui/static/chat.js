'use strict';
class XlmChat {
    constructor(document, fetcher, socket, config, urls = URL) {
        this.doc = document; this.fetch = fetcher; this.socket = socket; this.config = config; this.urls = urls;
        this.catalog = []; this.entries = new Map(); this.blobs = new Set(); this.file = null; this.preview = null; this.dismissed = ''; this.streamSession = null; this.loadedCatalog = false;
        const on = (id, event, fn) => this.el(id).addEventListener(event, fn);
        on('providerSelect', 'change', () => this.models()); on('modelSelect', 'change', () => this.validate());
        on('refreshModels', 'click', () => this.load()); on('generateMode', 'change', () => this.validate());
        on('attachButton', 'click', () => this.el('imageInput').click());
        on('imageInput', 'change', () => this.attach(this.el('imageInput').files[0]));
        on('removeImage', 'click', () => this.attach(null)); on('messageInput', 'input', () => this.suggest());
        on('acceptSuggestion', 'click', () => { this.el('generateMode').checked = true; this.validate(); });
        on('dismissSuggestion', 'click', () => { this.dismissed = this.el('messageInput').value; this.suggest(); });
        on('sendButton', 'click', () => this.send());
        socket.on('stream_session', data => { this.streamSession = data.token; });
        socket.on('disconnect', () => { this.streamSession = null; for (const entry of this.entries.values()) if (entry.pending) { entry.output.textContent += '\nConnection lost; submit a new request to retry.'; entry.pending = false; } });
        socket.on('chat_response', data => { const entry = this.entries.get(data.request_id); if (entry && entry.pending) entry.output.textContent += data.message; });
        socket.on('chat_title', data => { const entry = this.entries.get(data.request_id); if (entry) entry.title.textContent = data.title; });
        socket.on('chat_done', data => { const entry = this.entries.get(data.request_id); if (entry) entry.pending = false; });
        socket.on('chat_error', data => { const entry = this.entries.get(data.request_id); if (entry && entry.pending) { entry.output.textContent += '\nXLM text request failed.'; entry.pending = false; } });
    }
    el(id) { return this.doc.getElementById(id); }
    option(value, label) { const e = this.doc.createElement('option'); e.value = value; e.textContent = label; return e; }
    async load() {
        try {
            const response = await this.fetch('/models'); const data = await response.json();
            if (!response.ok) throw new Error();
            // Preserve selection made while this discovery request was in flight.
            const previous = this.loadedCatalog ? this.el('providerSelect').value : this.config.provider;
            const previousModel = this.loadedCatalog ? this.el('modelSelect').value : this.config.model;
            this.catalog = data.models || [];
            const providers = [...new Set(this.catalog.map(m => m.provider))];
            this.el('providerSelect').replaceChildren(...providers.map(p => this.option(p, p)));
            this.el('providerSelect').value = providers.includes(previous) ? previous : '';
            this.models(previousModel); this.loadedCatalog = true;
        } catch (_) { this.catalog = []; this.el('capabilityInfo').textContent = 'Model catalog unavailable. Refresh when XLM is connected.'; this.el('sendButton').disabled = true; }
    }
    models(previous) {
        const selected = previous === undefined ? this.el('modelSelect').value : previous;
        const models = this.catalog.filter(m => m.provider === this.el('providerSelect').value);
        this.el('modelSelect').replaceChildren(...models.map(m => this.option(m.model, `${m.display_name || m.model}${m.enabled ? '' : ' (disabled)'}`)));
        this.el('modelSelect').value = models.some(m => m.model === selected) ? selected : '';
        this.validate();
    }
    selected() { return this.catalog.find(m => m.provider === this.el('providerSelect').value && m.model === this.el('modelSelect').value); }
    validate() {
        const model = this.selected(); const generation = this.el('generateMode').checked;
        const capability = generation ? 'image_generation' : this.file ? 'structured_image' : 'chat';
        const reason = generation && this.file ? 'Remove the attachment to generate an image. Image editing is not supported.' : !model || !model.enabled ? 'Select an enabled model.' : !model.capabilities.includes(capability) ? `Selected model does not support ${capability}. Select a compatible model.` : '';
        this.el('capabilityInfo').textContent = reason || `Ready for ${capability} · ${model.provider} / ${model.model}`;
        this.el('sendButton').disabled = Boolean(reason); this.suggest(); return !reason;
    }
    suggest() {
        const prompt = this.el('messageInput').value;
        this.el('suggestion').hidden = this.el('generateMode').checked || Boolean(this.file) || prompt === this.dismissed || !/^\s*(?:please\s+)?(?:(?:draw|illustrate|visualize)\s+\S|(?:generate|create|render|make)\s+(?:(?:me|an?|the)\s+)*(?:image|picture|illustration|photo|painting|drawing|diagram|visualization)\b)/i.test(prompt);
    }
    attach(file) {
        this.el('feedback').textContent = '';
        if (file && (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || !file.size || file.size > 3 * 1024 * 1024)) { this.attach(null); this.el('feedback').textContent = 'Attachment cleared. Choose a nonempty PNG, JPEG or WebP image up to 3 MiB.'; return; }
        if (this.preview) this.urls.revokeObjectURL(this.preview);
        this.preview = null; this.file = file || null;
        this.el('attachment').hidden = !file; this.el('advanced').hidden = !file;
        if (file) { this.preview = this.urls.createObjectURL(file); this.el('imagePreview').src = this.preview; this.el('fileDetails').textContent = `${file.name} · ${file.size} bytes`; }
        else { this.el('imagePreview').removeAttribute('src'); this.el('imageInput').value = ''; }
        this.validate();
    }
    entry(prompt, model) {
        const id = crypto.randomUUID(), article = this.doc.createElement('article'), title = this.doc.createElement('h2'), input = this.doc.createElement('p'), output = this.doc.createElement('pre'), metadata = this.doc.createElement('small');
        input.textContent = prompt; metadata.textContent = `${model.provider} / ${model.model}`; article.append(title, input, output, metadata); this.el('chatBox').appendChild(article);
        const entry = {id, article, title, output, metadata, pending: false}; this.entries.set(id, entry); return entry;
    }
    async send() {
        this.el('feedback').textContent = '';
        const prompt = this.el('messageInput').value.trim();
        if (!this.validate() || !prompt) return;
        if (new TextEncoder().encode(prompt).length > 65536) { this.el('feedback').textContent = 'Prompt exceeds 64 KiB.'; return; }
        const model = this.selected(), generation = this.el('generateMode').checked, file = this.file;
        let body, route, headers = {'X-XLM-UI-Token': this.config.token};
        if (file) {
            const schema = this.el('schemaInput').value;
            try { const parsed = JSON.parse(schema); if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || new TextEncoder().encode(schema).length > 65536) throw new Error(); }
            catch (_) { this.el('feedback').textContent = 'JSON Schema must be an object, limited to 64 KiB.'; return; }
            body = new FormData(); for (const [key, value] of Object.entries({image:file, instructions:prompt, json_schema:schema, provider:model.provider, model:model.model})) body.append(key,value);
            route = '/analyze_image';
        } else {
            if (!generation && !this.streamSession) { this.el('feedback').textContent = 'Wait for the chat connection, then retry.'; return; }
            route = generation ? '/generate_image' : '/send_message'; headers['Content-Type'] = 'application/json';
        }
        const entry = this.entry(prompt, model); entry.pending = !generation && !file;
        if (!file) body = JSON.stringify({message:prompt, provider:model.provider, model:model.model, request_id:entry.id, stream_session:this.streamSession});
        this.el('messageInput').value = ''; this.suggest(); entry.output.textContent = entry.pending ? '' : 'Working…';
        try {
            const response = await this.fetch(route, {method:'POST', headers, body}); const result = await response.json();
            if (!response.ok) throw new Error();
            if (route === '/send_message') return;
            entry.metadata.textContent = `${result.provider} / ${result.model} · ${result.latency_ms} ms${result.request_id ? ' · ' + result.request_id : ''}`;
            if (!result.success) { entry.output.textContent = `Request failed: ${result.error_code || 'UNKNOWN'} · Retryable: ${Boolean(result.retryable)}`; return; }
            if (generation) {
                if (result.output_type !== 'image' || !['image/png','image/jpeg'].includes(result.mime_type) || !result.image_base64 || result.image_base64.length > 4194304) throw new Error();
                const bytes = Uint8Array.from(atob(result.image_base64), c => c.charCodeAt(0));
                const url = this.urls.createObjectURL(new Blob([bytes], {type:result.mime_type})); this.blobs.add(url);
                const image = this.doc.createElement('img'); image.alt = prompt;
                image.addEventListener('error', () => { image.remove(); this.urls.revokeObjectURL(url); this.blobs.delete(url); entry.output.textContent = 'Generated image could not be displayed. Submit a new request to retry.'; });
                image.src = url; entry.output.textContent = ''; entry.article.appendChild(image);
            } else entry.output.textContent = JSON.stringify(result.structured_result, null, 2);
        } catch (_) { entry.pending = false; entry.output.textContent = 'Request failed. Check the connection and submit a new request to retry.'; }
    }
    dispose() { if (this.preview) this.urls.revokeObjectURL(this.preview); for (const url of this.blobs) this.urls.revokeObjectURL(url); this.blobs.clear(); }
}
window.XlmChat = XlmChat;
if (!window.XLM_CHAT_TEST) { const chat = new XlmChat(document, window.fetch.bind(window), io({auth:{token:window.XLM_CHAT_CONFIG.token}}), window.XLM_CHAT_CONFIG); chat.load(); window.addEventListener('pagehide', () => chat.dispose()); }
