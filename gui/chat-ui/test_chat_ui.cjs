'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path');
class Element {
 constructor(tag){this.tag=tag;this.value='';this.checked=false;this.hidden=false;this.disabled=false;this.textContent='';this.children=[];this.listeners={};}
 addEventListener(name,fn){this.listeners[name]=fn;}
 replaceChildren(...children){this.children=children;this.value=children.length?children[0].value:'';}
 append(...children){this.children.push(...children);}
 appendChild(child){this.append(child);}
 remove(){this.removed=true;}
 removeAttribute(name){delete this[name];}
 click(){if(this.listeners.click)this.listeners.click();}
}
class Document{constructor(){this.elements=new Map();}getElementById(id){if(!this.elements.has(id))this.elements.set(id,new Element(id));return this.elements.get(id);}createElement(tag){return new Element(tag);}}
class Form{constructor(){this.data={};}append(k,v){this.data[k]=v;}}
let serial=0;
const context={window:{XLM_CHAT_TEST:true},URL,TextEncoder,Uint8Array,Blob,atob,FormData:Form,crypto:{randomUUID:()=>`request-${++serial}`}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'static/chat.js'),'utf8'),context);
const Chat=context.window.XlmChat;
const models=[{provider:'openai',model:'text',enabled:true,capabilities:['chat','structured_image']},{provider:'openai',model:'image',enabled:true,capabilities:['image_generation']},{provider:'google',model:'image',enabled:true,capabilities:['image_generation']},{provider:'google',model:'disabled',enabled:false,capabilities:['chat']}];
function fixture(){
 const doc=new Document(), events={}, calls=[], revoked=[], created=[];
 let respond=async(url)=>({ok:true,json:async()=>url==='/models'?{models}:{success:true,output_type:'image',mime_type:'image/png',image_base64:'cG5n',provider:'actual',model:'actual-model',latency_ms:12,request_id:'server',structured_result:{color:'red'}}});
 const fetch=async(url,options)=>{calls.push({url,options});return respond(url,options);};
 const urls={createObjectURL:blob=>{created.push(blob);return `blob:${created.length}`;},revokeObjectURL:url=>revoked.push(url)};
 const chat=new Chat(doc,fetch,{on:(event,fn)=>events[event]=fn},{token:'test-token',provider:'openai',model:'text'},urls);
 doc.getElementById('schemaInput').value='{"type":"object"}';
 return {chat,doc,events,calls,revoked,created,respond:fn=>{respond=fn;},el:id=>doc.getElementById(id)};
}
(async()=>{
 const f=fixture();await f.chat.load();assert.equal(f.el('modelSelect').value,'text');
 f.el('messageInput').value='Create an image of a tree';f.chat.suggest();assert.equal(f.el('suggestion').hidden,false);assert.equal(f.el('generateMode').checked,false);assert.equal(f.el('modelSelect').value,'text');
 for(const prompt of ['Draw a diagram of a tree','Draw a tree','Visualize a tree','Create a diagram of a tree']){f.el('messageInput').value=prompt;f.chat.suggest();assert.equal(f.el('suggestion').hidden,false);}
 f.el('dismissSuggestion').click();assert.equal(f.el('suggestion').hidden,true);
 for(const prompt of ['Explain how image generation works','Can you describe image generation?','What is an image?']){f.el('messageInput').value=prompt;f.chat.suggest();assert.equal(f.el('suggestion').hidden,true);}
 f.el('messageInput').value='Draw a picture of a tree';f.chat.suggest();f.el('acceptSuggestion').click();assert.equal(f.el('generateMode').checked,true);assert.equal(f.el('modelSelect').value,'text');assert.equal(f.el('sendButton').disabled,true);
 f.el('modelSelect').value='image';f.chat.validate();assert.equal(f.el('sendButton').disabled,false);await f.chat.send();
 assert.equal(f.calls.at(-1).url,'/generate_image');assert.equal(JSON.parse(f.calls.at(-1).options.body).model,'image');let entry=[...f.chat.entries.values()][0];assert.equal(entry.article.children.at(-1).tag,'img');assert.match(entry.metadata.textContent,/actual-model/);assert.equal(f.el('chatBox').children.length,1);
 f.el('modelSelect').value='image';await f.chat.load();assert.equal(f.el('modelSelect').value,'image');
 f.chat.attach({name:'test.png',type:'image/png',size:100});assert.equal(f.el('sendButton').disabled,true);assert.equal(f.el('attachment').hidden,false);
 f.el('generateMode').checked=false;f.el('modelSelect').value='text';f.chat.validate();assert.equal(f.el('sendButton').disabled,false);
 f.el('messageInput').value='What color?';await f.chat.send();assert.equal(f.calls.at(-1).url,'/analyze_image');assert.equal(f.calls.at(-1).options.body.data.instructions,'What color?');assert.equal(f.el('chatBox').children.length,2);
 f.chat.attach(null);assert.equal(f.el('advanced').hidden,true);assert.ok(f.revoked.includes('blob:2'));
 f.chat.attach({name:'bad',type:'text/html',size:20});assert.equal(f.chat.file,null);assert.match(f.el('feedback').textContent,/PNG/);
 f.el('messageInput').value='Hello';await f.chat.send();assert.match(f.el('feedback').textContent,/connection/);
 f.events.stream_session({token:'connection-secret'});await f.chat.send();assert.equal(f.calls.at(-1).url,'/send_message');const text=[...f.chat.entries.values()].at(-1);assert.equal(JSON.parse(f.calls.at(-1).options.body).stream_session,'connection-secret');
 f.events.chat_response({request_id:'foreign',message:'leak'});assert.equal(text.output.textContent,'');f.events.chat_response({request_id:text.id,message:'hello'});assert.equal(text.output.textContent,'hello');f.events.chat_done({request_id:text.id});f.events.chat_response({request_id:text.id,message:'late'});assert.equal(text.output.textContent,'hello');
 f.el('messageInput').value='Second';await f.chat.send();const latest=[...f.chat.entries.values()].at(-1);f.events.disconnect();assert.match(latest.output.textContent,/Connection lost/);assert.equal(text.output.textContent,'hello');
 f.events.chat_title({request_id:text.id,title:'Text title'});assert.equal(text.title.textContent,'Text title');
 f.chat.dispose();assert.ok(f.revoked.includes('blob:1'));
 const bad=fixture();await bad.chat.load();bad.el('generateMode').checked=true;bad.el('modelSelect').value='image';bad.el('messageInput').value='<script>bad</script>';bad.respond(async()=>({ok:false,json:async()=>({error:'PRIVATE_UPSTREAM'})}));await bad.chat.send();const failed=[...bad.chat.entries.values()][0];assert.equal(failed.article.children[1].textContent,'<script>bad</script>');assert.doesNotMatch(failed.output.textContent,/PRIVATE/);
 const refresh=fixture();await refresh.chat.load();refresh.respond(async()=>({ok:true,json:async()=>({models:models.filter(m=>m.model!=='text')})}));await refresh.chat.load();assert.equal(refresh.el('modelSelect').value,'');assert.equal(refresh.el('sendButton').disabled,true);
 const delayed=fixture();await delayed.chat.load();let release;const wait=new Promise(resolve=>{release=resolve;});delayed.respond(async()=>{await wait;return {ok:true,json:async()=>({models})};});const loading=delayed.chat.load();delayed.el('providerSelect').value='google';delayed.chat.models();delayed.el('modelSelect').value='image';release();await loading;assert.equal(delayed.el('providerSelect').value,'google');assert.equal(delayed.el('modelSelect').value,'image');
 const invalid=fixture();await invalid.chat.load();invalid.chat.attach({name:'good.png',type:'image/png',size:100});invalid.chat.attach({name:'bad',type:'text/html',size:100});assert.equal(invalid.chat.file,null);assert.equal(invalid.el('attachment').hidden,true);assert.ok(invalid.revoked.includes('blob:1'));
 const decode=fixture();await decode.chat.load();decode.el('generateMode').checked=true;decode.el('modelSelect').value='image';decode.el('messageInput').value='Draw a tree';await decode.chat.send();const resultEntry=[...decode.chat.entries.values()][0];const broken=resultEntry.article.children.at(-1);broken.listeners.error();assert.equal(broken.removed,true);assert.match(resultEntry.output.textContent,/could not be displayed/);assert.ok(decode.revoked.includes('blob:1'));
 console.log('Chat controller behavioral tests passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
