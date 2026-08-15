import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useAdminWs } from '../composables/useAdminWs'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.authorization = token
  }
  return config
}, error => {
  return Promise.reject(error)
})

request.interceptors.response.use(response => {
  const data = response.data
  if (data && data.success === false) {
    const errorMsg = data.errorMsg || '请求失败'
    ElMessage.error(errorMsg)
    return Promise.reject(new Error(errorMsg))
  }
  return data
}, error => {
  if (error.response && error.response.status === 401) {
    ElMessage.error('登录已过期，请重新登录')
    useAdminWs().disconnect()
    localStorage.removeItem('token')
    router.push('/login')
  } else {
    const errorMsg = (error.response && error.response.data && error.response.data.errorMsg) || error.message || '网络错误'
    ElMessage.error(errorMsg)
  }
  return Promise.reject(error)
})

// 发送验证码
export function sendCode(phone) {
  return request.post(`/user/code?phone=${phone}`)
}

// 用户登录
export function login(data) {
  return request.post('/user/login', data)
}

// 注销当前登录态
export function logout() {
  return request.post('/user/logout')
}

// 按类型获取商铺
export function getShopsByType(params) {
  return request.get('/shop/of/type', { params })
}

// ES 搜索商铺
export function searchShops(params) {
  return request.get('/shop/search', { params })
}

// 商铺详情
export function getShopDetail(id) {
  return request.get(`/shop/${id}`)
}

// 更新商铺
export function updateShop(data) {
  return request.put('/shop', data)
}

// 查询秒杀券列表
export function getVoucherList(shopId) {
  return request.get(`/voucher/list/${shopId}`)
}

// 创建秒杀券
export function createSeckillVoucher(data) {
  return request.post('/voucher/seckill', data)
}

// 热门博客
export function getHotBlogs(params) {
  return request.get('/blog/hot', { params })
}

// 商铺类型列表
export function getShopTypeList() {
  return request.get('/shop-type/list')
}

export default request
