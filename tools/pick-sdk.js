// Picks the exact archive URLs for a platform and the newest build-tools from
// Google's SDK repository manifest, rather than guessing at file names.
const fs = require('fs');

const xml = fs.readFileSync(process.argv[2], 'utf8');

// Each <remotePackage path="..."> block carries its own <archive><url>.
const blocks = xml.split('<remotePackage').slice(1);
const found = {};
for (const block of blocks) {
  const pathMatch = block.match(/path="([^"]+)"/);
  const urlMatch = block.match(/<url>([^<]+)<\/url>/);
  if (!pathMatch || !urlMatch) continue;
  const pkg = pathMatch[1];
  if (!urlMatch[1].endsWith('.zip')) continue;
  (found[pkg] = found[pkg] || []).push(urlMatch[1]);
}

for (const want of ['platforms;android-36', 'platforms;android-35', 'build-tools;36.0.0', 'build-tools;35.0.0']) {
  if (found[want]) console.log(want, '->', found[want].join(' '));
}

const bt = Object.keys(found)
  .filter((k) => k.startsWith('build-tools;'))
  .map((k) => ({ key: k, ver: k.split(';')[1] }))
  .filter((e) => /^\d+(\.\d+)*$/.test(e.ver))
  .sort((a, b) => {
    const av = a.ver.split('.').map(Number);
    const bv = b.ver.split('.').map(Number);
    for (let i = 0; i < 3; i++) if ((av[i] || 0) !== (bv[i] || 0)) return (av[i] || 0) - (bv[i] || 0);
    return 0;
  });
console.log('\nnewest build-tools with a windows zip:');
for (const e of bt.slice(-8)) {
  const win = found[e.key].find((u) => u.includes('windows'));
  console.log(' ', e.key, win || found[e.key].join(' '));
}
