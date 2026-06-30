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
            :disabled="codeSent"
          />
        </el-form-item>
        <el-form-item v-if="codeSent">
          <el-input
            v-model="form.code"
            placeholder="输入验证码（查看 Spring Boot 日志）"
            size="large"
            maxlength="6"
            :prefix-icon="Key"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            v-if="!codeSent"
            type="primary"
            size="large"
            class="login-btn"
            :loading="loading"
            @click="handleSendCode"
          >
            获取验证码
          </el-button>
          <div v-else style="width:100%;display:flex;gap:8px;">
            <el-button size="large" style="flex:1" @click="codeSent=false;form.code=''">重新发送</el-button>
            <el-button type="primary" size="large" style="flex:2" :loading="loading" @click="handleLogin">登录</el-button>
          </div>
        </el-form-item>
        <el-alert v-if="codeSent"
          title="验证码已生成，请在应用日志中查找：验证码是：XXXXXX"
          type="info" :closable="false" show-icon style="margin-top:-8px" />
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Key } from '@element-plus/icons-vue'
import { login, sendCode } from '../api'

const router = useRouter()
const loading = ref(false)
const codeSent = ref(false)

const form = ref({
  phone: '17612345678',
  code: ''
})

async function handleSendCode() {
  if (!form.value.phone || form.value.phone.length !== 11) {
    ElMessage.warning('请输入11位手机号')
    return
  }
  loading.value = true
  try {
    await sendCode(form.value.phone)
    codeSent.value = true
    ElMessage.success('验证码已发送，请查看应用日志')
  } catch { /* interceptor already shows error */ } finally {
    loading.value = false
  }
}

async function handleLogin() {
  if (!form.value.code) {
    ElMessage.warning('请输入验证码')
    return
  }
  loading.value = true
  try {
    const res = await login({ phone: form.value.phone, code: form.value.code })
    const token = res.data || res.token || res
    if (token) {
      localStorage.setItem('token', typeof token === 'string' ? token : JSON.stringify(token))
      ElMessage.success('登录成功')
      router.push('/')
    } else {
      ElMessage.error('登录失败，未获取到令牌')
    }
  } catch { /* interceptor already shows error */ } finally {
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
