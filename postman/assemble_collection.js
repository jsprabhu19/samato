// Assemble the three agent part files into one Postman v2.1.0 collection.
// Usage: node assemble_collection.js
// Output: postman/samato.postman_collection.json

const fs = require('fs');
const path = require('path');

const here = __dirname;
const headerPath = path.join(here, 'agent_d.part.json');
const ePath = path.join(here, 'agent_e.part.json');
const fPath = path.join(here, 'agent_f.part.json');
const outPath = path.join(here, 'samato.postman_collection.json');

const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));

const header = read(headerPath);
const eFolders = read(ePath);
const fFolders = read(fPath);

if (!header.info || !header.event || !header.item) {
    console.error('agent_d.part.json is missing info/event/item — not the header part');
    process.exit(1);
}
if (!Array.isArray(eFolders) || !Array.isArray(fFolders)) {
    console.error('agent_e or agent_f is not a JSON array of folders');
    process.exit(1);
}

const assembled = {
    info: header.info,
    event: header.event,
    item: [...header.item, ...eFolders, ...fFolders],
};

const folders = assembled.item;
const totalRequests = folders.reduce((n, f) => n + (f.item || []).length, 0);

fs.writeFileSync(outPath, JSON.stringify(assembled, null, 2) + '\n', 'utf8');

console.log(`Wrote ${outPath}`);
console.log(`Folders: ${folders.length}`);
for (const f of folders) {
    console.log(`  - ${f.name || '?'}: ${(f.item || []).length} requests`);
}
console.log(`Total requests: ${totalRequests}`);
