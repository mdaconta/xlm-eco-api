'use strict';
// Verify the one-call diagnostic mode against a local fake XLM, never a provider.
const {spawn}=require('node:child_process'),net=require('node:net'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));
 const server=spawn(process.env.PYTHON||'python',[path.join(__dirname,'test_chat_browser_server.py'),String(port)],{windowsHide:true,stdio:'ignore',env:{...process.env,XLM_TEST_LIVE_NAMES:'true'}});
 try{
  let ready=false;for(let i=0;i<60;i++){try{if((await fetch(`http://127.0.0.1:${port}/`)).ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}assert.ok(ready);
  const output=path.resolve('target/remote-chat-generation-fixture');
  const child=spawn(process.execPath,[path.join(__dirname,'remote_chat_browser.cjs')],{windowsHide:true,stdio:'inherit',env:{...process.env,XLM_RUN_REMOTE_CHAT:'true',XLM_REMOTE_CHAT_SCENARIO:'generation',XLM_CHAT_URL:`http://127.0.0.1:${port}`,XLM_BROWSER_OUTPUT:output}});
  const code=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('exit',resolve);});assert.equal(code,0);
  const ledger=JSON.parse(fs.readFileSync(path.join(output,'run-status.json'),'utf8'));
  assert.equal(ledger.status,'passed');assert.equal(ledger.scenario,'generation');assert.equal(ledger.provider_call_budget,1);assert.deepEqual(ledger.requests.map(r=>r.route),['/generate_image']);
  assert.ok(!ledger.stages.some(s=>/text|attachment|title/.test(s.name)));
  console.log('Generation-only harness fixture passed: one generation request, no text/title/analysis.');
 }finally{server.kill();}
})().catch(error=>{console.error(error);process.exitCode=1;});
