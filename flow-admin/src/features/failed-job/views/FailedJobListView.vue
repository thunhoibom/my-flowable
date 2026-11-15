<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { RefreshCw, Search } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePagination from '@/components/TablePagination.vue'
import { canOperateInstances, canWriteDefinitions } from '@/features/auth'
import { useAuthStore } from '@/stores/auth'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime } from '@/utils/format'
import { failedJobApi, type FailedJob } from '../api'

const authStore = useAuthStore()
const queryClient = useQueryClient()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const canRetry = computed(() => canOperateInstances(authStore.user))
const canDelete = computed(() => canWriteDefinitions(authStore.user))

const query = reactive({ processInstanceId: '', pageNum: 1, pageSize: 20 })
const applied = ref({ ...query })
const jobsQuery = useQuery({
  queryKey: computed(() => queryKeys.failedJobPage(tenantCode.value, applied.value)),
  queryFn: () =>
    failedJobApi.page({
      ...applied.value,
      processInstanceId: applied.value.processInstanceId.trim() || undefined,
    }),
})
const records = computed(() => jobsQuery.data.value?.records ?? [])
const total = computed(() => jobsQuery.data.value?.total ?? 0)

const stacktraceVisible = ref(false)
const stacktraceJob = ref<FailedJob>()
const stacktraceQuery = useQuery({
  queryKey: computed(() => [
    ...queryKeys.failedJobs(tenantCode.value),
    'stacktrace',
    stacktraceJob.value?.jobId,
  ]),
  queryFn: () => failedJobApi.stacktrace(stacktraceJob.value!.jobId),
  enabled: computed(() => stacktraceVisible.value && Boolean(stacktraceJob.value)),
})

async function refresh() {
  await queryClient.invalidateQueries({ queryKey: queryKeys.failedJobs(tenantCode.value) })
}
const retryMutation = useMutation({
  mutationFn: (job: FailedJob) => failedJobApi.retry(job.jobId),
  onSuccess: async () => {
    ElMessage.success('Đã đưa job về hàng chờ chạy lại')
    await refresh()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})
const deleteMutation = useMutation({
  mutationFn: (job: FailedJob) => failedJobApi.delete(job.jobId),
  onSuccess: async () => {
    ElMessage.success('Đã xóa job')
    await refresh()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

function search() {
  query.pageNum = 1
  applied.value = { ...query }
}
function reset() {
  Object.assign(query, { processInstanceId: '', pageNum: 1 })
  applied.value = { ...query }
}
function changePage(pageNum: number, pageSize: number) {
  query.pageNum = pageNum
  query.pageSize = pageSize
  applied.value = { ...query }
}
function showStacktrace(job: FailedJob) {
  stacktraceJob.value = job
  stacktraceVisible.value = true
}
async function retry(job: FailedJob) {
  const confirmed = await confirmAction(
    `Chạy lại bước “${job.activityName || job.activityId}” với 3 lần thử?`,
    'Chạy lại job',
    { confirmButtonText: 'Chạy lại', cancelButtonText: 'Hủy', type: 'warning' },
  )
  if (confirmed) retryMutation.mutate(job)
}
async function remove(job: FailedJob) {
  const confirmed = await confirmAction(
    'Xóa job thì bước này sẽ không bao giờ chạy và instance sẽ đứng ở đây. Tiếp tục?',
    'Xóa job',
    { confirmButtonText: 'Xóa', cancelButtonText: 'Hủy', type: 'error' },
  )
  if (confirmed) deleteMutation.mutate(job)
}
</script>

<template>
  <div class="management-page page-stack">
    <PageHeader
      eyebrow="Vận hành"
      title="Job lỗi"
      description="Các bước chạy nền (gọi dịch vụ, hẹn giờ) đã thử hết số lần mà vẫn lỗi."
    />

    <section class="page-actions compact-filter">
      <el-form class="filter-form" inline @submit.prevent="search"
        ><el-form-item label="Mã instance"
          ><el-input v-model="query.processInstanceId" clearable placeholder="Mã instance"
        /></el-form-item>
        <el-form-item class="filter-form__actions"
          ><el-button type="primary" native-type="submit"><Search :size="16" />Tìm</el-button
          ><el-button @click="reset"><RefreshCw :size="16" />Đặt lại</el-button></el-form-item
        ></el-form
      >
    </section>

    <section class="table-panel">
      <el-table v-loading="jobsQuery.isFetching.value" :data="records" height="100%">
        <el-table-column prop="processDefinitionKey" label="Quy trình" min-width="160" />
        <el-table-column label="Bước" min-width="160">
          <template #default="{ row }">{{ row.activityName || row.activityId }}</template>
        </el-table-column>
        <el-table-column
          prop="exceptionMessage"
          label="Lỗi"
          min-width="260"
          show-overflow-tooltip
        />
        <el-table-column label="Instance" min-width="180">
          <template #default="{ row }">
            <RouterLink
              v-if="row.processInstanceId"
              :to="{ name: 'process-instance-detail', params: { id: row.processInstanceId } }"
              >{{ row.processInstanceId }}</RouterLink
            >
          </template>
        </el-table-column>
        <el-table-column label="Thời điểm" width="175">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="Thao tác" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showStacktrace(row)">Xem lỗi</el-button>
            <el-button v-if="canRetry" link type="success" @click="retry(row)">Chạy lại</el-button>
            <el-button v-if="canDelete" link type="danger" @click="remove(row)">Xóa</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <ListEmptyState title="Không có job lỗi" description="Mọi bước chạy nền đều đang ổn." />
        </template>
      </el-table>
      <TablePagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        aria-label="Phân trang job lỗi"
        @change="changePage"
      />
    </section>

    <el-dialog v-model="stacktraceVisible" title="Chi tiết lỗi" width="760px">
      <p v-if="stacktraceJob?.exceptionMessage">{{ stacktraceJob.exceptionMessage }}</p>
      <pre v-loading="stacktraceQuery.isFetching.value" class="failed-job-stacktrace">{{
        stacktraceQuery.data.value || 'Không có stack trace'
      }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.failed-job-stacktrace {
  max-height: 420px;
  overflow: auto;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
