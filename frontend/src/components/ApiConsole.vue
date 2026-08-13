<script setup lang="ts">
import { reactive, computed } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { http } from '../api/client'

type ParamIn = 'path' | 'query' | 'body'
interface EpParam {
  key: string
  label: string
  in: ParamIn
}
interface Endpoint {
  id: string
  method: 'GET' | 'POST' | 'DELETE'
  path: string
  label: string
  desc?: string
  params: EpParam[]
  confirm?: boolean
}
interface EpGroup {
  name: string
  items: Endpoint[]
}

const groups: EpGroup[] = [
  {
    name: '系统',
    items: [
      { id: 'ping', method: 'GET', path: '/ping/health', label: '健康检查', desc: '后端存活 + 版本', params: [] },
      { id: 'overview', method: 'GET', path: '/console/overview', label: '控制台总览', desc: '状态 + 统计 + 配置', params: [] },
    ],
  },
  {
    name: '入库',
    items: [
      { id: 'docs', method: 'GET', path: '/docs', label: '文档列表', params: [] },
      { id: 'doc-content', method: 'GET', path: '/docs/{docId}/content', label: '文档内容预览', params: [{ key: 'docId', label: 'docId', in: 'path' }] },
      { id: 'doc-del', method: 'DELETE', path: '/docs/{docId}', label: '删除文档', params: [{ key: 'docId', label: 'docId', in: 'path' }], confirm: true },
      { id: 'pipelines', method: 'GET', path: '/ingestion/pipelines', label: 'Pipeline 列表', params: [] },
    ],
  },
  {
    name: '对话',
    items: [
      { id: 'conv-list', method: 'GET', path: '/chat/conversations', label: '会话列表', params: [] },
      { id: 'conv-new', method: 'POST', path: '/chat/conversations', label: '新建会话', params: [{ key: 'title', label: '标题（可空）', in: 'body' }] },
      { id: 'conv-del', method: 'DELETE', path: '/chat/conversations/{conversationId}', label: '删除会话', params: [{ key: 'conversationId', label: 'conversationId', in: 'path' }], confirm: true },
    ],
  },
  {
    name: '评测',
    items: [
      { id: 'eval-ds', method: 'GET', path: '/eval/datasets', label: '评测集列表', params: [] },
      { id: 'eval-run', method: 'POST', path: '/eval/runs', label: '触发评测', params: [{ key: 'datasetId', label: 'datasetId', in: 'body' }] },
    ],
  },
  {
    name: '诊断',
    items: [
      { id: 'diagnose', method: 'POST', path: '/diagnose/trace', label: '日志诊断', desc: 'traceId → 查日志 + 检索 + LLM 根因', params: [{ key: 'traceId', label: 'traceId', in: 'query' }] },
    ],
  },
  {
    name: '存储',
    items: [
      { id: 'storage-url', method: 'GET', path: '/storage/url', label: '文件预签名 URL', params: [{ key: 'key', label: 'key', in: 'query' }] },
    ],
  },
]

interface EpState {
  params: Record<string, string>
  loading: boolean
  status: number
  result: unknown
  error: string
}

const state = reactive<Record<string, EpState>>({})
for (const g of groups) {
  for (const ep of g.items) {
    state[ep.id] = { params: {}, loading: false, status: 0, result: null, error: '' }
  }
}

const flatEndpoints = computed(() => groups.flatMap((g) => g.items))

async function callApi(ep: Endpoint) {
  const st = state[ep.id]
  if (ep.confirm && !window.confirm(`确认执行 ${ep.method} ${ep.path}？`)) return

  st.loading = true
  st.error = ''
  st.result = null
  st.status = 0
  try {
    let path = ep.path
    const query: Record<string, string> = {}
    const body: Record<string, string> = {}
    for (const p of ep.params) {
      const v = (st.params[p.key] ?? '').trim()
      if (p.in === 'path') {
        path = path.split('{' + p.key + '}').join(encodeURIComponent(v))
      } else if (p.in === 'query') {
        query[p.key] = v
      } else if (p.in === 'body') {
        body[p.key] = v
      }
    }
    const res = await http.request({
      method: ep.method.toLowerCase(),
      url: path,
      params: query,
      data: Object.keys(body).length ? body : undefined,
    })
    st.status = res.status
    st.result = res.data
  } catch (e: unknown) {
    const err = e as { response?: { status?: number; data?: unknown; message?: string }; message?: string }
    st.status = err.response?.status ?? 0
    st.error = (err.response?.data as { message?: string })?.message || err.message || '请求失败'
    st.result = err.response?.data ?? null
  } finally {
    st.loading = false
  }
}

function statusClass(s: number): string {
  if (s === 0) return 'err'
  if (s < 300) return 'ok'
  return 'err'
}

function clearResult(ep: Endpoint) {
  const st = state[ep.id]
  st.result = null
  st.error = ''
  st.status = 0
}
</script>

