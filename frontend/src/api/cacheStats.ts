import { http } from './client'

/** 缓存看板（GET /cache/stats，手动刷新；命中计数为后端进程内自启动累计） */

export interface CacheLayerStat {
  layer: string
  enabled: boolean
  ttl: string | null
  admissionThreshold: number | null
  hits: number
  misses: number
  hitRate: number
  /** 最近 1h 滑动窗口口径（后端分钟环形桶；旧后端无此字段则不显示） */
  windowHits?: number
  windowMisses?: number
  windowHitRate?: number
}

export interface CacheTopQuestion {
  question: string
  paradigm: string
  hitCount: number
  updatedAt: string | null
}

export interface CacheStats {
  enabled: boolean
  layers: CacheLayerStat[]
  circuit: { state: string; down: boolean; failureThreshold: number; cooldown: string }
  degrade: { limit: number; rejected: number; availablePermits: number }
  redisDown: boolean
  keyCounts: Record<string, number>
  docver: Record<string, number>
  semantic: {
    rows?: number
    totalHits?: number
    threshold?: number
    topQuestions?: CacheTopQuestion[]
  }
}

export async function getCacheStats(): Promise<CacheStats> {
  const { data } = await http.get<CacheStats>('/cache/stats')
  return data
}

/** 开关缓存层（layer 为空 = 总开关；进程内生效，重启恢复配置文件） */
export async function toggleCache(layer: string | null, enabled: boolean): Promise<void> {
  await http.post('/cache/toggle', { layer: layer ?? '', enabled })
}

// ===== 语义缓存记录 =====

export interface SemanticRecord {
  id: number
  question: string
  answer: string
  paradigm: string
  docver: number
  messageId: number | null
  hitCount: number
  updateTime: number | null
}

export interface RecordPage {
  total: number
  records: SemanticRecord[]
}

export async function getSemanticRecords(page = 1, size = 10): Promise<RecordPage> {
  const { data } = await http.get<RecordPage>('/cache/semantic/records', { params: { page, size } })
  return data
}

export async function deleteSemanticRecord(id: number): Promise<void> {
  await http.delete(`/cache/semantic/${id}`)
}

export async function clearSemanticRecords(): Promise<void> {
  await http.delete('/cache/semantic')
}

// ===== Redis 键记录 =====

export interface RedisEntry {
  key: string
  ttlSeconds: number | null
  preview: string | null
}

export async function getRedisEntries(layer: string, limit = 50): Promise<RedisEntry[]> {
  const { data } = await http.get<RedisEntry[]>('/cache/redis-entries', { params: { layer, limit } })
  return data
}
