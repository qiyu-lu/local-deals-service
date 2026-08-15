<template>
  <div class="page-container forbidden-page">
    <el-result icon="warning" title="暂无可访问的管理功能" sub-title="请联系商户主账户或平台管理员分配权限">
      <template #extra>
        <el-button type="primary" @click="handleLogout">退出登录</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { clearAdminSession } from '../auth/adminSession'
import { logout } from '../api'

const router = useRouter()

async function handleLogout() {
  try {
    await logout()
  } catch {
    // 本地会话仍需清除。
  } finally {
    clearAdminSession()
    router.replace('/login')
  }
}
</script>

<style scoped>
.forbidden-page {
  min-height: calc(100vh - 56px);
  display: grid;
  place-items: center;
}
</style>
