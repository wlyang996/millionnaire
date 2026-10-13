import test from 'node:test'
import assert from 'node:assert/strict'
import { diffSettings, sectionForField, pages } from '../src/admin/navigation.js'
import { boardCell, boardGrid } from '../src/admin/boardGeometry.js'
test('draft comparison preserves nested arrays and exact before/after values',()=>{
  const old={tiers:[{tier:'LOW',basePrice:500,rents:[100,250,450,700]}],announcement:{enabled:false,text:''}}
  const next=structuredClone(old);next.tiers[0].rents[2]=500;next.announcement.enabled=true
  assert.deepEqual(diffSettings(old,next),[{path:'tiers.0.rents.2',before:450,after:500},{path:'announcement.enabled',before:false,after:true}])
  assert.equal(old.tiers[0].rents[2],450)
  assert.deepEqual(diffSettings(old,structuredClone(old)),[])
})
test('added and removed optional settings remain visible in a release review',()=>{
  const changes=diffSettings({team:{aidMaxAmount:300},announcement:null},{team:{aidMaxAmount:400},announcement:{title:'活动'}})
  assert.equal(changes[0].before,300);assert.equal(changes[0].after,400)
  assert.deepEqual(changes[1],{path:'announcement',before:null,after:{title:'活动'}})
  assert.equal(sectionForField('tiers.0.rents.2'),'prices');assert.equal(sectionForField('team.aidMaxAmount'),'team')
})
for(const size of [30,50])test(`${size}-tile preview has a closed clockwise perimeter and matches the client's corner contract`,()=>{
  const grid=boardGrid(size), cells=Array.from({length:size},(_,index)=>boardCell(index,grid))
  assert.equal(new Set(cells.map(cell=>`${cell.col},${cell.row}`)).size,size)
  assert.deepEqual(cells[0],{col:0,row:grid.rows-1})
  assert.deepEqual(cells[grid.cols-1],{col:grid.cols-1,row:grid.rows-1})
  assert.deepEqual(cells[grid.cols+grid.rows-2],{col:grid.cols-1,row:0})
  assert.deepEqual(cells[2*grid.cols+grid.rows-3],{col:0,row:0})
  cells.forEach((cell,index)=>{const next=cells[(index+1)%size];assert.equal(Math.abs(cell.col-next.col)+Math.abs(cell.row-next.row),1)})
  assert.deepEqual(boardCell(size,grid),cells[0]);assert.deepEqual(boardCell(-1,grid),cells.at(-1))
})
test('every existing management section appears once in navigation',()=>{
  assert.deepEqual(new Set(pages.map(page=>page.id)),new Set(['dashboard','names','sets','prices','money','rentRise','room','events','startPick','roundReward','cityEvents','funTitles','series','team','timing','characters','audio','announcement','history']))
  assert.equal(pages.length,19)
})
