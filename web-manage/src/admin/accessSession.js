import { ref } from 'vue'
export const adminIdentity=ref(null)
export function setIdentity(value){adminIdentity.value=value?{user:value.user,permissions:[...(value.permissions||[])],mustChangePassword:!!value.mustChangePassword,expiresAt:value.expiresAt,sessionId:value.sessionId,persistent:value.persistent}:null}
