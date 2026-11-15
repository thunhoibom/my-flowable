<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Plus } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime } from '@/utils/format'
import { serviceEnvironmentApi, type ServiceEnvironment } from '../api'
import {
  authTypes,
  emptyForm,
  secretLabel,
  toForm,
  toSettings,
  validate,
  type EnvironmentForm,
} from '../environmentForm'

const authStore = useAuthStore()
const queryClient = useQueryClient()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const listQuery = useQuery({
  queryKey: computed(() => queryKeys.serviceEnvironments(tenantCode.value)),
  queryFn: serviceEnvironmentApi.list,
})
const records = computed(() => listQuery.data.value ?? [])

const dialogVisible = ref(false)
const editing = ref<ServiceEnvironment>()
const form = reactive<EnvironmentForm>(emptyForm())
const authLabel = (type: string) => authTypes.find((item) => item.value === type)?.label ?? type
const overriddenOperations = computed(() =>
  Object.entries(editing.value?.settings.operations ?? {})
    .filter(([, auth]) => auth.mode === 'OVERRIDE')
    .map(([operationKey]) => operationKey),
)
const stored = (name: string) => editing.value?.storedSecrets.includes(name) ?? false
const keepPlaceholder = (name: string) =>
  stored(name) ? 'Đã lưu. Để trống để giữ nguyên' : 'Chưa lưu'

