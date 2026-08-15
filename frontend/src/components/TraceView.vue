<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { DeploymentUnitOutlined, NodeIndexOutlined, FileSearchOutlined, CopyOutlined } from '@ant-design/icons-vue'
import { getObserveLinks, type ObserveLinks } from '../api/eval'

/**
 * 链路追踪：跳转 OpenObserve 查看 RAG 全链路 trace / 日志。
 * 跳转链接 + 只读账号均由后端 /observe/links 返回（地址随 openobserve.web-url 配置，前端不维护）。
 */
const links = ref<ObserveLinks | null>(null)
const loading = ref(true)
const loadError = ref(false)
const pwdVisible = ref(false)

onMounted(async () => {
  try {
    links.value = await getObserveLinks()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
})

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制')
  } catch {
    message.info('请手动选中复制')
  }
}
</script>

<template>
  <div class="trace-page page-scroll">
    <!-- 页头：标题 + 主操作（跳 OpenObserve） -->
    <div class="page-header">
      <div class="page-header-text">
        <h1 class="page-title">链路追踪</h1>
        <p class="page-desc">查看请求在 RAG 全链路的 trace —— 检索 / 查询改写 / 意图分类 / Rerank / LLM 生成各步骤的 span 与耗时，定位慢环节与异常。</p>
      </div>
      <div class="page-actions">
        <a-button v-if="links" type="primary" :href="links.traceUrl" target="_blank">
          <template #icon><DeploymentUnitOutlined /></template>打开 OpenObserve
        </a-button>
      </div>
    </div>

    <div v-if="loading" class="state">加载中…</div>
    <div v-else-if="loadError || !links" class="state error">
      无法获取 OpenObserve 配置，请检查后端 <code>openobserve.web-url</code> 设置。
    </div>

    <template v-else>
      <!-- 跳转入口卡：双入口磁贴 -->
      <div class="table-card">
        <div class="table-toolbar"><span class="card-title">跳转入口</span></div>
        <div class="entry-grid">
          <a class="entry-tile" :href="links.traceUrl" target="_blank" rel="noopener">
            <span class="entry-ico"><NodeIndexOutlined /></span>
            <div class="entry-body">
              <div class="entry-name">链路追踪（Traces）</div>
              <div class="entry-desc">按 traceId 查整条调用链，看各步骤耗时与父子 span</div>
            </div>
          </a>
          <a class="entry-tile" :href="links.logUrl" target="_blank" rel="noopener">
            <span class="entry-ico"><FileSearchOutlined /></span>
            <div class="entry-body">
              <div class="entry-name">日志检索（Logs）</div>
              <div class="entry-desc">按 traceId / 关键词反查结构化日志（含步骤、模型、耗时）</div>
            </div>
          </a>
        </div>
      </div>

      <!-- 只读账号卡 -->
      <div class="table-card">
        <div class="table-toolbar"><span class="card-title">OpenObserve 查看账号</span></div>
        <div class="acct-body">
          <div class="acct-row">
            <span class="acct-label">组织</span>
            <code>{{ links.org }}</code>
          </div>
          <div class="acct-row">
            <span class="acct-label">账号</span>
            <code>{{ links.email }}</code>
            <a-button type="text" size="small" class="copy-btn" @click="copy(links.email)">
              <template #icon><CopyOutlined /></template>
            </a-button>
          </div>
          <div class="acct-row">
            <span class="acct-label">密码</span>
            <code>{{ pwdVisible ? links.password : '•••••••••' }}</code>
            <a-button type="link" size="small" @click="pwdVisible = !pwdVisible">{{ pwdVisible ? '隐藏' : '显示' }}</a-button>
            <a-button type="text" size="small" class="copy-btn" @click="copy(links.password)">
              <template #icon><CopyOutlined /></template>
            </a-button>
          </div>
          <div class="acct-hint">
            点击上方卡片在新标签页打开 OpenObserve，首次需用此账号登录（组织选 <code>{{ links.org }}</code>）。
            <br />注：OO 社区版无只读角色，此账号为 OO 全权账号，仅供查看共享。
          </div>
        </div>
      </div>

      <!-- 使用说明卡 -->
      <div class="table-card">
        <div class="table-toolbar"><span class="card-title">如何根据一次对话查链路？</span></div>
        <div class="tip-body">
          <ol>
            <li>在对话页发起一次问答（日志里每行带 <code>[traceId,spanId]</code>）。</li>
            <li>复制该次问答的 <code>traceId</code>（控制台日志或 OpenObserve 日志里取）。</li>
            <li>打开「链路追踪」，在搜索条件 <code>trace_id = &lt;你的 traceId&gt;</code> 即可看到整条链路。</li>
          </ol>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 卡片统一 12px 节奏（页头自带下边距） */
.trace-page .table-card {
  margin-bottom: 12px;
}
/* 卡片工具栏标题 */
.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
.state {
  padding: 32px;
  text-align: center;
  font-size: 13px;
  color: var(--color-ink-tertiary);
}
.state.error {
  color: var(--color-danger);
}
.state code {
  background: var(--color-surface-secondary);
  padding: 0 5px;
  border-radius: 3px;
  font-size: 12px;
}
/* 跳转入口磁贴 */
.entry-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 16px 20px;
}
.entry-tile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: var(--color-surface-secondary);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: all 0.15s;
}
a.entry-tile {
  color: inherit;
  text-decoration: none;
}
.entry-tile:hover {
  background: var(--color-surface);
  border-color: var(--color-primary);
}
.entry-ico {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 17px;
}
.entry-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
  margin-bottom: 2px;
}
.entry-desc {
  font-size: 12px;
  color: var(--color-ink-tertiary);
  line-height: 1.5;
}
/* 账号卡内容 */
.acct-body {
  padding: 12px 20px 16px;
}
.acct-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
}
.acct-label {
  width: 40px;
  color: var(--color-ink-tertiary);
}
.acct-row code {
  background: var(--color-surface-secondary);
  padding: 1px 8px;
  border-radius: 3px;
  font-family: var(--font-display);
  color: var(--color-ink);
}
.copy-btn {
  color: var(--color-ink-tertiary);
}
.acct-hint {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--color-border-light);
  font-size: 12px;
  color: var(--color-ink-tertiary);
  line-height: 1.7;
}
/* 说明卡内容 */
.tip-body {
  padding: 12px 20px 16px;
}
.tip-body ol {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  color: var(--color-ink-secondary);
  line-height: 1.9;
}
.tip-body code {
  background: var(--color-surface-secondary);
  padding: 0 5px;
  border-radius: 3px;
  font-size: 12px;
}
@media (max-width: 768px) {
  .entry-grid {
    grid-template-columns: 1fr;
  }
}
</style>
