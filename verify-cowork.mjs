// 验证：手机端（经网关）能否看到电脑端正在用的会话
import puppeteer from 'puppeteer-core';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const url = process.argv[2];

const logs = [];
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: true,
  args: ['--no-first-run', '--no-default-browser-check', '--disable-gpu'],
});
const page = await browser.newPage();
page.on('console', (m) => { if (m.type() === 'error') logs.push('err: ' + m.text().slice(0, 160)); });
page.on('websocket', (ws) => logs.push('ws: ' + ws.url().slice(0, 90)));

await page.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
// 等应用真正挂载（boot screen 消失），最多 60 秒
for (let i = 0; i < 40; i++) {
  await new Promise((r) => setTimeout(r, 1500));
  const booted = await page.evaluate(() => {
    const t = document.body ? document.body.innerText || '' : '';
    return t.indexOf('Loading plugins') < 0 && t.length > 20;
  }).catch(() => false);
  if (booted) break;
}
await new Promise((r) => setTimeout(r, 4000));

// 关掉可能的引导弹窗
for (let i = 0; i < 6; i++) {
  const r = await page.evaluate(() => {
    const t = [...document.querySelectorAll('button,[role="button"]')]
      .filter((e) => ['继续', '知道了'].includes((e.innerText || '').trim()));
    if (!t.length) return 'none';
    t[t.length - 1].click();
    return 'clicked';
  }).catch(() => 'err');
  if (r === 'none') break;
  await new Promise((res) => setTimeout(res, 1500));
}

const info = await page.evaluate(() => {
  const txt = (document.body.innerText || '').replace(/\s+/g, ' ');
  // 侧边栏里的会话项
  const items = [...document.querySelectorAll('[class*="session" i], [class*="conversation" i], li, a')]
    .map((e) => (e.innerText || '').trim())
    .filter((s) => s.length > 2 && s.length < 60);
  return { text: txt.slice(0, 1200), items: [...new Set(items)].slice(0, 40) };
});

console.log('=== 页面文本（前 700）===');
console.log(info.text.slice(0, 700));
console.log('\n=== 疑似会话条目 ===');
for (const i of info.items.slice(0, 25)) console.log('  - ' + i);
console.log('\n=== 关键判定 ===');
console.log('  含「制作安卓版」: ' + /制作安卓版/.test(info.text));
console.log('  含「确认收到手机消息」: ' + /确认收到手机消息/.test(info.text));
console.log('  含「清理C盘」: ' + /清理C盘/.test(info.text));
console.log('\n=== logs ===');
for (const l of logs.slice(0, 15)) console.log('  ' + l);

await page.screenshot({ path: 'E:\\ai清理\\mobile\\cowork-390.png' });
await browser.close();