<template>
  <div class="page-container">
    <div class="header-row">
      <div>
        <h2 class="page-title">定向发券</h2>
        <p class="page-subtitle">人工标签、活动规则与单用户发放；服务端仍是唯一商户隔离边界。</p>
      </div>
      <div class="header-actions">
        <el-input
          v-if="isPlatform"
          v-model="scopeMerchantId"
          placeholder="平台操作需填商户 ID"
          class="scope-input"
          @keyup.enter="loadAll"
        />
        <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <div class="grid">
      <el-card shadow="never">
        <template #header><span class="card-title">标签</span></template>
        <el-form v-if="canWrite" :model="tagForm" inline @submit.prevent>
          <el-form-item>
            <el-input v-model="tagForm.code" placeholder="编码，如 VIP" maxlength="64" />
          </el-form-item>
          <el-form-item>
            <el-input v-model="tagForm.name" placeholder="名称" maxlength="128" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="submitTag">新增</el-button>
          </el-form-item>
        </el-form>
        <el-table :data="tags" size="small" border>
          <el-table-column prop="code" label="编码" />
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="status" label="状态" width="90" />
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">活动</span></template>
        <el-form v-if="canWrite" :model="campaignForm" label-width="90px" class="campaign-form">
          <el-form-item label="券 ID"><el-input v-model="campaignForm.voucherId" /></el-form-item>
          <el-form-item label="活动名称"><el-input v-model="campaignForm.name" /></el-form-item>
          <el-form-item label="发放模式">
            <el-select v-model="campaignForm.grantMode" style="width:100%">
              <el-option label="用户领取 + 管理员发放" value="BOTH" />
              <el-option label="用户领取" value="CLAIM" />
              <el-option label="管理员发放" value="ADMIN" />
            </el-select>
          </el-form-item>
          <el-form-item label="资格类型">
            <el-select v-model="campaignForm.eligibilityType" style="width:100%">
              <el-option label="全部用户" value="ALL" />
              <el-option label="人工标签" value="MANUAL_TAG" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="campaignForm.eligibilityType === 'MANUAL_TAG'" label="标签 ID">
            <el-input v-model="campaignForm.requiredTagId" />
          </el-form-item>
          <el-form-item label="时间窗">
            <div class="time-row">
              <el-input v-model="campaignForm.beginTime" placeholder="YYYY-MM-DD HH:mm:ss" />
              <span>至</span>
              <el-input v-model="campaignForm.endTime" placeholder="YYYY-MM-DD HH:mm:ss" />
            </div>
          </el-form-item>
          <el-form-item label="额度"><el-input-number v-model="campaignForm.quotaTotal" :min="1" /></el-form-item>
          <el-button type="primary" @click="submitCampaign">创建 DRAFT 活动</el-button>
        </el-form>

        <el-table :data="campaigns" size="small" border class="campaign-table">
          <el-table-column prop="name" label="活动" min-width="150" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="额度" width="100">
            <template #default="{ row }">{{ row.grantedCount ?? 0 }} / {{ row.quotaTotal }}</template>
          </el-table-column>
          <el-table-column label="规则版本" width="100" prop="ruleVersion" />
          <el-table-column v-if="canWrite" label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'DRAFT' || row.status === 'PAUSED'"
                type="success"
                link
                @click="activate(row)"
              >激活</el-button>
              <el-button
                v-if="row.status === 'ACTIVE'"
                type="warning"
                link
                @click="pause(row)"
              >暂停</el-button>
              <el-button v-if="row.status === 'ACTIVE' || row.status === 'PAUSED'" type="danger" link @click="close(row)">关闭</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-card v-if="canWrite" shadow="never" class="grant-card">
      <template #header><span class="card-title">单用户管理员发放</span></template>
      <el-form :model="grantForm" inline @submit.prevent>
        <el-form-item label="活动">
          <el-select v-model="grantForm.campaignId" placeholder="选择活动" style="width:240px">
            <el-option v-for="campaign in campaigns" :key="campaign.id" :label="campaign.name" :value="campaign.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户 ID"><el-input v-model="grantForm.userId" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submitGrant">发放一张</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  createMarketingCampaign,
  createMarketingTag,
  getMarketingCampaigns,
  getMarketingTags,
  grantMarketingCampaign,
  updateMarketingCampaignStatus,
  resultData
} from '../api'
import { useAdminSession } from '../auth/adminSession'

