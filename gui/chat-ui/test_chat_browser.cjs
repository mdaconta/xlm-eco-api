'use strict';
// Real DOM/layout regression against the Flask UI with a local fake XLM stub.
// PLAYWRIGHT_MODULE may point to a preinstalled Playwright module. No downloads.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {spawn}=require('node:child_process'), path=require('node:path'), fs=require('node:fs'), assert=require('node:assert/strict'), net=require('node:net');

async function boundedDiagnostic(promise){
 let timer;
 try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>{const error=new Error('Diagnostic observation timed out');error.name='DiagnosticTimeout';reject(error);},5000);})]);}
 finally{clearTimeout(timer);}
}
(async()=>{
 const probe=net.createServer();await new Promise(resolve=>probe.listen(0,'127.0.0.1',resolve));const port=probe.address().port;await new Promise(resolve=>probe.close(resolve));
 const server=spawn(process.env.PYTHON || 'python',[path.join(__dirname,'test_chat_browser_server.py'),String(port)],{windowsHide:true,stdio:['ignore','pipe','pipe']});
 let logs='';server.stdout.on('data',d=>logs+=d);server.stderr.on('data',d=>logs+=d);
 let browser,page;
 const out=path.resolve(process.env.XLM_TEST_BROWSER_OUTPUT || path.join(__dirname,'../../target/chat-browser'));fs.mkdirSync(out,{recursive:true});
 const diagnostics={responses:[],failures:[]};const diagnosticWrites=[];const requestTimes=new Map();const saveDiagnostics=()=>fs.writeFileSync(path.join(out,'diagnostics.json'),JSON.stringify(diagnostics,null,2));
 try{
  let ready=false;for(let i=0;i<60;i++){try{const r=await fetch(`http://127.0.0.1:${port}/`);if(r.ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}assert.ok(ready,logs);
  browser=await chromium.launch({headless:true,channel:process.env.BROWSER_CHANNEL || 'msedge'});
  page=await browser.newPage({viewport:{width:1280,height:900}});
  page.on('request',request=>{if(new URL(request.url()).pathname==='/generate_image')requestTimes.set(request,performance.now());});
  page.on('requestfailed',request=>{if(new URL(request.url()).pathname==='/generate_image'){diagnostics.failures.push({route:'/generate_image',error:request.failure()?.errorText,failed_after_request_ms:requestTimes.has(request)?Math.round(performance.now()-requestTimes.get(request)):null});saveDiagnostics();}});
  page.on('response',response=>{if(new URL(response.url()).pathname==='/generate_image'){const headerTime=performance.now();const start=requestTimes.get(response.request());const record={route:'/generate_image',status:response.status(),contentLength:response.headers()['content-length'],headers_after_request_ms:start===undefined?null:Math.round(headerTime-start)};diagnostics.responses.push(record);saveDiagnostics();diagnosticWrites.push((async()=>{try{const finished=await boundedDiagnostic(response.finished());record.finishedError=finished?finished.name:null;const body=await boundedDiagnostic(response.body());record.body_after_headers_ms=Math.round(performance.now()-headerTime);record.bytes=body.length;try{const json=JSON.parse(body);record.jsonValid=true;record.imageCharacters=json.image_base64?.length;record.success=json.success;}catch(error){record.jsonValid=false;record.errorType=error.name;}}catch(error){record.readError=error.name;record.networkCode=error.message.match(/net::ERR_[A-Z_]+/)?.[0] || 'unavailable';}finally{record.transfer_observation_ms=Math.round(performance.now()-headerTime);saveDiagnostics();}})());}});
  // Socket transport itself has Python two-connection isolation tests. This shim avoids CDN dependency.
  if(process.env.XLM_TEST_REAL_SOCKET!=='true')await page.route('https://cdn.socket.io/**',route=>route.fulfill({contentType:'application/javascript',body:'window.io=()=>({on(){}});'}));
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.locator('#modelSelect option').first().waitFor({state:'attached'});
  assert.equal(await page.locator('#advanced').isVisible(),false);
  await page.locator('#messageInput').fill('Create an image of a tree');assert.equal(await page.locator('#suggestion').isVisible(),true);
  assert.equal(await page.locator('#generateMode').isChecked(),false);
  await page.locator('#dismissSuggestion').click();assert.equal(await page.locator('#suggestion').isVisible(),false);assert.equal(await page.locator('#generateMode').isChecked(),false);
  await page.locator('#messageInput').fill('Draw a diagram of a tree');assert.equal(await page.locator('#suggestion').isVisible(),true);
  await page.locator('#acceptSuggestion').click();assert.equal(await page.locator('#sendButton').isDisabled(),true);
  await page.locator('#modelSelect').selectOption('image');await page.locator('#sendButton').click();await page.waitForFunction(()=>document.querySelector('article img') || document.querySelector('article pre')?.textContent.includes('failed'));assert.equal(await page.locator('article img').count(),1,'Generated image response was not rendered');
  await page.waitForFunction(()=>document.querySelector('article img').naturalWidth>0);
  assert.match(await page.locator('article').innerText(),/Draw a diagram of a tree/);
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF1kAAAAASUVORK5CYII=','base64');
  const chooserPromise=page.waitForEvent('filechooser');await page.locator('#attachButton').click();const chooser=await chooserPromise;await chooser.setFiles({name:'pixel.png',mimeType:'image/png',buffer:png});assert.equal(await page.locator('#attachment').isVisible(),true);assert.equal(await page.locator('#sendButton').isDisabled(),true);
  await page.locator('#generateMode').uncheck();await page.locator('#modelSelect').selectOption('text');await page.locator('#messageInput').fill('Describe this image');await page.locator('#sendButton').click();await page.waitForFunction(()=>document.querySelectorAll('article')[1]?.textContent.includes('test pixel'));
  assert.equal(await page.locator('article').count(),2);
  await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:path.join(out,'narrow.png'),fullPage:true});
  await page.locator('#removeImage').click();assert.equal(await page.locator('#attachment').isVisible(),false);assert.equal(await page.locator('#advanced').isVisible(),false);
  console.log('Real-browser Chat generation, analysis, compact attachment, transcript and 390px layout checks passed. Screenshots: '+out);
 }catch(error){if(page){diagnostics.entries=await page.locator('article').allTextContents();await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});}throw error;}finally{await Promise.allSettled(diagnosticWrites);fs.writeFileSync(path.join(out,'diagnostics.json'),JSON.stringify(diagnostics,null,2));if(browser)await browser.close();server.kill();}
})().catch(error=>{console.error(error);process.exitCode=1;});
