// Run by GrowthIntegrationTest (POS_BROWSER_QA=1): opens the new admin tabs and the POS with a test-only principal,
// takes screenshots and fails on JavaScript errors or on any write request (nothing is changed in the database).
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const base = process.env.POS_QA_URL, user = process.env.POS_QA_USER, password = process.env.POS_QA_PASSWORD;
if (!base || !user || !password) throw new Error('Run through GrowthIntegrationTest.');
const output = path.resolve('target/growth-ui-qa');
const profile = path.join(output, 'chrome-' + Date.now());
await mkdir(profile, { recursive: true });
const chrome = spawn(process.env.CHROME_BIN || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'],
    { windowsHide: true, stdio: 'ignore' });
let socket;
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
try {
    let debugPort;
    for (let i = 0; i < 100; i++) { try { debugPort = Number((await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; } catch { await delay(100); } }
    if (!debugPort) throw new Error('Chrome did not start.');
    const targets = await (await fetch('http://127.0.0.1:' + debugPort + '/json/list')).json();
    socket = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
    let sequence = 0;
    const pending = new Map(), errors = [], writes = [];
    socket.addEventListener('message', ({ data }) => {
        const message = JSON.parse(String(data));
        if (message.id) { const item = pending.get(message.id); if (item) { clearTimeout(item.timeout); pending.delete(message.id); message.error ? item.reject(new Error(JSON.stringify(message.error))) : item.resolve(message.result); } }
        if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
        if (message.method === 'Network.requestWillBeSent' && !['GET', 'HEAD', 'OPTIONS'].includes(message.params.request.method))
            writes.push({ method: message.params.request.method, url: message.params.request.url });
    });
    const send = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; const timeout = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)); }, 30000); pending.set(id, { resolve, reject, timeout }); socket.send(JSON.stringify({ id, method, params })); });
    const evaluate = async (expression) => { const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text); return result.result.value; };
    const wait = async (expression, timeout = 30000) => { const end = Date.now() + timeout; while (Date.now() < end) { try { if (await evaluate(expression)) return; } catch { } await delay(150); } throw new Error('Readiness timeout: ' + expression); };
    const shot = async (name, full = false) => {
        const params = { format: 'png', captureBeyondViewport: full };
        if (full) { const size = await evaluate('({w: document.documentElement.scrollWidth, h: Math.min(document.documentElement.scrollHeight, 4000)})'); params.clip = { x: 0, y: 0, width: size.w, height: size.h, scale: 1 }; }
        await writeFile(path.join(output, name), Buffer.from((await send('Page.captureScreenshot', params)).data, 'base64'));
    };
    await send('Runtime.enable'); await send('Page.enable'); await send('Network.enable');
    await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: base + '/login' }); await wait('!!document.querySelector("input[name=username]")');
    await evaluate('document.querySelector("input[name=username]").value=' + JSON.stringify(user) + ';document.querySelector("input[name=password]").value=' + JSON.stringify(password) + ';document.querySelector("form").requestSubmit();true');
    await wait('location.pathname==="/admin"&&window.storeUser?.role==="ADMIN"');
    const clickTab = async (tab) => { await evaluate('document.querySelector(\'[data-tab="' + tab + '"]\').click();window.scrollTo(0,0);true'); await wait('!document.getElementById("tab-' + tab + '").hidden'); };
    const report = {};

    await clickTab('basket'); await wait('document.querySelectorAll("#basketBody tr").length > 1');
    report.basketRules = await evaluate('document.querySelectorAll("#basketBody tr").length');
    await delay(300); await shot('admin-basket.png', true);

    await clickTab('stock'); await wait('document.querySelectorAll("#lotBody tr").length > 1 && document.querySelectorAll("#stockStats .stat-card").length === 4');
    report.stock = await evaluate('({expiring: document.querySelectorAll("#expiryBody tr").length, lots: document.querySelectorAll("#lotBody tr").length, demoLabels: document.body.innerText.includes("DEMO"), receiptLines: document.querySelectorAll("#receiptLines .growth-line").length, scrolled: scrollY})');
    if (report.stock.demoLabels) throw new Error('The stock page still shows DEMO labels.');
    await delay(300); await shot('admin-stock.png', true);

    await clickTab('revenue'); await wait('document.querySelectorAll("#profitStats .stat-card").length === 4 && document.querySelectorAll("#profitMonthBody tr").length > 0');
    report.profit = await evaluate('[...document.querySelectorAll("#profitStats .stat-value")].map((n) => n.textContent)');
    await evaluate('document.getElementById("profitCard").scrollIntoView();true');
    await delay(300); await shot('admin-profit.png');

    await clickTab('experiments'); await wait('!document.getElementById("experimentCaption").textContent.startsWith("Đang")');
    await evaluate('document.getElementById("simulationForm").requestSubmit();true');   // the simulation is a POST that stores nothing
    await wait('!!document.querySelector("#simulationResult .experiment-verdict")');
    report.simulationVerdict = await evaluate('document.querySelector("#simulationResult .experiment-verdict").textContent');
    await delay(300); await shot('admin-experiments.png', true);

    await clickTab('reviews'); await wait('document.querySelectorAll("#reviewStats .stat-card").length === 4');
    report.reviewsEmptyState = await evaluate('document.querySelector("#reviewProductBody").textContent.trim().slice(0, 80)');
    await delay(300); await shot('admin-reviews.png', true);

    // POS: put a product that has "bought together" rules into the cart and look at the suggestions (no checkout)
    await send('Page.navigate', { url: base + '/pos' });
    await wait('document.querySelectorAll(".product-card").length > 10');
    await evaluate('document.getElementById("openShiftModal") && (document.getElementById("openShiftModal").style.display="none");true');
    const productId = await evaluate(`(async () => {
        for (const card of [...document.querySelectorAll('.product-card')].slice(0, 200)) {
            const list = await fetch('/api/pos/suggestions?ids=' + card.dataset.productId).then((r) => r.json());
            if (list.length >= 2) return Number(card.dataset.productId);
        }
        return null; })()`);
    report.posProductWithSuggestions = productId;
    if (productId != null) {
        await evaluate('document.querySelector(\'.product-card[data-product-id="' + productId + '"] .product-add-button\').click();true');
        await wait('!document.getElementById("suggestBox").hidden && document.querySelectorAll("#suggestList .suggest-item").length > 0');
        report.posSuggestions = await evaluate('[...document.querySelectorAll("#suggestList .suggest-reason")].map((n) => n.textContent)');
        await evaluate('document.querySelector("#suggestList .suggest-add").click();true');
        await wait('document.querySelectorAll("#cartTableBody tr").length === 2');
        report.posCartAfterSuggestion = await evaluate('document.querySelectorAll("#cartTableBody tr").length');
    }
    await delay(400); await shot('pos-suggestions.png');

    // the member page (signed out) renders on a phone without errors
    await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
    await send('Page.navigate', { url: base + '/member' }); await wait('!document.getElementById("authView").hidden');
    await delay(300); await shot('member-signed-out.png');

    const allowedWrites = writes.filter((request) => !/^\/(login|logout|api\/admin\/experiments\/simulate)$/.test(new URL(request.url).pathname));
    if (errors.length) throw new Error('Browser JavaScript errors: ' + errors.join('\n'));
    if (allowedWrites.length) throw new Error('Unexpected write requests: ' + JSON.stringify(allowedWrites));
    report.passed = true; report.javascriptErrors = 0;
    await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
    process.stdout.write(JSON.stringify(report) + '\n');
} finally { socket?.close(); chrome.kill(); }
