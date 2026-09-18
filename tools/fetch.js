// Downloads a URL to a file. Used by build.ps1 because PowerShell's own TLS
// stack (schannel) cannot complete a handshake in this confined environment,
// while Node's bundled OpenSSL can.
//
// usage: node fetch.js <url> <outFile>
const fs = require('fs');
const https = require('https');
const http = require('http');
const path = require('path');

const [url, outFile] = process.argv.slice(2);
if (!url || !outFile) {
  console.error('usage: node fetch.js <url> <outFile>');
  process.exit(2);
}

fs.mkdirSync(path.dirname(outFile), { recursive: true });

function get(target, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 8) return reject(new Error('too many redirects'));
    const mod = target.startsWith('https:') ? https : http;
    const req = mod.get(target, { timeout: 120000, headers: { 'user-agent': 'note-export-build' } }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
        res.resume();
        const next = new URL(res.headers.location, target).toString();
        return resolve(get(next, redirects + 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error('HTTP ' + res.statusCode + ' for ' + target));
      }
      resolve(res);
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
  });
}

async function download(url, outFile) {
  const res = await get(url);
  const tmp = outFile + '.part';
  await new Promise((resolve, reject) => {
    const ws = fs.createWriteStream(tmp);
    res.pipe(ws);
    ws.on('finish', resolve);
    ws.on('error', reject);
    res.on('error', reject);
  });
  fs.renameSync(tmp, outFile);
  const size = fs.statSync(outFile).size;
  if (size === 0) { fs.unlinkSync(outFile); throw new Error('empty download: ' + url); }
  return size;
}

(async () => {
  // The direct route to dl.google.com drops a connection often enough that a
  // build would otherwise fail for no reason; retry a few times before giving up.
  let lastError;
  for (let attempt = 1; attempt <= 4; attempt++) {
    try {
      const size = await download(url, outFile);
      console.log('downloaded ' + url + ' -> ' + outFile + ' (' + size + ' bytes)');
      return;
    } catch (e) {
      lastError = e;
      const detail = e.message || e.code || String(e);
      console.error('attempt ' + attempt + ' failed: ' + detail);
      if (/HTTP 4\d\d/.test(detail)) break;
      await new Promise((r) => setTimeout(r, 2000 * attempt));
    }
  }
  console.error('fetch failed: ' + (lastError && (lastError.message || lastError.code) || 'unknown error'));
  process.exit(1);
})();
