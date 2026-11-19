// Renders the static route map (map-export.html) to PNG at 2x and 3x, light and dark.
// Usage: node map-export.js <out-dir>, then python3 quantize.py <out-dir> <dir for the imageset>
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
(async () => {
  const out = process.argv[2] || path.join(__dirname, 'map-out');
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  for (const theme of ['light', 'dark']) {
    for (const scale of [2, 3]) {
      const page = await browser.newPage({ viewport: { width: 390, height: 1020 }, deviceScaleFactor: scale });
      page.on('pageerror', e => console.log('pageerror:', e.message));
      await page.goto('file://' + path.join(__dirname, 'map-export.html') + '#' + theme);
      await page.waitForTimeout(150);
      if (theme === 'light' && scale === 2) console.log('route', JSON.stringify(await page.evaluate(() => window.routeInfo)));
      const file = path.join(out, `route-map-${theme}@${scale}x.png`);
      await (await page.$('#map')).screenshot({ path: file });
      console.log(file, fs.statSync(file).size);
      await page.close();
    }
  }
  await browser.close();
})();
