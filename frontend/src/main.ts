import { createApp } from 'vue'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import 'github-markdown-css/github-markdown.css'
import 'highlight.js/styles/github.css'
// JetBrains Mono 本地打包：品牌名 / KPI 数字 / traceId 的真等宽（--font-display 首选）
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import '@fontsource/jetbrains-mono/700.css'
import App from './App.vue'
import './style.css'

createApp(App).use(Antd).mount('#app')
