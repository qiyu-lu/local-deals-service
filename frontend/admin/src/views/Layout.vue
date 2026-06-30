<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo-area">
        <span class="logo-text">LocalDeals Admin</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="side-menu"
        background-color="#1E293B"
        text-color="#cbd5e1"
        active-text-color="#ffffff"
        router
      >
        <el-menu-item index="/">
          <el-icon><Odometer /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/shops">
          <el-icon><Shop /></el-icon>
          <span>商铺管理</span>
        </el-menu-item>
        <el-menu-item index="/vouchers">
          <el-icon><Ticket /></el-icon>
          <span>秒杀券管理</span>
        </el-menu-item>
        <el-menu-item index="/realtime">
          <el-icon><Connection /></el-icon>
          <span>实时订单</span>
        </el-menu-item>
      </el-menu>
    </aside>
    <div class="main">
      <header class="header-bar">
        <div class="header-left"></div>
        <div class="header-right">
          <el-button type="danger" plain size="small" @click="handleLogout">退出登录</el-button>
        </div>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Odometer, Shop, Ticket, Connection } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)

function handleLogout() {
  ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    localStorage.removeItem('token')
    ElMessage.success('已退出登录')
    router.push('/login')
  }).catch(() => {})
}
</script>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
}

.sidebar {
  width: 220px;
  flex-shrink: 0;
  background-color: var(--color-sidebar-bg);
  display: flex;
  flex-direction: column;
}

.logo-area {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo-text {
  color: #ffffff;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.side-menu {
  border-right: none;
  flex: 1;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.header-bar {
  height: 56px;
  background-color: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  flex-shrink: 0;
}

.content {
  flex: 1;
  overflow-y: auto;
  background-color: var(--color-bg);
}
</style>
