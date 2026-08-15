<template>
  <div class="page-container">
    <h2 class="page-title">仪表盘</h2>
    <p class="page-subtitle">当前后台账户及授权范围概览</p>

    <div class="stat-grid">
      <el-card class="stat-card" shadow="hover">
        <div class="stat-icon-wrap primary-icon">
          <el-icon :size="24" color="#1E40AF"><User /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">当前账户</div>
          <div class="stat-text">{{ principal?.displayName || principal?.username || '-' }}</div>
          <div class="stat-extra">{{ principal?.username || '-' }}</div>
        </div>
      </el-card>

      <el-card class="stat-card" shadow="hover">
        <div class="stat-icon-wrap amber-icon">
          <el-icon :size="24" color="#D97706"><Key /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">已授权功能</div>
          <div class="stat-value tabular-num">{{ principal?.permissions?.length || 0 }}</div>
          <div class="stat-extra">页面展示与后端鉴权使用同一权限码</div>
        </div>
      </el-card>

      <el-card v-if="canReadShops" class="stat-card" shadow="hover">
        <div class="stat-icon-wrap green-icon">
          <el-icon :size="24" color="#16A34A"><Shop /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">授权范围内商铺</div>
          <div class="stat-value tabular-num">{{ shopCount }}</div>
          <div class="stat-extra">{{ scopeText }}</div>
        </div>
      </el-card>

      <el-card v-if="canReadRealtimeOrders" class="stat-card" shadow="hover">
        <div class="stat-icon-wrap" :class="wsConnected ? 'green-icon' : 'red-icon'">
          <el-icon :size="24" :color="wsConnected ? '#16A34A' : '#DC2626'"><Connection /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">实时订单通道</div>
          <el-tag :type="wsConnected ? 'success' : 'info'" effect="dark" round class="status-tag">
            {{ wsConnected ? '已连接' : '未连接' }}
          </el-tag>
          <div class="stat-extra">连接时使用 30 秒一次性票据</div>
        </div>
      </el-card>
    </div>

    <el-alert
      :title="scopeText"
      description="前端权限控制仅用于隐藏入口和防止误操作；服务端仍会对每次请求校验权限与商户数据范围。"
      type="info"
      :closable="false"
      show-icon
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Connection, Key, Shop, User } from '@element-plus/icons-vue'
import { getAdminShops, resultPage } from '../api'
import { useAdminSession } from '../auth/adminSession'
import { useAdminWs } from '../composables/useAdminWs'

const { principal, hasPermission } = useAdminSession()
const { connected: wsConnected } = useAdminWs()
const shopCount = ref('-')
const canReadShops = computed(() => hasPermission('shop:read'))
const canReadRealtimeOrders = computed(() => hasPermission('order:realtime'))
const scopeText = computed(() => principal.value?.scopeType === 'PLATFORM'
  ? '平台数据范围：可按权限访问所有商户资源'
  : `商户数据范围：仅可访问商户 ${principal.value?.merchantId ?? '-'} 的资源`)

async function loadShopCount() {
  if (!canReadShops.value) return
  try {
    const result = await getAdminShops({ current: 1, size: 1 })
    shopCount.value = resultPage(result).total
  } catch {
    shopCount.value = '-'
  }
}

onMounted(loadShopCount)
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon-wrap {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.primary-icon { background-color: rgba(30, 64, 175, 0.1); }
.amber-icon { background-color: rgba(217, 119, 6, 0.1); }
.green-icon { background-color: rgba(22, 163, 74, 0.1); }
.red-icon { background-color: rgba(220, 38, 38, 0.1); }

.stat-body {
  flex: 1;
  min-width: 0;
}

.stat-label {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 6px;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #1e293b;
  line-height: 1.2;
}

.stat-text {
  font-size: 18px;
  font-weight: 700;
  color: #1e293b;
  line-height: 1.3;
}

.stat-extra {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}

.status-tag {
  margin-top: 4px;
}

@media (max-width: 900px) {
  .stat-grid { grid-template-columns: 1fr; }
}
</style>
