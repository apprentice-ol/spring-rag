import { http } from './client'

export interface DocCollection {
  id: number
  name: string
  description: string | null
  docCount: number
  createTime: string
  updateTime: string
}

export async function listCollections(): Promise<DocCollection[]> {
  const { data } = await http.get<DocCollection[]>('/docs/collections')
  return data
}

export async function createCollection(name: string, description: string): Promise<{ id: number }> {
  const { data } = await http.post<{ id: number }>('/docs/collections', { name, description })
  return data
}

export async function renameCollection(id: number, name: string, description: string): Promise<unknown> {
  const { data } = await http.put(`/docs/collections/${id}`, { name, description })
  return data
}

export async function deleteCollection(id: number): Promise<{ unlinked: number }> {
  const { data } = await http.delete<{ unlinked: number }>(`/docs/collections/${id}`)
  return data
}

/**
 * 批量归集/移动/移出。
 * @param collectionId 目标集合；null = 移出集合（变独立文件）
 */
export async function assignDocs(collectionId: number | null, docIds: string[]): Promise<{ updated: number }> {
  const { data } = await http.post<{ updated: number }>('/docs/collections/assign', { collectionId, docIds })
  return data
}
