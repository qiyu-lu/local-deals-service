<template>
  <div class="page-container">
    <h2 class="page-title">仪表盘</h2>
    <p class="page-subtitle">系统核心指标概览</p>

    <div class="stat-grid">
      <el-card class="stat-card" shadow="hover">
        <div class="stat-icon-wrap" style="background-color: rgba(30, 64, 175, 0.1);">
          <el-icon :size="24" color="#1E40AF"><Shop /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">商铺总数</div>
          <div class="stat-value tabular-num">{{ shopTypeCount }}</div>
        </div>
      </el-card>

      <el-card class="stat-card" shadow="hover">
        <div class="stat-icon-wrap" style="background-color: rgba(217, 119, 6, 0.1);">
          <el-icon :size="24" color="#D97706"><Ticket /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">今日秒杀订单</div>
          <div class="stat-value tabular-num">
            {{ seckillSold }}<span class="stat-value-sub">/{{ seckillTotal }}</span>
          </div>
          <div class="stat-extra">库存已售完</div>
        </div>
      </el-card>

      <el-card class="stat-card" shadow="hover">
        <div class="stat-icon-wrap" style="background-color: rgba(22, 163, 74, 0.1);">
          <el-icon :size="24" color="#16A34A"><Refresh /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">ES 同步状态</div>
          <el-tag type="success" effect="dark" round class="status-tag">Canal 运行中</el-tag>
        </div>
      </el-card>

      <el-card class="stat-card" shadow="hover">
        <div class="stat-icon-wrap" :style="{ backgroundColor: wsConnected ? 'rgba(22, 163, 74, 0.1)' : 'rgba(220, 38, 38, 0.1)' }">
          <el-icon :size="24" :color="wsConnected ? '#16A34A' : '#DC2626'"><Connection /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">WebSocket 在线用户</div>
          <div class="stat-value tabular-num">{{ wsConnected ? 1 : 0 }}</div>
          <div class="stat-extra">{{ wsConnected ? '当前已连接' : '当前未连接' }}</div>
        </div>
      </el-card>
    </div>

    <el-card class="chart-card" shadow="never">
      <div ref="chartRef" class="chart-container"></div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Shop, Ticket, Refresh, Connection } from '@element-plus/icons-vue'
import { getShopTypeList } from '../api'
import { useAdminWs } from '../composables/useAdminWs'

const shopTypeCount = ref(14)
const seckillSold = ref(100)
const seckillTotal = ref(100)
const { connected: wsConnected } = useAdminWs()

const chartRef = ref(null)
let chartInstance = null

async function loadShopTypeCount() {
  try {
    const res = await getShopTypeList()
    const list = res.data || res || []
    if (Array.isArray(list) && list.length > 0) {
      shopTypeCount.value = list.length
    }
  } catch (e) {
    // 接口异常时保留 Mock 值 14
  }
}

function buildChartData() {
  const hours = []
  const orders = []
  for (let h = 0; h < 24; h++) {
    hours.push(`${h}时`)
    let base = 5 + Math.random() * 10
    const peak10 = Math.exp(-Math.pow(h - 10, 2) / 4) * 80
    const peak20 = Math.exp(-Math.pow(h - 20, 2) / 4) * 95
    base += peak10 + peak20
    orders.push(Math.round(base + Math.random() * 5))
  }
  return { hours, orders }
}

function initChart() {
  if (!window.echarts || !chartRef.value) return
  chartInstance = window.echarts.init(chartRef.value)
  const { hours, orders } = buildChartData()
  const option = {
    title: {
      text: '今日秒杀订单趋势（模拟数据）',
      textStyle: { fontSize: 14, fontWeight: 600, color: '#1e293b' }
    },
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 60, bottom: 30 },
    xAxis: {
      type: 'category',
      data: hours,
      axisLine: { lineStyle: { color: '#cbd5e1' } }
    },
    yAxis: {
      type: 'value',
      name: '订单数',
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    series: [
      {
        name: '秒杀订单数',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: orders,
        itemStyle: { color: '#1E40AF' },
        areaStyle: {
          color: new window.echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(30, 64, 175, 0.3)' },
            { offset: 1, color: 'rgba(30, 64, 175, 0.02)' }
          ])
        },
        lineStyle: { width: 2, color: '#1E40AF' }
      }
    ]
  }
  chartInstance.setOption(option)
}

function handleResize() {
  chartInstance && chartInstance.resize()
}

onMounted(async () => {
  loadShopTypeCount()
  await nextTick()
  initChart()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
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

.stat-value-sub {
  font-size: 14px;
  font-weight: 400;
  color: #94a3b8;
}

.stat-extra {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}

.status-tag {
  margin-top: 4px;
}

.chart-card {
  border-radius: 10px;
}

.chart-container {
  width: 100%;
  height: 360px;
}
</style>
