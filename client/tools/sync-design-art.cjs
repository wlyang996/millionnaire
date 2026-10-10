/** Copy original PNGs without editing pixels; Cocos generates/preserves their .meta files. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const source = path.join(root, 'design/screens/assets-supplement-v1');
const output = path.join(root, 'client/assets/resources/art');
const sprites = JSON.parse(fs.readFileSync(path.join(source, 'sprites.json'), 'utf8')).sprites;
const paths = new Map();
const entries = [];
const globalOnly = process.argv.includes('--global-only');
if (globalOnly) {
    const old = fs.readFileSync(path.join(root, 'client/assets/scripts/ui/ArtCatalog.ts'), 'utf8');
    for (const m of old.matchAll(/'([^']+)': '([^']+)'/g)) paths.set(m[1], m[2]);
}
const sha = (data) => crypto.createHash('sha256').update(data).digest('hex');
function copy(from, relative, key) {
    if (globalOnly && !relative.startsWith('global_gameplay/')) return;
    const to = path.join(output, relative);
    const data = fs.readFileSync(from);
    fs.mkdirSync(path.dirname(to), { recursive: true });
    if (!fs.existsSync(to) || sha(fs.readFileSync(to)) !== sha(data)) fs.writeFileSync(to, data);
    paths.set(key, 'art/' + relative.replace(/\\/g, '/').replace(/\.png$/, ''));
    entries.push({ key, source: path.relative(root, from).replace(/\\/g, '/'),
        runtime: path.relative(root, to).replace(/\\/g, '/'), sha256: sha(data) });
}
for (const s of sprites) copy(path.join(source, s.file), s.file.replace(/^png\//, ''), s.name);
const informationSource = path.join(root, 'design/screens/assets-information-v1');
if (fs.existsSync(path.join(informationSource, 'sprites.json'))) {
    const information = JSON.parse(fs.readFileSync(path.join(informationSource, 'sprites.json'), 'utf8')).sprites;
    for (const s of information) copy(path.join(informationSource, s.file), 'information/' + path.basename(s.file), s.name);
}
copy(path.join(root, 'design/screens/assets-v3/png/title_wood.png'), 'title_wood.png', 'title_wood');
const completionSource = path.join(root, 'design/screens/assets-ui-completion-v1');
if (fs.existsSync(path.join(completionSource, 'sprites.json'))) {
    const completion = JSON.parse(fs.readFileSync(path.join(completionSource, 'sprites.json'), 'utf8')).sprites;
    for (const s of completion) copy(path.join(completionSource, s.file), 'completion/' + path.basename(s.file), s.name);
}
const luckySource = path.join(root, 'design/screens/assets-lucky-v1');
if (fs.existsSync(path.join(luckySource, 'sprites.json'))) {
    const lucky = JSON.parse(fs.readFileSync(path.join(luckySource, 'sprites.json'), 'utf8')).sprites;
    for (const s of lucky) copy(path.join(luckySource, s.file), 'lucky/' + path.basename(s.file), s.name);
}
const facesSource = path.join(root, 'design/screens/assets-event-faces-v1');
if (fs.existsSync(path.join(facesSource, 'sprites.json'))) {
    const faces = JSON.parse(fs.readFileSync(path.join(facesSource, 'sprites.json'), 'utf8')).sprites;
    for (const s of faces) copy(path.join(facesSource, s.file), 'event_faces/' + path.basename(s.file), s.name);
}
const jailCloseup = path.join(root, 'design/screens/assets-alignment-v1/scene_jail_closeup.png');
if (fs.existsSync(jailCloseup)) copy(jailCloseup, 'alignment/scene_jail_closeup.png', 'scene_jail_closeup');
const globalSource = path.join(root, 'design/screens/assets-global-gameplay-v1');
for (const key of ['reward_gift', 'city_construction']) {
    const file = path.join(globalSource, key + '.png');
    if (fs.existsSync(file)) copy(file, 'global_gameplay/' + key + '.png', key);
}
paths.set('scene_property_low', paths.get('shop_low'));
const catalog = '/** Runtime copies of design/screens; event_art takes precedence for shared card backs. */\n'
    + 'export const ART_PATHS: Record<string, string> = {\n'
    + [...paths].map(([key, value]) => `    '${key}': '${value}',`).join('\n') + '\n};\n';
const target = path.join(root, 'client/assets/scripts/ui/ArtCatalog.ts');
if (fs.readFileSync(target, 'utf8').replace(/^\uFEFF/, '').trimEnd() !== catalog.trimEnd()) fs.writeFileSync(target, catalog);
fs.writeFileSync(path.join(root, 'design/screens/records/client-art-import-' + (globalOnly ? '2026-10-10-global' : '2026-10-06') + '.json'),
    JSON.stringify({ date: globalOnly ? '2026-10-10' : '2026-10-06', status: 'runtime copies for UI preview; original candidates unchanged',
        files: entries.length, resourceKeys: paths.size, entries }, null, 2) + '\n');
console.log(`Verified ${entries.length} PNG copies, ${paths.size} resource keys; original pixels preserved.`);
