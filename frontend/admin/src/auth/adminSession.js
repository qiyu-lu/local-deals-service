import { computed, readonly, ref } from 'vue'

const TOKEN_KEY = 'admin_token'
const PRINCIPAL_KEY = 'admin_principal'

function loadPrincipal() {
  const stored = localStorage.getItem(PRINCIPAL_KEY)
  if (!stored) return null
  try {
    return normalizePrincipal(JSON.parse(stored))
  } catch {
    localStorage.removeItem(PRINCIPAL_KEY)
    return null
  }
}

function normalizePrincipal(value) {
  if (!value || typeof value !== 'object') return null
  const permissions = Array.isArray(value.permissions)
    ? value.permissions.filter(permission => typeof permission === 'string')
    : []
  return { ...value, permissions }
}

const token = ref(localStorage.getItem(TOKEN_KEY) || '')
const principal = ref(loadPrincipal())
let validatedToken = ''

export function getAdminToken() {
  return token.value
}

export function getAdminPrincipal() {
  return principal.value
}

export function setAdminSession(session) {
  const nextToken = typeof session?.token === 'string' ? session.token.trim() : ''
  const nextPrincipal = normalizePrincipal(session?.principal)
  if (!nextToken || !nextPrincipal) {
    throw new Error('后台登录响应缺少令牌或账户信息')
  }

  token.value = nextToken
  principal.value = nextPrincipal
  validatedToken = nextToken
  localStorage.setItem(TOKEN_KEY, nextToken)
  localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(nextPrincipal))
}

export function setAdminPrincipal(value) {
  const nextPrincipal = normalizePrincipal(value)
  if (!nextPrincipal) throw new Error('后台账户信息无效')
  principal.value = nextPrincipal
  validatedToken = token.value
  localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(nextPrincipal))
}

export function clearAdminSession() {
  token.value = ''
  principal.value = null
  validatedToken = ''
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(PRINCIPAL_KEY)
}

export function isAdminSessionValidated() {
  return Boolean(token.value && validatedToken === token.value)
}

export function hasAdminPermission(permission) {
  if (!permission) return true
  return Boolean(principal.value?.permissions?.includes(permission))
}

export function firstPermittedRoute() {
  const candidates = [
    ['dashboard:read', '/'],
    ['shop:read', '/shops'],
    ['voucher:read', '/vouchers'],
    ['marketing:read', '/marketing'],
    ['order:realtime', '/realtime']
  ]
  return candidates.find(([permission]) => hasAdminPermission(permission))?.[1] || '/forbidden'
}

export function useAdminSession() {
  return {
    token: readonly(token),
    principal: readonly(principal),
    authenticated: computed(() => Boolean(token.value)),
    hasPermission: hasAdminPermission
  }
}
