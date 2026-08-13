import { http } from './client'

export interface ConsoleOverview {
  system: {
    backend: string
    database: string
    javaVersion: string
    appName: string
  }
  stats: {
    documents: number
    chunks: number
    conversations: number
    evalRuns: number
    evalDatasets: number
  }
  config: {
    topK: number
    similarityThreshold: number
    recallBudget: number
    candidateLimit: number
    contextTopK: number
    rrfK: number
    chunkSize: number
    chunkOverlap: number
    rerankEnabled: boolean
    keywordEnabled: boolean
    webSearchEnabled: boolean
    chatModel: string
    embeddingModel: string
    rerankModel: string
  }
}

export async function getOverview(): Promise<ConsoleOverview> {
  const { data } = await http.get<ConsoleOverview>('/console/overview')
  return data
}
