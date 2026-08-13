import { http } from './client'

export interface IngestionResult {
  docId: string
  name: string
  chunkCount: number
  status: string
}

export interface DocumentInfo {
  id: number
  docId: string
  name: string
  mimeType: string
  sourceType: string
  sourceLocation: string | null
  chunkCount: number
  status: string
  errorMsg: string
  collectionId: number | null
  createdAt: string
  updatedAt: string
}

export async function uploadDocument(
  file: File,
  collectionId?: number,
  /** true=纯文本切分（不保 block 元数据）；缺省/false=语义感知（block-aware） */
  plainText?: boolean,
): Promise<IngestionResult> {
  const form = new FormData()
  form.append('file', file)
  if (collectionId != null) form.append('collectionId', String(collectionId))
  if (plainText) form.append('plainText', 'true')
  const { data } = await http.post<IngestionResult>('/docs/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

export async function listDocuments(): Promise<DocumentInfo[]> {
  const { data } = await http.get<DocumentInfo[]>('/docs')
  return data
}

export interface DocumentPage {
  total: number
  records: DocumentInfo[]
}

/** 文档分页查询（管理后台文档管理）：keyword 模糊匹配文件名，sourceType/status 精确过滤 */
export async function pageDocuments(opts: {
  page: number
  size: number
  keyword?: string
  sourceType?: string
  status?: string
  collectionId?: number
  unassigned?: boolean
}): Promise<DocumentPage> {
  const { data } = await http.get<DocumentPage>('/docs/page', { params: opts })
  return data
}

export async function deleteDocument(docId: string): Promise<{ status: string }> {
  const { data } = await http.delete<{ status: string }>(`/docs/${docId}`)
  return data
}

export interface ChunkItem {
  content: string
  chunkIndex: number
  blockType: string
}

export interface DocContent {
  docId: string
  docName: string
  chunks: ChunkItem[]
}

export async function getDocumentContent(docId: string): Promise<DocContent> {
  const { data } = await http.get<DocContent>(`/docs/${docId}/content`)
  return data
}
