// Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
//
// page-fonts.mjs — the debug page in a headless Chromium (#304): which brass fonts
// document.fonts has loaded, and full-page screenshots 1280 and 400 px wide.
// Runs in node:24 (fetch and WebSocket built in) next to Chromium's DevTools port.
//
// Usage: node page-fonts.mjs <DevTools base URL> <page URL> <user:password> <out dir>
import fs from 'node:fs';

const [, , devtools, page, credentials, out] = process.argv;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
for (let i = 0; ; i++) {
    try { await (await fetch(devtools + '/json/version')).json(); break; } catch (e) {
        if (i > 50) throw new Error('no DevTools at ' + devtools);
        await sleep(200);
    }
}
const target = await (await fetch(devtools + '/json/new?about:blank', { method: 'PUT' })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0;
const pending = new Map();
ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id).resolve(m); pending.delete(m.id); }
};
// a closed socket or a silent browser fails the commands waiting for it (Qodo on #305)
ws.onclose = () => {
    for (const p of pending.values()) p.reject(new Error('DevTools closed'));
    pending.clear();
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
    const i = ++id;
    const timer = setTimeout(() => {
        pending.delete(i); reject(new Error(method + ': no answer in 30 s'));
    }, 30000);
    pending.set(i, {
        resolve: (m) => { clearTimeout(timer); resolve(m); },
        reject: (e) => { clearTimeout(timer); reject(e); },
    });
    ws.send(JSON.stringify({ id: i, method, params }));
});
await send('Network.enable');
await send('Network.setExtraHTTPHeaders', {
    headers: { Authorization: 'Basic ' + Buffer.from(credentials).toString('base64') },
});
const result = {};
for (const width of [1280, 400]) {
    await send('Emulation.setDeviceMetricsOverride',
        { width, height: 900, deviceScaleFactor: 1, mobile: width < 600 });
    await send('Page.navigate', { url: page });
    await sleep(5000);
    const ev = await send('Runtime.evaluate', {
        expression: 'document.fonts.ready.then(() => JSON.stringify([...document.fonts]'
            + '.map((f) => [f.family.replace(/["\']/g, ""), f.weight, f.status])))',
        awaitPromise: true, returnByValue: true,
    });
    const faces = JSON.parse(ev.result.result.value);
    const loaded = (family) => faces.some((f) => f[0] === family && f[2] === 'loaded');
    const h1 = await send('Runtime.evaluate', {
        expression: 'getComputedStyle(document.querySelector("h1")).fontFamily + " | "'
            + ' + getComputedStyle(document.querySelector(".board")).fontFamily',
        returnByValue: true,
    });
    result[width] = { faces, serif: loaded('Brass Serif'), mono: loaded('Brass Mono'),
        computed: h1.result.result.value };
    const m = await send('Page.getLayoutMetrics');
    const size = m.result.cssContentSize;
    const shot = await send('Page.captureScreenshot', {
        format: 'png', captureBeyondViewport: true,
        clip: { x: 0, y: 0, width, height: Math.ceil(size.height), scale: 1 },
    });
    fs.writeFileSync(`${out}/page-${width}.png`, Buffer.from(shot.result.data, 'base64'));
}
console.log(JSON.stringify(result));
ws.close();
process.exit(result[1280].serif && result[1280].mono && result[400].serif && result[400].mono ? 0 : 1);
