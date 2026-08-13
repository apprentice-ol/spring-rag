import { createApp } from 'vue'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import 'github-markdown-css/github-markdown.css'
import 'highlight.js/styles/github.css'
import App from './App.vue'
import './style.css'

createApp(App).use(Antd).mount('#app')
