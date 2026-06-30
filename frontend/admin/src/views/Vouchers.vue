<template>
  <div class="page-container">
    <div class="header-row">
      <div>
        <h2 class="page-title">秒杀券管理</h2>
        <p class="page-subtitle">创建与管理限时秒杀优惠券</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">创建秒杀券</el-button>
    </div>

    <div class="query-bar">
      <span class="section-title" style="margin:0">已有秒杀券（商铺 ID：</span>
      <el-input-number v-model="queryShopId" :min="1" style="width:120px" size="small" />
      <el-button size="small" type="primary" @click="loadVouchers">查询</el-button>
    </div>
    <el-empty v-if="!loading && vouchers.length === 0" description="该商铺暂无秒杀券" />
    <div class="voucher-grid">
      <el-card v-for="v in vouchers" :key="v.id" class="voucher-card" shadow="hover">
        <div class="voucher-top">
          <span class="voucher-title">{{ v.title }}</span>
          <el-tag :type="voucherStatusType(v)" size="small">{{ voucherStatusText(v) }}</el-tag>
        </div>
        <p class="voucher-sub">{{ v.subTitle }}</p>
        <div class="voucher-price-row">
          <span class="voucher-price tabular-num">¥{{ (v.payValue / 100).toFixed(2) }}</span>
          <span class="voucher-original tabular-num">¥{{ (v.actualValue / 100).toFixed(2) }}</span>
        </div>
        <div class="voucher-stock-row">
          <span class="tabular-num">剩余库存：{{ v.stock ?? '-' }} 张</span>
          <span>ID: {{ v.id }}</span>
        </div>
        <div class="voucher-time-row">{{ v.beginTime }} ~ {{ v.endTime }}</div>
      </el-card>
    </div>

    <el-dialog v-model="createDialogVisible" title="创建秒杀券" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="商铺 ID">
          <el-input-number v-model="form.shopId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="券标题">
          <el-input v-model="form.title" placeholder="如：100元代金券" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subTitle" placeholder="如：仅限堂食" />
        </el-form-item>
        <el-form-item label="原价（元）">
          <el-input-number v-model="form.actualValueYuan" :min="0" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="秒杀价（元）">
          <el-input-number v-model="form.payValueYuan" :min="0" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="form.stock" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker
            v-model="form.beginTime"
            type="datetime"
            placeholder="选择开始时间"
            style="width: 100%"
            value-format="YYYY-MM-DD HH:mm:ss"
          />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            placeholder="选择结束时间"
            style="width: 100%"
            value-format="YYYY-MM-DD HH:mm:ss"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { createSeckillVoucher, getVoucherList } from '../api'

const createDialogVisible = ref(false)
const submitting = ref(false)
const loading = ref(false)
const queryShopId = ref(1)
const vouchers = ref([])

const defaultForm = () => ({
  shopId: 1,
  title: '',
  subTitle: '',
  actualValueYuan: 100,
  payValueYuan: 80,
  stock: 100,
  beginTime: '',
  endTime: ''
})

const form = ref(defaultForm())

async function loadVouchers() {
  loading.value = true
  try {
    const res = await getVoucherList(queryShopId.value)
    vouchers.value = res.data || res || []
  } catch {
    vouchers.value = []
  } finally {
    loading.value = false
  }
}

function voucherStatusText(v) {
  if (!v.beginTime || !v.endTime) return '普通券'
  const now = Date.now()
  const begin = new Date(v.beginTime).getTime()
  const end = new Date(v.endTime).getTime()
  if (now < begin) return '未开始'
  if (now <= end) return '进行中'
  return '已结束'
}

function voucherStatusType(v) {
  const t = voucherStatusText(v)
  if (t === '进行中') return 'success'
  if (t === '未开始') return 'warning'
  return 'info'
}

function openCreate() {
  form.value = defaultForm()
  createDialogVisible.value = true
}

async function submitCreate() {
  if (!form.value.title) {
    ElMessage.warning('请输入券标题')
    return
  }
  if (!form.value.beginTime || !form.value.endTime) {
    ElMessage.warning('请选择开始和结束时间')
    return
  }
  submitting.value = true
  try {
    const payload = {
      shopId: form.value.shopId,
      title: form.value.title,
      subTitle: form.value.subTitle,
      payValue: Math.round(form.value.payValueYuan * 100),
      actualValue: Math.round(form.value.actualValueYuan * 100),
      stock: form.value.stock,
      beginTime: form.value.beginTime,
      endTime: form.value.endTime
    }
    await createSeckillVoucher(payload)
    ElMessage.success('秒杀券创建成功')
    createDialogVisible.value = false
    queryShopId.value = form.value.shopId
    await loadVouchers()
  } catch {
    // 错误已在拦截器中提示
  } finally {
    submitting.value = false
  }
}

onMounted(loadVouchers)
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  margin: 24px 0 12px 0;
}

.query-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 20px 0 12px 0;
}

.voucher-time-row {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 6px;
}

.voucher-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.voucher-card {
  border-radius: 10px;
}

.voucher-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.voucher-title {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}

.voucher-sub {
  font-size: 12px;
  color: #94a3b8;
  margin: 0 0 12px 0;
}

.voucher-price-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
}

.voucher-price {
  font-size: 22px;
  font-weight: 700;
  color: var(--color-accent);
}

.voucher-original {
  font-size: 13px;
  color: #94a3b8;
  text-decoration: line-through;
}

.voucher-stock-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #64748b;
  margin-top: 8px;
}
</style>
