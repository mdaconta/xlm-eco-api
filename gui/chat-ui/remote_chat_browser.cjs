'use strict';
// Opt-in billable live UI test: one text + automatic title, one analysis, one generation.
// No retry. Persist sanitized stages and HTTP request counts before any provider request.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path');
if(process.env.XLM_RUN_REMOTE_CHAT!=='true') throw new Error('Explicit XLM_RUN_REMOTE_CHAT=true authorization required');
const scenario=process.env.XLM_REMOTE_CHAT_SCENARIO || 'full';
if(!['full','generation'].includes(scenario))throw new Error('Scenario must be full or generation');
const directory=path.resolve(process.env.XLM_BROWSER_OUTPUT || 'target/remote-chat-browser');
fs.mkdirSync(directory,{recursive:true});
const ledger={scenario,browser_channel:process.env.BROWSER_CHANNEL || 'msedge',provider_call_budget:scenario==='full'?4:1,started_at:new Date().toISOString(),status:'running',stage:'preflight',requests:[],responses:[],stages:[]};
const save=()=>fs.writeFileSync(path.join(directory,'run-status.json'),JSON.stringify(ledger,null,2));
const requestTimes=new Map();const runStarted=performance.now();
const inferPaths=new Set(['/send_message','/analyze_image','/generate_image']);
const origin=process.env.XLM_CHAT_URL || 'http://127.0.0.1:5000';
const attachment=path.resolve('target/verification-red-square.png');

