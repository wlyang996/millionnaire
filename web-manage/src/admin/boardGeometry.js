// Mirrors the client perimeter contract in core/BoardLayout.ts.
export function boardGrid(size) { return size === 50 ? { cols:12, rows:15 } : { cols:8, rows:9 } }
export function boardCell(index, { cols, rows }) {
  const n=2*(cols+rows)-4, i=((index%n)+n)%n
  if(i<cols)return {col:i,row:rows-1}
  if(i<cols+rows-1)return {col:cols-1,row:rows-1-(i-(cols-1))}
  if(i<2*cols+rows-2)return {col:cols-1-(i-(cols+rows-2)),row:0}
  return {col:0,row:i-(2*cols+rows-3)}
}
