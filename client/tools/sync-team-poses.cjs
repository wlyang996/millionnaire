/** Preserve generated pixels; copy independent sprite sheets and disable automatic atlas packing. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const dir = path.join(root, 'design/screens/assets-team-poses-v1/png');
const out = path.join(root, 'client/assets/resources/art/team_poses');
const template = JSON.parse(fs.readFileSync(path.join(root,'client/assets/resources/art/completion/information_host_keke.png.meta'),'utf8'));
const keys = ['team_pose_give','team_pose_receive','team_pose_worry','team_pose_celebrate','team_trophy','team_debt_thought'];
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
fs.mkdirSync(out,{recursive:true});
const entries = keys.map(key => {
    const source = path.join(dir,key+'.png'), dest = path.join(out,key+'.png'), data = fs.readFileSync(source);
    const width = data.readUInt32BE(16), height = data.readUInt32BE(20);
    if (!fs.existsSync(dest) || hash(fs.readFileSync(dest))!==hash(data)) fs.writeFileSync(dest,data);
    const metaPath=dest+'.meta';
    const meta=fs.existsSync(metaPath)?JSON.parse(fs.readFileSync(metaPath,'utf8')):structuredClone(template);
    if(!fs.existsSync(metaPath))meta.uuid=crypto.randomUUID();
    for(const [id,sub] of Object.entries(meta.subMetas)){
        sub.uuid=meta.uuid+'@'+id;sub.displayName=key;
        if(sub.importer==='texture')sub.userData.imageUuidOrDatabaseUri=meta.uuid;
        if(sub.importer==='sprite-frame'){
            Object.assign(sub.userData,{imageUuidOrDatabaseUri:meta.uuid+'@6c48a',isUuid:true,trimX:0,trimY:0,offsetX:0,offsetY:0,width,height,rawWidth:width,rawHeight:height,
                rotated:false,trimType:'none',packable:false});
            sub.userData.vertices={rawPosition:[-width/2,-height/2,0,width/2,-height/2,0,-width/2,height/2,0,width/2,height/2,0],
                indexes:[0,1,2,2,1,3],uv:[0,height,width,height,0,0,width,0],nuv:[0,1,1,1,0,0,1,0],
                minPos:[-width/2,-height/2,0],maxPos:[width/2,height/2,0]};
        }
    }
    fs.writeFileSync(metaPath,JSON.stringify(meta,null,2)+'\n');
    return {key,source:path.relative(root,source).replace(/\\/g,'/'),runtime:path.relative(root,dest).replace(/\\/g,'/'),width,height,sha256:hash(data),cells:key.startsWith('team_pose_')?9:1};
});
const catalogPath=path.join(root,'client/assets/scripts/ui/ArtCatalog.ts');
let catalog=fs.readFileSync(catalogPath,'utf8');
const missing=keys.filter(key=>!catalog.includes("'"+key+"':"));
if(missing.length)catalog=catalog.replace(/\n};\s*$/, '\n'+missing.map(key=>`    '${key}': 'art/team_poses/${key}',`).join('\n')+'\n};\n');
fs.writeFileSync(catalogPath,catalog);
const regions = JSON.parse(fs.readFileSync(path.join(root,'design/screens/assets-team-poses-v1/regions.json'),'utf8'));
fs.writeFileSync(path.join(root,'client/assets/scripts/ui/TeamPoseRegions.ts'),
    '/** Source sprite bounds; excludes disconnected edge fragments without changing PNG pixels. */\n'
    + 'export const TEAM_POSE_REGIONS: Record<string, { x: number; y: number; w: number; h: number }[]> = '
    + JSON.stringify(Object.fromEntries(Object.entries(regions).map(([key,rects])=>[key,rects.map(({x,y,w,h})=>({x,y,w,h}))])),null,2)+';\n');
fs.writeFileSync(path.join(root,'design/screens/assets-team-poses-v1/manifest.json'),JSON.stringify({date:'2026-10-11',status:'approved screen37; runtime integration',grid:{columns:3,rows:3,order:['tangtang','keke','ajie','naicha','akai','yuanyuan','doudou','maomao','robot']},entries},null,2)+'\n');
console.log('Verified '+entries.length+' original PNG copies; 36 poses plus trophy and debt thought bubble.');
