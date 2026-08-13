import axios from 'axios'

export const http = axios.create({
  baseURL: '/api/rag',
  timeout: 60000,
})
