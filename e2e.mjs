// 端到端实证：真发一条消息，等模型回复并截图
import puppeteer from 'puppeteer-core';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const url = process.argv[2];
const out = process.argv[3] || 'e2e.png';
const text = process.argv[4] || '你好，用一句话确认你收到了这条从手机发来的消息。';

const logs = [];
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: true,
  args: ['--no-first-run', '--no-default-browser-check', '--disable-gpu'],
});
const page = await browser.newPage();
page.on('console', (m) => { if (m.type() === 'error') logs.push('err: ' + m.text().slice(0, 200)); });
page.on('websocket', (ws) => logs.push('ws: ' + ws.url().slice(0, 80)));

await page.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
await new Promise((r) => setTimeout(r, 18000));

await page.waitForSelector('[contenteditable="true"]', { timeout: 30000 });
logs.push('composer found');

// 关掉"预览版说明"引导弹窗：它可能比输入框晚渲染，轮询点掉
for (let i = 0; i < 10; i++) {
  const r = await page.evaluate(() => {
    const targets = [...document.querySelectorAll('button,[role="button"]')]
      .filter((e) => ['继续', '知道了', '开始使用'].includes((e.innerText || '').trim()));
    if (!targets.length) return 'none';
    const el = targets[targets.length - 1];
    el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    el.click();
    return 'clicked:' + (el.innerText || '').trim();
  }).catch(() => 'err');
  logs.push('modal try' + i + ' -> ' + r);
  if (r !== 'none') { await new Promise((res) => setTimeout(res, 2500)); }
  const gone = await page.evaluate(() => ![...document.querySelectorAll('button,[role="button"]')]
    .some((e) => ['继续', '知道了', '开始使用'].includes((e.innerText || '').trim())));
  if (gone) { logs.push('modal gone'); break; }
  await new Promise((res) => setTimeout(res, 1500));
}

const focusOk = await page.evaluate(() => {
  const el = document.querySelector('[contenteditable="true"]');
  el.focus();
  el.click();
  return document.activeElement === el;
});
logs.push('focused: ' + focusOk);

await page.keyboard.type(text, { delay: 30 });
await new Promise((r) => setTimeout(r, 800));
const before = await page.evaluate(() => (document.querySelector('[contenteditable="true"]') || {}).innerText || '');
logs.push('composer text: ' + before.slice(0, 80));

await page.keyboard.press('Enter');

let replied = false;
let lastLen = 0;
for (let i = 0; i < 30; i++) {
  await new Promise((r) => setTimeout(r, 5000));
  const t = await page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' '));
  logs.push('t+' + ((i + 1) * 5) + 's len=' + t.length);
  if (t.length > lastLen + 20 && /收到|你好|Hello|确认|手机/.test(t.slice(-400))) { replied = true; break; }
  lastLen = Math.max(lastLen, t.length);
}

await page.screenshot({ path: out });
const tail = await page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' ').slice(-500));
console.log(JSON.stringify({ replied, tail }, null, 2));
console.log('--- logs ---');
console.log(logs.join('\n'));
await browser.close();