const { principal, hasPermission } = useAdminSession()
const canWrite = computed(() => hasPermission('marketing:write'))
const isPlatform = computed(() => principal.value?.scopeType === 'PLATFORM')
const scopeMerchantId = ref('')
const tags = ref([])
const campaigns = ref([])
const tagForm = ref({ code: '', name: '' })
const campaignForm = ref({
  voucherId: '',
  name: '',
  grantMode: 'BOTH',
  eligibilityType: 'ALL',
  requiredTagId: '',
  beginTime: '',
  endTime: '',
  quotaTotal: 10
})
const grantForm = ref({ campaignId: '', userId: '' })

function listPayload(result) {
  const value = resultData(result)
  return Array.isArray(value) ? value : value?.records || value?.list || []
}

function scopeParams() {
  return scopeMerchantId.value ? { merchantId: scopeMerchantId.value } : undefined
}

function scopePayload(payload) {
  return scopeMerchantId.value ? { ...payload, merchantId: scopeMerchantId.value } : payload
}

async function loadAll() {
  if (isPlatform.value && !scopeMerchantId.value) {
    tags.value = []
    campaigns.value = []
    return
  }
  try {
    const [tagResult, campaignResult] = await Promise.all([
      getMarketingTags(scopeParams()),
      getMarketingCampaigns(scopeParams())
    ])
    tags.value = listPayload(tagResult)
    campaigns.value = listPayload(campaignResult)
  } catch {
    tags.value = []
    campaigns.value = []
  }
}

async function submitTag() {
  if (!tagForm.value.code || !tagForm.value.name) {
    ElMessage.warning('请填写标签编码和名称')
    return
  }
  await createMarketingTag(scopePayload({ ...tagForm.value }))
  tagForm.value = { code: '', name: '' }
  ElMessage.success('标签已创建')
  await loadAll()
}

async function submitCampaign() {
  const form = campaignForm.value
  if (!form.voucherId || !form.name || !form.beginTime || !form.endTime) {
    ElMessage.warning('请完整填写券、名称和时间窗')
    return
  }
  await createMarketingCampaign(scopePayload({
    voucherId: form.voucherId,
    name: form.name,
    grantMode: form.grantMode,
    eligibilityType: form.eligibilityType,
    requiredTagId: form.eligibilityType === 'MANUAL_TAG' ? form.requiredTagId : null,
    beginTime: form.beginTime,
    endTime: form.endTime,
    quotaTotal: form.quotaTotal
  }))
  ElMessage.success('活动已创建为 DRAFT')
  await loadAll()
}

async function changeStatus(row, status) {
  await updateMarketingCampaignStatus(row.id, scopePayload({
    expectedStatus: row.status,
    expectedRuleVersion: row.ruleVersion,
    status
  }))
  await loadAll()
}

function activate(row) { return changeStatus(row, 'ACTIVE') }
function pause(row) { return changeStatus(row, 'PAUSED') }
function close(row) { return changeStatus(row, 'CLOSED') }

async function submitGrant() {
  const row = campaigns.value.find(campaign => String(campaign.id) === String(grantForm.value.campaignId))
  if (!row || !grantForm.value.userId) {
    ElMessage.warning('请选择活动并填写用户 ID')
    return
  }
  await grantMarketingCampaign(row.id, scopePayload({
    userId: grantForm.value.userId,
    expectedRuleVersion: row.ruleVersion
  }))
  ElMessage.success('单用户发放请求已提交')
  await loadAll()
}

onMounted(loadAll)
</script>

<style scoped>
.header-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.header-actions { display: flex; align-items: center; gap: 8px; }
.scope-input { width: 180px; }
.grid { display: grid; grid-template-columns: minmax(280px, 0.8fr) minmax(520px, 1.6fr); gap: 16px; }
.card-title { font-weight: 600; }
.campaign-form { max-width: 620px; }
.time-row { display: flex; align-items: center; gap: 8px; width: 100%; }
.time-row .el-input { min-width: 0; }
.campaign-table { margin-top: 18px; }
.grant-card { margin-top: 16px; }
@media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
</style>
