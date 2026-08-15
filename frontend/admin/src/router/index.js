import { createRouter, createWebHashHistory } from 'vue-router'
import { getAdminMe, resultData } from '../api'
import {
  clearAdminSession,
  firstPermittedRoute,
  getAdminToken,
  hasAdminPermission,
  isAdminSessionValidated,
  setAdminPrincipal
} from '../auth/adminSession'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    children: [
      {
        path: '',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { permission: 'dashboard:read' }
      },
      {
        path: 'shops',
        name: 'Shops',
        component: () => import('../views/Shops.vue'),
        meta: { permission: 'shop:read' }
      },
      {
        path: 'vouchers',
        name: 'Vouchers',
        component: () => import('../views/Vouchers.vue'),
        meta: { permission: 'voucher:read' }
      },
      {
        path: 'realtime',
        name: 'Realtime',
        component: () => import('../views/Realtime.vue'),
        meta: { permission: 'order:realtime' }
      },
      {
        path: 'forbidden',
        name: 'Forbidden',
        component: () => import('../views/Forbidden.vue')
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

let validationPromise = null

async function ensureAdminSession() {
  if (!getAdminToken()) return false
  if (isAdminSessionValidated()) return true
  if (!validationPromise) {
    validationPromise = getAdminMe()
      .then(result => {
        setAdminPrincipal(resultData(result))
        return true
      })
      .catch(() => {
        clearAdminSession()
        return false
      })
      .finally(() => {
        validationPromise = null
      })
  }
  return validationPromise
}

router.beforeEach(async to => {
  if (to.meta.public) {
    if (!getAdminToken()) return true
    return await ensureAdminSession() ? firstPermittedRoute() : true
  }

  if (!getAdminToken()) return { path: '/login', query: { redirect: to.fullPath } }
  if (!await ensureAdminSession()) return { path: '/login', query: { redirect: to.fullPath } }

  const permission = to.meta.permission
  if (permission && !hasAdminPermission(permission)) {
    const target = firstPermittedRoute()
    return target === to.path ? '/forbidden' : target
  }
  return true
})

export default router
