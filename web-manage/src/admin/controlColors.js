const tones = {
  workbench:'violet', dashboard:'blue', liveRooms:'green', players:'blue',games:'cyan',releases:'rose',media:'amber',
  names:'green', sets:'blue', prices:'amber', money:'amber', rentRise:'amber',
  room:'blue', events:'rose', startPick:'violet', roundReward:'amber', cityEvents:'cyan', funTitles:'rose',
  series:'blue', team:'green', experience:'cyan', timing:'cyan', characters:'rose', audio:'cyan',
  announcement:'amber', history:'blue', accounts:'blue', roles:'blue', audit:'blue',
  sessions:'blue', password:'blue', myPermissions:'blue',
}
const groupTones={overview:'violet',economy:'amber',rules:'blue',characters:'rose',audio:'cyan',announcement:'amber',versions:'green',system:'blue',operations:'cyan'}
export const pageTone = id => tones[id] || 'violet'
export const groupTone = id => groupTones[id] || 'violet'
