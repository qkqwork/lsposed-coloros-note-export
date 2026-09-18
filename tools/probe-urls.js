// HEAD-probes candidate archive names, because the SDK manifest only lists the
// Linux archives while the Windows ones follow the same spelling pattern.
const https = require('https');

const base = 'https://dl.google.com/android/repository/';
const names = process.argv.slice(2);

function head(url) {
  return new Promise((resolve) => {
    const req = https.request(url, { method: 'HEAD', timeout: 20000 }, (res) => {
      res.resume();
      resolve(res.statusCode + '  ' + (res.headers['content-length'] || '?') + '  ' + url);
    });
    req.on('error', (e) => resolve('ERR ' + e.message + '  ' + url));
    req.on('timeout', () => { req.destroy(); resolve('TIMEOUT  ' + url); });
    req.end();
  });
}

(async () => {
  for (const n of names) console.log(await head(base + n));
})();
