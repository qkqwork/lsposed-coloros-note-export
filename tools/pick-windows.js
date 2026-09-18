// The manifest lists several archives per package (one per host OS); the first
// is usually the Linux one, so every <url> inside a block must be collected.
const fs = require('fs');
const xml = fs.readFileSync(process.argv[2], 'utf8');
const blocks = xml.split('<remotePackage').slice(1);

function urlsOf(block) {
  return [...block.matchAll(/<url>([^<]+)<\/url>/g)].map((m) => m[1]);
}

for (const block of blocks) {
  const pathMatch = block.match(/path="([^"]+)"/);
  if (!pathMatch) continue;
  const pkg = pathMatch[1];
  if (!/^(build-tools|platforms|platform-tools);/.test(pkg)) continue;
  if (process.argv[3] && !pkg.includes(process.argv[3])) continue;
  const urls = urlsOf(block);
  const win = urls.filter((u) => /windows/i.test(u));
  if (win.length) console.log(pkg, '->', win.join(' '));
}
