<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-header">
        <h1 class="login-title">LocalDeals Admin</h1>
        <p class="login-subtitle">本地生活服务管理平台</p>
      </div>
      <el-form :model="form" class="login-form" @submit.prevent>
        <el-form-item>
          <el-input
            v-model="form.phone"
            type="tel"
            placeholder="请输入手机号"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            :prefix-icon="Lock"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            class="login-btn"
            :loading="loading"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '../api'

const router = useRouter()
const loading = ref(false)

const form = ref({
  phone: '17612345678',
  password: ''
})

async function handleLogin() {
  if (!form.value.phone) {
    ElMessage.warning('请输入手机号')
    return
  }
  loading.value = true
  try {
    const res = await login({ phone: form.value.phone, code: '123456' })
    const token = res.data || res.token || res
    if (token) {
      localStorage.setItem('token', typeof token === 'string' ? token : JSON.stringify(token))
      ElMessage.success('登录成功')
      router.push('/')
    } else {
      ElMessage.error('登录失败，未获取到令牌')
    }
  } catch (err) {
    if (err.response && err.response.status === 401) {
      ElMessage.error('手机号或密码错误')
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  width: 100vw;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1E40AF 0%, #1E293B 100%);
}

.login-card {
  width: 400px;
  border-radius: 12px;
  padding: 12px;
}

.login-header {
  text-align: center;
  margin-bottom: 24px;
}

.login-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-primary);
  margin: 0 0 8px 0;
}

.login-subtitle {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}

.login-form {
  margin-top: 16px;
}

.login-btn {
  width: 100%;
  background-color: var(--color-primary);
  border-color: var(--color-primary);
}
</style>
