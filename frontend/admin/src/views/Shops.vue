<template>
  <div class="page-container">
    <h2 class="page-title">商铺管理</h2>
    <p class="page-subtitle">商铺信息查询与维护</p>

    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="keyword"
          placeholder="搜索商铺名称/地址"
          class="search-input"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <span class="search-hint">仅查询当前账户授权范围</span>
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="pagedList" border stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="商铺名称" min-width="160" />
      <el-table-column prop="address" label="地址" min-width="220" />
      <el-table-column label="人均价格" width="120">
        <template #default="{ row }">
          <span class="tabular-num">¥{{ row.avgPrice ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="评分" width="100">
        <template #default="{ row }">
          <span class="tabular-num">{{ row.score ? (row.score / 10).toFixed(1) : '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="距离" width="120">
        <template #default="{ row }">
          <span class="tabular-num">{{ row.distance != null ? row.distance.toFixed(0) + 'm' : '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="canWriteShop" label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </div>

    <el-dialog v-model="editDialogVisible" title="编辑商铺信息" width="480px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="商铺名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="editForm.address" />
        </el-form-item>
        <el-form-item label="营业时间">
          <el-input v-model="editForm.openHours" placeholder="如：10:00-22:00" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getAdminShops, resultPage, updateAdminShop } from '../api'
import { useAdminSession } from '../auth/adminSession'

const loading = ref(false)
const submitting = ref(false)
const keyword = ref('')
const shopList = ref([])
const currentPage = ref(1)
const pageSize = ref(5)
const total = ref(0)
const { hasPermission } = useAdminSession()
const canWriteShop = computed(() => hasPermission('shop:write'))

const pagedList = computed(() => shopList.value)

async function loadShops(page = 1) {
  loading.value = true
  try {
    const result = await getAdminShops({
      keyword: keyword.value || undefined,
      current: page,
      size: pageSize.value
    })
    const pageResult = resultPage(result)
    shopList.value = Array.isArray(pageResult.records) ? pageResult.records : []
    total.value = pageResult.total
  } catch (e) {
    shopList.value = []
  } finally {
    loading.value = false
  }
}

async function handleSearch() {
  if (!keyword.value) {
    currentPage.value = 1
    loadShops(1)
    return
  }
  currentPage.value = 1
  await loadShops(1)
}

function handleRefresh() {
  keyword.value = ''
  currentPage.value = 1
  loadShops(1)
}

function handlePageChange(page) {
  loadShops(page)
}

const editDialogVisible = ref(false)
const editForm = ref({ id: null, name: '', address: '', openHours: '' })

function openEdit(row) {
  if (!canWriteShop.value) return
  editForm.value = {
    id: row.id,
    name: row.name,
    address: row.address,
    openHours: row.openHours || ''
  }
  editDialogVisible.value = true
}

async function submitEdit() {
  if (!canWriteShop.value) return
  submitting.value = true
  try {
    await updateAdminShop(editForm.value)
    editDialogVisible.value = false
    ElMessage.success('商铺信息修改成功')
    loadShops(currentPage.value)
  } catch (e) {
    // 错误已在拦截器中提示
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadShops(1)
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-input {
  width: 280px;
}

.search-hint {
  font-size: 12px;
  color: #94a3b8;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