async function boundedDiagnostic(promise){
 let timer;
 try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>{const error=new Error('Diagnostic observation timed out');error.name='DiagnosticTimeout';reject(error);},5000);})]);}
 finally{clearTimeout(timer);}
}
async function stage(name,action){ledger.stage=name;ledger.stages.push({name,state:'started'});save();await action();ledger.stages.at(-1).state='passed';save();}
(async()=>{
 let browser,page;const transfers=[];
 save();
 try {
  if(scenario==='full')await stage('attachment preflight',async()=>assert.ok(fs.existsSync(attachment),'Local attachment fixture is missing'));
  await stage('browser launch',async()=>{browser=await chromium.launch({headless:true,channel:process.env.BROWSER_CHANNEL || 'msedge'});page=await browser.newPage({viewport:{width:1280,height:900}});page.setDefaultTimeout(170000);});
  await page.addInitScript(()=>{
   window.__xlmHarnessFailures=[];
   const originalJson=Response.prototype.json;
   Response.prototype.json=async function(...args){try{return await originalJson.apply(this,args);}catch(error){if(this.url.endsWith('/generate_image'))window.__xlmHarnessFailures.push({operation:'response.json',errorType:error.name});throw error;}};
   const originalFetch=window.fetch;
   window.fetch=async function(...args){try{return await originalFetch.apply(this,args);}catch(error){if(String(args[0]).endsWith('/generate_image'))window.__xlmHarnessFailures.push({operation:'fetch',errorType:error.name});throw error;}};
  });
  page.on('requestfailed',request=>{const route=new URL(request.url()).pathname;if(inferPaths.has(route)){ledger.transfer_failures=ledger.transfer_failures||[];ledger.transfer_failures.push({route,networkCode:request.failure()?.errorText.match(/net::ERR_[A-Z_]+/)?.[0]||'unavailable',failed_after_request_ms:requestTimes.has(request)?Math.round(performance.now()-requestTimes.get(request)):null});save();}});
  page.on('request',request=>{const route=new URL(request.url()).pathname;if(request.method()==='POST'&&inferPaths.has(route)){const started=performance.now();requestTimes.set(request,started);ledger.requests.push({route,at:new Date().toISOString(),start_elapsed_ms:Math.round(started-runStarted)});save();}});
  page.on('response',response=>{const route=new URL(response.url()).pathname;if(inferPaths.has(route)){
   const responseStarted=performance.now();const requestStarted=requestTimes.get(response.request());
   const record={route,status:response.status(),content_length:response.headers()['content-length'],headers_after_request_ms:requestStarted===undefined?null:Math.round(responseStarted-requestStarted)};ledger.responses.push(record);save();
   if(route==='/generate_image')transfers.push((async()=>{try{const finished=await boundedDiagnostic(response.finished());record.finished_error=finished?finished.name:null;const bytes=await boundedDiagnostic(response.body());record.received_bytes=bytes.length;record.body_read_after_headers_ms=Math.round(performance.now()-responseStarted);try{const body=JSON.parse(bytes);record.json_valid=true;record.success=body.success;record.image_characters=body.image_base64?.length;}catch(error){record.json_valid=false;record.parse_error_type=error.name;}}catch(error){record.read_error_type=error.name;record.network_code=error.message.match(/net::ERR_[A-Z_]+/)?.[0]||'unavailable';}finally{record.transfer_observation_ms=Math.round(performance.now()-responseStarted);save();}})());
  }});
  await stage('catalog and explicit model selection',async()=>{
   await page.goto(origin);await page.locator('#modelSelect option').first().waitFor({state:'attached'});
   await page.locator('#providerSelect').selectOption('openai');await page.locator('#modelSelect').selectOption(scenario==='full'?'gpt-4o-mini':'gpt-image-2.5-flare');
  });
  if(scenario==='full')await stage('suggestion explicit override and capability block',async()=>{
   await page.locator('#messageInput').fill('Create an image of a blue circle, but respond with the word OK only.');
   assert.equal(await page.locator('#suggestion').isVisible(),true);await page.locator('#acceptSuggestion').click();
   assert.equal(await page.locator('#sendButton').isDisabled(),true);await page.locator('#generateMode').uncheck();await page.locator('#dismissSuggestion').click();
  });
  await stage('read-only inference readiness preflight',async()=>{
   const discovery=await page.request.get(new URL('/models',origin).href);
   ledger.readiness_http_status=discovery.status();save();
   assert.equal(discovery.status(),200,'Inference discovery is unavailable; no inference request submitted');
   const status=await page.request.get(new URL('/console/status',origin).href);
   assert.equal(status.status(),200,'Console status is unavailable');
   assert.equal((await status.json()).inference.state,'ready','Inference is recovering; no inference request submitted');
  });
  async function submit(route){
   const pending=page.waitForResponse(response=>new URL(response.url()).pathname===route&&response.request().method()==='POST');
   pending.catch(()=>{}); // Closing the browser cancels a wait if UI validation prevents submission.
   await page.locator('#sendButton').click();
   assert.equal(await page.locator('#feedback').textContent(),'','UI prevented submission');
   const response=await pending;
   assert.equal(response.status(),200,'Inference HTTP request failed; no automatic retry');
  }
  if(scenario==='full'){
  await stage('text submission and response',async()=>{
   await submit('/send_message');
   await page.waitForFunction(()=>document.querySelector('article pre')?.textContent.includes('OK') || document.querySelector('#feedback')?.textContent);
   assert.ok((await page.locator('article pre').first().textContent()).includes('OK'),'Expected text response was not rendered');
  });
  await stage('optional scoped text title',async()=>{await page.waitForFunction(()=>Boolean(document.querySelector('article h2')?.textContent));});
  await stage('attachment chooser and analysis submission',async()=>{
   const chooser=page.waitForEvent('filechooser');await page.locator('#attachButton').click();await (await chooser).setFiles(attachment);
   assert.equal(await page.locator('#attachment').isVisible(),true);
   await page.locator('#messageInput').fill('What color is the square at the center? Return the color name.');
   await page.locator('#advanced').evaluate(e=>e.open=true);
   await page.locator('#schemaInput').fill('{"type":"object","properties":{"color":{"type":"string"}},"required":["color"],"additionalProperties":false}');
   await submit('/analyze_image');
   await page.waitForFunction(()=>document.querySelectorAll('article')[1]?.textContent.toLowerCase().includes('"red"'));
  });
  }
  await stage('generation submission and image decode',async()=>{
   if(scenario==='full')await page.locator('#removeImage').click();await page.locator('#modelSelect').selectOption('gpt-image-2.5-flare');await page.locator('#generateMode').check();
   await page.locator('#messageInput').fill('Draw one blue circle on a plain white background. No text.');await submit('/generate_image');
   const index=scenario==='full'?2:0;
   await page.waitForFunction(index=>{const article=document.querySelectorAll('article')[index];return article?.querySelector('img')?.naturalWidth>0 || /Request failed|could not be displayed/.test(article?.querySelector('pre')?.textContent||'');},index);
   await Promise.allSettled(transfers);
   assert.ok(await page.locator('article').nth(index).locator('img').evaluateAll(images=>images.some(image=>image.naturalWidth>0)),'Generation finished with a terminal UI failure');
  });
  await stage('desktop and narrow layout',async()=>{
   const evidence=await page.locator('article').allTextContents();fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2));
   await page.screenshot({path:path.join(directory,'desktop.png'),fullPage:true});await page.setViewportSize({width:390,height:844});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:path.join(directory,'narrow.png'),fullPage:true});
  });
  await Promise.allSettled(transfers);ledger.status='passed';save();console.log(`Live Chat ${scenario} scenario and responsive checks PASSED.`);
 } catch(error) {
  ledger.status='failed';ledger.error_type=error.name;
  // No raw browser exception, URL, token, request body or upstream response is saved.
  if(page){try{Object.assign(ledger,await page.evaluate(()=>({feedback:document.querySelector('#feedback')?.textContent || '',capability:document.querySelector('#capabilityInfo')?.textContent || '',entries:[...document.querySelectorAll('article')].map(e=>e.textContent),browser_failures:window.__xlmHarnessFailures||[]})));await page.screenshot({path:path.join(directory,'failure.png'),fullPage:true,timeout:5000});}catch{ledger.failure_capture='unavailable';}}
  save();console.error(`Live Chat check failed at stage "${ledger.stage}" (${ledger.error_type}). ${ledger.requests.length} inference HTTP requests observed. Inspect ${path.join(directory,'run-status.json')}. No automatic retry.`);process.exitCode=1;
 } finally {if(browser)await browser.close();await Promise.allSettled(transfers);save();}
})();