const saveMutation = useMutation({
  mutationFn: async () => {
    const problem = validate(form)
    if (problem) throw new Error(problem)
    return serviceEnvironmentApi.save(form.serviceKey.trim(), toSettings(form, editing.value))
  },
  onSuccess: async () => {
    dialogVisible.value = false
    ElMessage.success('Đã lưu cấu hình môi trường')
    await queryClient.invalidateQueries({
      queryKey: queryKeys.serviceEnvironments(tenantCode.value),
    })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

function create() {
  editing.value = undefined
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}
function edit(entry: ServiceEnvironment) {
  editing.value = entry
  Object.assign(form, toForm(entry))
  dialogVisible.value = true
}
async function remove(entry: ServiceEnvironment) {
  const confirmed = await confirmAction(
    `Xóa cấu hình của “${entry.serviceKey}”? Các bước gọi dịch vụ này sẽ lỗi cho tới khi cấu hình lại, và không phát hành được quy trình dùng nó.`,
    'Xóa cấu hình môi trường',
    { confirmButtonText: 'Xóa', cancelButtonText: 'Hủy', type: 'error' },
  )
  if (!confirmed) return
  try {
    await serviceEnvironmentApi.delete(entry.serviceKey)
    ElMessage.success('Đã xóa cấu hình')
    await queryClient.invalidateQueries({
      queryKey: queryKeys.serviceEnvironments(tenantCode.value),
    })
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}
</script>

<template>
  <div class="management-page page-stack">
    <PageHeader
      eyebrow="Vận hành"
      title="Cấu hình môi trường"
      description="Base URL và khóa của từng dịch vụ trên môi trường này. Đổi ở đây có hiệu lực ngay, không cần phát hành lại."
    />

    <section class="page-actions compact-filter">
      <div class="action-buttons page-primary-actions">
        <el-button type="primary" @click="create"><Plus :size="17" />Thêm cấu hình</el-button>
      </div>
    </section>

    <section class="table-panel">
      <el-table v-loading="listQuery.isFetching.value" :data="records" height="100%">
        <el-table-column prop="serviceKey" label="Dịch vụ" min-width="160" />
        <el-table-column label="Base URL" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.settings.baseUrl || '—' }}</template>
        </el-table-column>
        <el-table-column label="Xác thực" width="150">
          <template #default="{ row }">{{ authLabel(row.settings.sharedAuth.type) }}</template>
        </el-table-column>
        <el-table-column label="Khóa đã lưu" min-width="200">
          <template #default="{ row }">
            <el-tag
              v-for="name in row.storedSecrets"
              :key="name"
              size="small"
              effect="plain"
              class="environment-secret-tag"
              >{{ name }}</el-tag
            >
            <span v-if="!row.storedSecrets.length">—</span>
          </template>
        </el-table-column>
        <el-table-column label="Cập nhật" width="200">
          <template #default="{ row }"
            >{{ formatDateTime(row.updatedAt) }}<br /><small>{{ row.updatedBy }}</small></template
          >
        </el-table-column>
        <el-table-column label="Thao tác" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">Sửa</el-button>
            <el-button link type="danger" @click="remove(row)">Xóa</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <ListEmptyState
            title="Chưa có cấu hình"
            description="Quy trình chỉ phát hành được khi mọi dịch vụ nó gọi đã có cấu hình ở đây."
          />
        </template>
      </el-table>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `Sửa cấu hình ${editing.serviceKey}` : 'Thêm cấu hình'"
      width="600px"
    >
      <el-form class="dialog-form" label-position="top" @submit.prevent>
        <el-form-item label="Mã dịch vụ" required>
          <el-input
            v-model="form.serviceKey"
            :disabled="Boolean(editing)"
            maxlength="128"
            placeholder="Như trong catalog dịch vụ"
          />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="Xác thực dùng chung">
          <el-select v-model="form.authType">
            <el-option
              v-for="auth in authTypes"
              :key="auth.value"
              :label="auth.label"
              :value="auth.value"
            />
          </el-select>
        </el-form-item>
        <template v-if="form.authType === 'API_KEY'">
          <el-form-item label="Vị trí API key">
            <el-select v-model="form.apiKeyLocation">
              <el-option label="Header" value="HEADER" />
              <el-option label="Query" value="QUERY" />
            </el-select>
          </el-form-item>
          <el-form-item :label="form.apiKeyLocation === 'QUERY' ? 'Tên query' : 'Header API Key'">
            <el-input v-model="form.apiKeyHeader" placeholder="X-Api-Key" />
          </el-form-item>
        </template>
        <el-form-item v-if="form.authType === 'BEARER_VARIABLE'" label="Biến Bearer">
          <el-input v-model="form.bearerVariable" />
        </el-form-item>
        <template v-if="form.authType === 'OAUTH2_CLIENT'">
          <el-form-item label="URL token"><el-input v-model="form.tokenUrl" /></el-form-item>
          <el-form-item label="Client id"><el-input v-model="form.clientId" /></el-form-item>
          <el-form-item label="Scope"><el-input v-model="form.scopes" /></el-form-item>
        </template>
        <el-form-item v-if="form.authType === 'MTLS'" label="Chứng chỉ PEM">
          <el-input
            v-model="form.certificatePem"
            type="textarea"
            :rows="4"
            :placeholder="keepPlaceholder('shared.certificatePem')"
          />
        </el-form-item>
        <el-form-item v-if="form.authType === 'HMAC'" label="Header chữ ký">
          <el-input v-model="form.signatureHeader" />
        </el-form-item>
        <template v-if="form.authType === 'AWS_SIG_V4'">
          <el-form-item label="Access key">
            <el-input v-model="form.accessKey" :placeholder="keepPlaceholder('shared.accessKey')" />
          </el-form-item>
          <el-form-item label="Region"><el-input v-model="form.region" /></el-form-item>
          <el-form-item label="Service"><el-input v-model="form.awsService" /></el-form-item>
        </template>
        <el-form-item
          v-if="form.authType !== 'NONE' && form.authType !== 'BEARER_VARIABLE'"
          :label="secretLabel(form.authType)"
        >
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="keepPlaceholder('shared.secret')"
          />
        </el-form-item>
        <el-alert
          v-if="overriddenOperations.length"
          type="info"
          :closable="false"
          show-icon
          :title="`Thao tác có khóa riêng, giữ nguyên khi lưu: ${overriddenOperations.join(', ')}`"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :loading="saveMutation.isPending.value"
          @click="saveMutation.mutate()"
          >Lưu</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.environment-secret-tag {
  margin: 2px 4px 2px 0;
}
</style>
