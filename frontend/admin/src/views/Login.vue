<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-header">
        <h1 class="login-title">LocalDeals Admin</h1>
        <p class="login-subtitle">商户与平台管理入口</p>
      </div>
      <el-form :model="form" class="login-form" @submit.prevent="handleLogin">
        <el-form-item>
          <el-input
            v-model.trim="form.username"
            name="username"
            autocomplete="username"
            placeholder="后台用户名"
            size="large"
            :prefix-icon="User"
            :disabled="loading"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="current-password"
            placeholder="密码"
            size="large"
            show-password
            :prefix-icon="Lock"
            :disabled="loading"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            native-type="submit"
            type="primary"
            size="large"
            class="login-btn"
            :loading="loading"
          >
            登录
          </el-button>
        </el-form-item>
        <el-alert
          title="消费者短信登录与后台账户已完全隔离"
          type="info"
          :closable="false"
          show-icon
        />
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { login, resultData } from '../api'
import { firstPermittedRoute, setAdminSession } from '../auth/adminSession'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = ref({ username: '', password: '' })

async function handleLogin() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入后台用户名和密码')
    return
  }

  loading.value = true
  try {
    const result = await login(form.value)
    setAdminSession(resultData(result))
    form.value.password = ''
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' &&
      route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//')
      ? route.query.redirect
      : firstPermittedRoute()
    await router.replace(redirect)
  } catch (error) {
    if (!error?.isAxiosError && error?.message?.startsWith('后台登录响应')) {
      ElMessage.error(error.message)
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
