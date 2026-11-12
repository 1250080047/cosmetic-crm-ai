// Run by AdminReadOnlyIntegrationTest. Uses a test-only principal and real SELECT APIs.
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const base=process.env.POS_QA_URL;
if(!base || !process.env.POS_QA_PASSWORD)throw new Error('Run through the read-only integration test.');
const output=path.resolve('target/admin-workspace-qa');
const profile=path.join(output,'chrome-'+Date.now());await mkdir(profile,{recursive:true});
const chrome=spawn(process.env.CHROME_BIN || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',[
    '--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'
],{windowsHide:true,stdio:'ignore'});
let socket;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
try {
    let debugPort;
    for(let i=0;i<100;i++){try{debugPort=Number((await readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]);break;}catch{await delay(100);}}
    if(!debugPort)throw new Error('Chrome did not start.');
    const targets=await (await fetch('http://127.0.0.1:'+debugPort+'/json/list')).json();
    socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
    await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
    let sequence=0;const pending=new Map(),errors=[],writes=[];
    socket.addEventListener('message',({data})=>{
        const message=JSON.parse(String(data));
        if(message.id){const item=pending.get(message.id);if(item){clearTimeout(item.timeout);pending.delete(message.id);message.error?item.reject(new Error(JSON.stringify(message.error))):item.resolve(message.result);}}
        if(message.method==='Runtime.exceptionThrown')errors.push(message.params.exceptionDetails.exception?.description||message.params.exceptionDetails.text);
        if(message.method==='Network.requestWillBeSent'&&!['GET','HEAD','OPTIONS'].includes(message.params.request.method))writes.push({method:message.params.request.method,url:message.params.request.url});
    });
    const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;const timeout=setTimeout(()=>{pending.delete(id);reject(new Error('CDP timeout: '+method));},30000);pending.set(id,{resolve,reject,timeout});socket.send(JSON.stringify({id,method,params}));});
    const evaluate=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text);return result.result.value;};
    const wait=async(expression,timeout=30000)=>{const end=Date.now()+timeout;while(Date.now()<end){try{if(await evaluate(expression))return;}catch{}await delay(150);}throw new Error('Readiness timeout: '+expression);};
    await send('Runtime.enable');await send('Page.enable');await send('Network.enable');
    await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
    await send('Page.navigate',{url:base+'/login'});await wait('!!document.querySelector("input[name=username]")');
    await evaluate('document.querySelector("input[name=username]").value="readonly-preview";document.querySelector("input[name=password]").value='+JSON.stringify(process.env.POS_QA_PASSWORD)+';document.querySelector("form").requestSubmit();true');
    await wait('location.pathname==="/admin"&&window.storeUser?.role==="ADMIN"');
    const clickTab=async tab=>{await evaluate('document.querySelector(\'[data-tab="'+tab+'"]\').click();true');await wait('!document.getElementById("tab-'+tab+'").hidden');};
    await clickTab('revenue');await wait('!document.getElementById("revenueResults").hidden');
    await evaluate('document.getElementById("revenueGranularity").value="year";document.getElementById("revenueGranularity").dispatchEvent(new Event("change"));document.getElementById("revenueYear").value="2020";document.getElementById("revenueFilters").requestSubmit();true');
    await wait('!document.getElementById("runRevenue").disabled && !document.getElementById("revenueResults").hidden');
    if(await evaluate('document.querySelectorAll("#revenueTrendBody tr").length')!==12)throw new Error('Expected 12 monthly comparison rows.');
    await delay(500);
    await writeFile(path.join(output,'revenue-desktop.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));
    await send('Browser.setDownloadBehavior',{behavior:'allow',downloadPath:output});await evaluate('document.getElementById("exportRevenue").click();true');
    await clickTab('products');await wait('!!document.querySelector("#productBody button")');
    await evaluate('document.querySelector("#productBody button").click();true');await wait('document.getElementById("workspaceDialog").open');
    await evaluate('document.getElementById("closeWorkspaceDialog").click();true');
    await clickTab('customers');await wait('document.getElementById("customerCount").textContent.includes("khách hàng")');
    await evaluate('document.getElementById("customerSearch").value="%";document.getElementById("customerSearch").dispatchEvent(new Event("input"));true');
    await wait('document.getElementById("customerCount").textContent==="0 khách hàng"');
    await clickTab('orders');await wait('!!document.querySelector(".order-detail-link")');await evaluate('document.querySelector(".order-detail-link").click();true');
    await wait('!!document.getElementById("orderDetailItems")');await evaluate('document.getElementById("closeWorkspaceDialog").click();true');
    await clickTab('strategies');await wait('!!document.getElementById("stratEnabled")');
    const active=await evaluate('fetch("/api/admin/ai/active-clusters").then(response=>response.json())');
    if(active.synchronized){
        await wait('document.querySelectorAll("#stratSelect option").length==='+active.active_k);
        await wait('!document.getElementById("strategyFields").disabled && !document.getElementById("saveStrategyButton").disabled');
        const optionNames=await evaluate('[...document.querySelectorAll("#stratSelect option")].map(option=>option.textContent)');
        const expected=active.clusters.map(cluster=>cluster.name);
        if(JSON.stringify(optionNames)!==JSON.stringify(expected))throw new Error('Strategy names/order differ from active SQL clusters: '+JSON.stringify(optionNames));
        await writeFile(path.join(output,'strategies-synchronized.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));
        await clickTab('overview');await wait('document.querySelector(".k-btn.active")?.dataset.k==="'+active.active_k+'"');
        await wait('document.querySelectorAll("#clusterProfileTableBody tr").length==='+active.active_k);
        const profileNames=await evaluate('[...document.querySelectorAll("#clusterProfileTableBody .segment-cell strong")].map(node=>node.textContent)');
        if(JSON.stringify(profileNames)!==JSON.stringify(expected))throw new Error('Overview names/order differ from SQL clusters.');
        await writeFile(path.join(output,'clusters-synchronized.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));
    }
    await clickTab('users');await wait('!document.querySelector(\'[data-tab="users"]\').hidden');
    await evaluate('document.getElementById("addUser").click();true');await wait('document.getElementById("workspaceDialog").open');await evaluate('document.getElementById("closeWorkspaceDialog").click();true');
    await clickTab('revenue');await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await delay(400);
    const mobile=await evaluate('({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth,viewport:innerWidth})');
    if(mobile.scroll>mobile.width+2)throw new Error('Mobile page overflows: '+JSON.stringify(mobile));
    await writeFile(path.join(output,'revenue-mobile.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));
    if(errors.length)throw new Error('Browser JavaScript errors: '+errors.join('\n'));
    if(writes.some(request=>!new URL(request.url).pathname.match(/^\/(login|logout)$/)))throw new Error('Unexpected mutation: '+JSON.stringify(writes));
    const report={passed:true,tabs:['revenue','products','customers','orders','strategies','users'],monthlyRows:12,mobile,javascriptErrors:errors.length,databaseMutations:0};
    await writeFile(path.join(output,'report.json'),JSON.stringify(report,null,2));process.stdout.write(JSON.stringify(report)+'\n');
}finally{socket?.close();chrome.kill();}
