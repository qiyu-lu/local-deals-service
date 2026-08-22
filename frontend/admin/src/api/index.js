import axios from 'axios'
import { ElMessage } from 'element-plus'
import { clearAdminSession, getAdminToken } from '../auth/adminSession'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use(config => {
  const token = getAdminToken()
  if (token) {
    config.headers.Authorization = token.startsWith('Bearer ') ? token : `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

request.interceptors.response.use(response => {
  const result = response.data
  if (result && result.success === false) {
    const errorMsg = result.errorMsg || '请求失败'
    ElMessage.error(errorMsg)
    return Promise.reject(new Error(errorMsg))
  }
  return result
}, error => {
  const isLoginRequest = error.config?.url === '/admin/auth/login'
  if (error.response?.status === 401 && !isLoginRequest) {
    ElMessage.error('后台登录已过期，请重新登录')
    clearAdminSession()
    window.dispatchEvent(new CustomEvent('admin:unauthorized'))
  } else {
    const errorMsg = error.response?.data?.errorMsg || error.message || '网络错误'
    ElMessage.error(errorMsg)
  }
  return Promise.reject(error)
})

export function resultData(result) {
  return result && Object.prototype.hasOwnProperty.call(result, 'data') ? result.data : result
}

export function resultPage(result) {
  const payload = resultData(result)
  if (Array.isArray(payload)) {
    return { records: payload, total: Number(result?.total ?? payload.length) }
  }
  const records = payload?.records || payload?.list || payload?.items || []
  return { records, total: Number(result?.total ?? payload?.total ?? records.length) }
}

export function login(data) {
  return request.post('/admin/auth/login', data)
}

export function getAdminMe() {
  return request.get('/admin/auth/me')
}

export function logout() {
  return request.post('/admin/auth/logout')
}

export function createAdminWsTicket() {
  return request.post('/admin/auth/ws-ticket')
}

export function getAdminShops(params) {
  return request.get('/admin/shops', { params })
}

export function getAdminShop(id) {
  return request.get(`/admin/shops/${id}`)
}

export function createAdminShop(data) {
  return request.post('/admin/shops', data)
}

export function updateAdminShop(data) {
  const { id, ...payload } = data
  return request.put(`/admin/shops/${id}`, payload)
}

export function getAdminVouchers(shopId) {
  return request.get(`/admin/shops/${shopId}/vouchers`)
}

export function createAdminSeckillVoucher(shopId, data) {
  return request.post(`/admin/shops/${shopId}/vouchers/seckill`, data)
}

export function getMarketingTags(params) {
  return request.get('/admin/marketing/tags', { params })
}

export function createMarketingTag(data) {
  return request.post('/admin/marketing/tags', data)
}

export function getMarketingCampaigns(params) {
  return request.get('/admin/marketing/campaigns', { params })
}

export function createMarketingCampaign(data) {
  return request.post('/admin/marketing/campaigns', data)
}

export function updateMarketingCampaignStatus(id, data) {
  return request.put(`/admin/marketing/campaigns/${id}/status`, data)
}

export function grantMarketingCampaign(id, data) {
  return request.post(`/admin/marketing/campaigns/${id}/grants`, data)
}

export default request
