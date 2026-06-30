<template>
  <div class="page-container">
    <h2 class="page-title">实时秒杀订单流</h2>
    <p class="page-subtitle">WebSocket 实时推送，秒杀成功后毫秒级到达</p>

    <div class="conn-bar">
      <div class="conn-status">
        <span class="status-dot" :class="statusClass"></span>
        <span class="status-text">{{ statusText }}</span>
      </div>
      <el-button
        :type="connected ? 'danger' : 'primary'"
        :icon="connected ? CircleClose : Connection"
        :loading="connecting"
        @click="toggleConnection"
      >
        {{ connected ? '断开连接' : '连接 WebSocket' }}
      </el-button>
    </div>

    <div class="order-list-wrap">
      <el-empty v-if="orders.length === 0" description="暂无订单推送" />
      <transition-group v-else name="slide-down" tag="div" class="order-list">
        <div v-for="order in orders" :key="order.key" class="order-card">
          <div class="order-card-left">
            <el-icon :size="20" :color="order.success ? '#16A34A' : '#DC2626'">
              <CircleCheck v-if="order.success" />
              <CircleClose v-else />
            </el-icon>
          </div>
          <div class="order-card-body">
            <div class="order-card-row">
              <span class="order-id tabular-num">订单号：{{ order.orderId ?? '-' }}</span>
              <el-tag :type="order.success ? 'success' : 'danger'" size="small" effect="dark">
                {{ order.success ? '成功' : '失败' }}
              </el-tag>
            </div>
            <div class="order-card-row sub">
              <span class="tabular-num">优惠券 ID：{{ order.voucherId ?? '-' }}</span>
              <span class="order-time tabular-num">{{ order.time }}</span>
            </div>
            <div v-if="order.message" class="order-message">{{ order.message }}</div>
          </div>
        </div>
      </transition-group>
    </div>

    <div class="mock-bar">
      <el-button @click="pushMockOrder">模拟秒杀推送（测试用）</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, CircleClose, CircleCheck } from '@element-plus/icons-vue'

const connected = ref(false)
const connecting = ref(false)
const orders = ref([])
let ws = null
let keyCounter = 0

const statusClass = computed(() => {
  if (connected.value) return 'dot-green'
  if (connecting.value) return 'dot-gray'
  return 'dot-red'
})

const statusText = computed(() => {
  if (connected.value) return '已连接'
  if (connecting.value) return '连接中...'
  return '未连接'
})

function addOrder(payload) {
  keyCounter += 1
  orders.value.unshift({
    key: keyCounter,
    orderId: payload.orderId,
    voucherId: payload.voucherId,
    success: payload.success,
    message: payload.message,
    time: new Date().toLocaleTimeString()
  })
  if (orders.value.length > 50) {
    orders.value.pop()
  }
}

function toggleConnection() {
  if (connected.value) {
    disconnect()
  } else {
    connect()
  }
}

function connect() {
  const token = localStorage.getItem('token')
  if (!token) {
    ElMessage.warning('未获取到登录令牌，请先登录')
    return
  }
  connecting.value = true
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const host = 'localhost:8083'
  const url = `${protocol}://${host}/ws/connect?token=${encodeURIComponent(token)}`

  try {
    ws = new WebSocket(url)
  } catch (e) {
    connecting.value = false
    ElMessage.error('WebSocket 连接失败')
    return
  }

  ws.onopen = () => {
    connecting.value = false
    connected.value = true
    ElMessage.success('WebSocket 连接成功')
  }

  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data)
      if (msg.type === 'SECKILL_RESULT') {
        addOrder(msg)
      }
    } catch (err) {
      // 忽略非 JSON 消息
    }
  }

  ws.onerror = () => {
    connecting.value = false
  }

  ws.onclose = () => {
    connecting.value = false
    connected.value = false
  }
}

function disconnect() {
  if (ws) {
    ws.close()
    ws = null
  }
  connected.value = false
  connecting.value = false
}

function pushMockOrder() {
  const success = Math.random() > 0.2
  addOrder({
    orderId: Date.now(),
    voucherId: Math.floor(Math.random() * 10) + 1,
    success,
    message: success ? '秒杀成功，订单已生成' : '库存不足，秒杀失败'
  })
}

onBeforeUnmount(() => {
  disconnect()
})
</script>

<style scoped>
.conn-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #ffffff;
  border-radius: 10px;
  padding: 16px 20px;
  margin-bottom: 16px;
}

.conn-status {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.dot-green {
  background-color: var(--color-success);
  box-shadow: 0 0 0 4px rgba(22, 163, 74, 0.2);
}

.dot-red {
  background-color: var(--color-danger);
  box-shadow: 0 0 0 4px rgba(220, 38, 38, 0.15);
}

.dot-gray {
  background-color: #94a3b8;
  box-shadow: 0 0 0 4px rgba(148, 163, 184, 0.15);
}

.status-text {
  font-size: 14px;
  color: #1e293b;
  font-weight: 500;
}

.order-list-wrap {
  background-color: #ffffff;
  border-radius: 10px;
  padding: 16px;
  min-height: 300px;
  max-height: 60vh;
  overflow-y: auto;
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.order-card {
  display: flex;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px 14px;
  background-color: #fafcff;
}

.order-card-left {
  display: flex;
  align-items: center;
}

.order-card-body {
  flex: 1;
  min-width: 0;
}

.order-card-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #1e293b;
}

.order-card-row.sub {
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
}

.order-id {
  font-weight: 600;
}

.order-message {
  margin-top: 6px;
  font-size: 12px;
  color: #475569;
  background-color: #f1f5f9;
  border-radius: 4px;
  padding: 4px 8px;
}

.mock-bar {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}

.slide-down-enter-active {
  transition: all 0.35s ease;
}

.slide-down-enter-from {
  opacity: 0;
  transform: translateY(-16px);
}

.slide-down-leave-active {
  transition: all 0.3s ease;
}

.slide-down-leave-to {
  opacity: 0;
}

.slide-down-move {
  transition: transform 0.35s ease;
}
</style>
