import { onBeforeUnmount, ref, watch } from 'vue'
import { draftKey, readRuleDraft, ruleDraft } from './configFiles.js'
export function useRuleDraft(source) {
  const candidate=ref(null),status=ref(''),paused=ref(true)
  let timer,key=null,revision=0
  watch(()=>draftKey(source.identity()),next=>{clearTimeout(timer);revision++;key=next;candidate.value=null;status.value='';paused.value=true},{immediate:true,flush:'sync'})
  function discover() {
    try{candidate.value=readRuleDraft(key?localStorage.getItem(key):null,source.identity())}catch{status.value='本机草稿存储不可用'}
    paused.value=false
  }
  function flush() {
    clearTimeout(timer)
    if(paused.value || !key || !source.canEdit() || !source.form() || !source.dirty())return
    try {
      const value=ruleDraft(source.identity(),source.baseVersion(),source.baseSettings(),source.form(),source.inputs())
      const text=JSON.stringify(value)
      if(new TextEncoder().encode(text).length>2*1024*1024)throw new Error('草稿过大')
      localStorage.setItem(key,text);status.value='本机草稿已保存 · '+new Date(value.updatedAt).toLocaleTimeString('zh-CN',{hour12:false})
    }catch{status.value='本机草稿保存失败，请导出备份后再关闭页面'}
  }
  function pause(){clearTimeout(timer);revision++;paused.value=true}
  function remove(){clearTimeout(timer);candidate.value=null;try{if(key)localStorage.removeItem(key);status.value='本机草稿已清除'}catch{status.value='本机草稿清除失败'}}
  watch(()=>[source.form(),source.inputs(),source.dirty()],()=>{
    if(paused.value || !source.dirty())return
    clearTimeout(timer);const expected=revision;timer=setTimeout(()=>{if(expected===revision)flush()},400)
  },{deep:true,flush:'post'})
  const hidden=()=>{if(document.hidden)flush()}
  window.addEventListener('pagehide',flush);document.addEventListener('visibilitychange',hidden)
  onBeforeUnmount(()=>{flush();clearTimeout(timer);window.removeEventListener('pagehide',flush);document.removeEventListener('visibilitychange',hidden)})
  return {candidate,status,discover,pause,remove,flush,paused}
}
