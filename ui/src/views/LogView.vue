<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import {
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VTag,
  VSpace,
  Dialog,
  Toast,
} from '@halo-dev/components'
import RiRefreshLine from '~icons/ri/refresh-line'
import RiDeleteBinLine from '~icons/ri/delete-bin-line'
import RiSendPlaneLine from '~icons/ri/send-plane-line'

interface RefreshLog {
  metadata: {
    name: string
    creationTimestamp: string
  }
  spec: {
    provider: string
    triggerType: string
    postName: string
    postTitle: string
    urls: string[]
    success: boolean
    taskId: string
    message: string
    requestTime: string
    responseTime: string
    duration: number
  }
}

const logs = ref<RefreshLog[]>([])
const loading = ref(false)
const refreshUrl = ref('')
const refreshing = ref(false)

const fetchLogs = async () => {
  loading.value = true
  try {
    const { data } = await axiosInstance.get('/apis/cdn-cache.halo.run/v1alpha1/logs?limit=100')
    logs.value = data
  } catch (e) {
    console.error('获取日志失败', e)
    Toast.error('获取日志失败')
  } finally {
    loading.value = false
  }
}

const deleteLog = async (name: string) => {
  Dialog.warning({
    title: '确认删除',
    description: '确定要删除这条日志吗？',
    confirmType: 'danger',
    onConfirm: async () => {
      try {
        await axiosInstance.delete(`/apis/cdn-cache.halo.run/v1alpha1/logs/${name}`)
        Toast.success('删除成功')
        fetchLogs()
      } catch (e) {
        Toast.error('删除失败')
      }
    },
  })
}

const clearAllLogs = async () => {
  Dialog.warning({
    title: '确认清空',
    description: '确定要清空所有日志吗？此操作不可恢复。',
    confirmType: 'danger',
    onConfirm: async () => {
      try {
        await axiosInstance.delete('/apis/cdn-cache.halo.run/v1alpha1/logs')
        Toast.success('清空成功')
        fetchLogs()
      } catch (e) {
        Toast.error('清空失败')
      }
    },
  })
}

const manualRefresh = async () => {
  if (!refreshUrl.value.trim()) {
    Toast.warning('请输入要刷新的 URL')
    return
  }
  
  refreshing.value = true
  try {
    const urls = refreshUrl.value.split('\n').map(u => u.trim()).filter(u => u)
    const { data } = await axiosInstance.post('/apis/cdn-cache.halo.run/v1alpha1/refresh', { urls })
    if (data.success) {
      Toast.success('刷新成功，任务ID: ' + data.taskId)
      refreshUrl.value = ''
      fetchLogs()
    } else {
      Toast.error('刷新失败: ' + data.message)
    }
  } catch (e: any) {
    Toast.error('请求失败: ' + (e.response?.data?.message || e.message))
  } finally {
    refreshing.value = false
  }
}