<template>
  <div class="api">
    <header class="api-header">
      <div>
        <h1 class="api-title">接口操作台</h1>
        <p class="api-sub">后端接口一键调用 · 辅助测试（{{ flatEndpoints.length }} 个接口）</p>
      </div>
    </header>

    <div class="api-groups">
      <section v-for="g in groups" :key="g.name" class="api-card">
        <h3 class="api-group-title">{{ g.name }}</h3>
        <div class="api-eps">
          <div v-for="ep in g.items" :key="ep.id" class="ep">
            <div class="ep-head">
              <span class="ep-method" :class="ep.method.toLowerCase()">{{ ep.method }}</span>
              <div class="ep-meta">
                <span class="ep-label">{{ ep.label }}</span>
                <span class="ep-path">{{ ep.path }}</span>
              </div>
              <button class="ep-btn" :class="{ loading: state[ep.id].loading }" :disabled="state[ep.id].loading" @click="callApi(ep)">
                {{ state[ep.id].loading ? '…' : '调用' }}
              </button>
            </div>

            <div v-if="ep.params.length" class="ep-params">
              <div v-for="p in ep.params" :key="p.key" class="ep-param">
                <span class="ep-param-key">{{ p.key }}<em v-if="p.in !== 'body'">{{ p.in }}</em></span>
                <input v-model="state[ep.id].params[p.key]" :placeholder="p.label" class="ep-input" />
              </div>
            </div>

            <div v-if="state[ep.id].result || state[ep.id].error" class="ep-result">
              <div class="ep-result-head">
                <span class="ep-status" :class="statusClass(state[ep.id].status)">
                  {{ state[ep.id].status || 'ERR' }}
                </span>
                <button class="ep-clear" @click="clearResult(ep)">清除</button>
              </div>
              <pre v-if="state[ep.id].error" class="ep-err">{{ state[ep.id].error }}</pre>
              <pre v-if="state[ep.id].result != null" class="ep-json">{{ JSON.stringify(state[ep.id].result, null, 2) }}</pre>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.api {
  --slate-900: #0f172a;
  --slate-700: #334155;
  --slate-500: #64748b;
  --slate-400: #94a3b8;
  --slate-200: #e2e8f0;
  --slate-100: #f1f5f9;
  --slate-50: #f8fafc;
  --teal: #0f766e;

  padding: 24px 28px 48px;
  max-width: 1100px;
  margin: 0 auto;
  background: #fafafa;
  min-height: 100%;
  color: var(--slate-700);
  font-feature-settings: 'tnum';
}

.api-header {
  margin-bottom: 20px;
}
.api-title {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--slate-900);
}
.api-sub {
  margin: 5px 0 0;
  font-size: 13px;
  color: var(--slate-400);
}

.api-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.api-card {
  background: #fff;
  border-radius: 14px;
  padding: 16px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.api-group-title {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--slate-700);
}

.api-eps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ep {
  border: 1px solid var(--slate-100);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--slate-50);
}
.ep-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ep-method {
  flex-shrink: 0;
  width: 52px;
  text-align: center;
  padding: 3px 0;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 700;
  font-family: var(--font-display);
  color: #fff;
}
.ep-method.get {
  background: #10b981;
}
.ep-method.post {
  background: #3b82f6;
}
.ep-method.delete {
  background: #ef4444;
}
.ep-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.ep-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--slate-900);
  flex-shrink: 0;
}
.ep-path {
  font-size: 12px;
  font-family: var(--font-display);
  color: var(--slate-400);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ep-btn {
  flex-shrink: 0;
  padding: 5px 14px;
  border-radius: 7px;
  border: 1px solid var(--teal);
  background: #fff;
  color: var(--teal);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.ep-btn:hover:not(:disabled) {
  background: var(--teal);
  color: #fff;
}
.ep-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.ep-params {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 9px;
}
.ep-param {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ep-param-key {
  font-size: 11px;
  font-family: var(--font-display);
  color: var(--slate-500);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.ep-param-key em {
  font-style: normal;
  font-size: 9px;
  color: var(--slate-400);
  border: 1px solid var(--slate-200);
  border-radius: 3px;
  padding: 0 3px;
}
.ep-input {
  height: 30px;
  width: 180px;
  padding: 0 10px;
  border: 1px solid var(--slate-200);
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  font-family: var(--font-display);
  color: var(--slate-900);
  outline: none;
  transition: border-color 0.15s;
}
.ep-input:focus {
  border-color: var(--teal);
}

.ep-result {
  margin-top: 10px;
  border-top: 1px dashed var(--slate-200);
  padding-top: 9px;
}
.ep-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.ep-status {
  font-size: 11px;
  font-weight: 700;
  font-family: var(--font-display);
  padding: 2px 8px;
  border-radius: 999px;
}
.ep-status.ok {
  background: #d1fae5;
  color: #059669;
}
.ep-status.err {
  background: #fee2e2;
  color: #dc2626;
}
.ep-clear {
  font-size: 11px;
  color: var(--slate-400);
  background: none;
  border: none;
  cursor: pointer;
}
.ep-clear:hover {
  color: var(--slate-700);
}
.ep-err {
  margin: 0 0 6px;
  padding: 8px 10px;
  background: #fef2f2;
  border-radius: 6px;
  font-size: 12px;
  color: #dc2626;
  white-space: pre-wrap;
  word-break: break-word;
}
.ep-json {
  margin: 0;
  padding: 10px 12px;
  background: #0f172a;
  border-radius: 8px;
  font-size: 11.5px;
  line-height: 1.55;
  font-family: var(--font-display);
  color: #cbd5e1;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 600px) {
  .api {
    padding: 18px 14px 36px;
  }
  .ep-input {
    width: 140px;
  }
}
</style>