const formatTime = (time: string) => {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

const getTriggerTypeText = (type: string) => {
  const map: Record<string, string> = {
    POST_PUBLISH: '文章发布',
    POST_UPDATE: '文章更新',
    PAGE_UPDATE: '页面更新',
    COMMENT: '评论审核',
    REPLY: '回复审核',
    MANUAL: '手动刷新',
  }
  return map[type] || type
}

const getProviderText = (provider: string) => {
  const map: Record<string, string> = {
    ALIYUN: '阿里云 CDN',
    ALIYUN_ESA: '阿里云 ESA',
    TENCENT: '腾讯云 CDN',
    TENCENT_EDGEONE: '腾讯云 EdgeOne',
    CLOUDFLARE: 'Cloudflare',
  }
  return map[provider] || provider
}

onMounted(() => {
  fetchLogs()
})
</script>

<template>
  <div class="cdn-log-tab">
    <!-- 手动刷新区域 -->
    <VCard title="手动刷新 CDN 缓存" class="refresh-card">
      <div class="refresh-form">
        <textarea
          v-model="refreshUrl"
          placeholder="输入要刷新的 URL，每行一个&#10;例如：&#10;https://example.com/&#10;https://example.com/archives/hello"
          rows="3"
          class="url-input"
        ></textarea>
        <VButton type="primary" :loading="refreshing" @click="manualRefresh">
          <template #icon>
            <RiSendPlaneLine />
          </template>
          刷新缓存
        </VButton>
      </div>
    </VCard>

    <!-- 日志列表 -->
    <VCard :body-class="['!p-0']" title="刷新日志">
      <template #actions>
        <VSpace>
          <VButton size="sm" @click="fetchLogs">
            <template #icon>
              <RiRefreshLine />
            </template>
            刷新
          </VButton>
          <VButton size="sm" type="danger" @click="clearAllLogs" :disabled="logs.length === 0">
            <template #icon>
              <RiDeleteBinLine />
            </template>
            清空
          </VButton>
        </VSpace>
      </template>

      <VLoading v-if="loading" />

      <VEmpty v-else-if="logs.length === 0" title="暂无日志" message="还没有 CDN 刷新记录" />

      <div v-else class="log-list">
        <div v-for="log in logs" :key="log.metadata.name" class="log-item">
          <div class="log-header">
            <div class="log-info">
              <VTag :theme="log.spec.success ? 'primary' : 'default'" :class="{ 'tag-error': !log.spec.success }">
                {{ log.spec.success ? '成功' : '失败' }}
              </VTag>
              <VTag>{{ getProviderText(log.spec.provider) }}</VTag>
              <VTag>{{ getTriggerTypeText(log.spec.triggerType) }}</VTag>
              <span class="log-time">{{ formatTime(log.spec.requestTime) }}</span>
              <span v-if="log.spec.duration" class="log-duration">耗时 {{ log.spec.duration }}ms</span>
            </div>
            <VButton size="xs" type="danger" @click="deleteLog(log.metadata.name)">
              删除
            </VButton>
          </div>

          <div v-if="log.spec.postTitle" class="log-post">
            文章: {{ log.spec.postTitle }}
          </div>

          <div class="log-urls">
            <div class="urls-label">刷新 URL:</div>
            <div class="urls-list">
              <code v-for="(url, idx) in log.spec.urls" :key="idx">{{ url }}</code>
            </div>
          </div>

          <div v-if="log.spec.taskId" class="log-task">
            任务 ID: {{ log.spec.taskId }}
          </div>

          <div v-if="!log.spec.success && log.spec.message" class="log-error">
            错误: {{ log.spec.message }}
          </div>
        </div>
      </div>
    </VCard>
  </div>
</template>

<style lang="scss" scoped>
.cdn-log-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.refresh-card {
  margin-bottom: 0;
}

.refresh-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.url-input {
  width: 100%;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-family: monospace;
  font-size: 13px;
  resize: vertical;
  
  &:focus {
    outline: none;
    border-color: #3b82f6;
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
  }
}

.log-list {
  padding: 16px;
  max-height: 500px;
  overflow-y: auto;
}

.log-item {
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  margin-bottom: 12px;
  background: #fff;

  &:last-child {
    margin-bottom: 0;
  }
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.log-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.log-time {
  color: #6b7280;
  font-size: 13px;
}

.log-duration {
  color: #9ca3af;
  font-size: 12px;
}

.log-post {
  color: #374151;
  font-size: 14px;
  margin-bottom: 8px;
}

.log-urls {
  margin-bottom: 8px;

  .urls-label {
    color: #6b7280;
    font-size: 13px;
    margin-bottom: 4px;
  }

  .urls-list {
    display: flex;
    flex-direction: column;
    gap: 4px;

    code {
      background: #f3f4f6;
      padding: 4px 8px;
      border-radius: 4px;
      font-size: 12px;
      color: #1f2937;
      word-break: break-all;
    }
  }
}

.log-task {
  color: #6b7280;
  font-size: 12px;
  margin-bottom: 4px;
}

.log-error {
  color: #dc2626;
  font-size: 13px;
  background: #fef2f2;
  padding: 8px;
  border-radius: 4px;
}

.tag-error {
  background: #fee2e2 !important;
  color: #dc2626 !important;
}
</style>
