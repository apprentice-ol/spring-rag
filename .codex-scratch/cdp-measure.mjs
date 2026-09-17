// 通过 CDP 在真实浏览器里量表格列宽（headless Chrome + remote debugging）。
// 用法: node cdp-measure.mjs <port> <表达式|@表达式文件> [等待条件表达式|@文件]
import { readFileSync, writeFileSync } from 'node:fs'

const [, , portArg, expr, waitExpr] = process.argv
const port = Number(portArg || 9333)

const load = (v) => (v && v.startsWith('@') ? readFileSync(v.slice(1), 'utf8') : v)
const exprSrc = load(expr)
const waitSrc = load(waitExpr) || 'document.readyState === "complete"'

async function listTargets() {
  const res = await fetch(`http://127.0.0.1:${port}/json/list`)
  return res.json()
}

async function waitForTargets(timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    try {
      const list = await listTargets()
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
      if (page) return page
    } catch {
      /* chrome 还没起来 */
    }
    if (Date.now() > deadline) throw new Error('CDP target not ready')
    await new Promise((r) => setTimeout(r, 300))
  }
}

const page = await waitForTargets()
const ws = new WebSocket(page.webSocketDebuggerUrl)
let seq = 0
const pending = new Map()
ws.addEventListener('message', (e) => {
  const msg = JSON.parse(e.data)
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg)
    pending.delete(msg.id)
  }
})
function send(method, params = {}) {
  return new Promise((resolve) => {
    const id = ++seq
    pending.set(id, resolve)
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails))
  return r.result?.result?.value
}

await new Promise((resolve) => ws.addEventListener('open', resolve, { once: true }))
await send('Runtime.enable')

// 等页面加载完（含接口回来的数据）
const deadline = Date.now() + 25000
for (;;) {
  const ready = await evaluate(waitSrc)
  if (ready) break
  if (Date.now() > deadline) break
  await new Promise((r) => setTimeout(r, 400))
}

const value = await evaluate(exprSrc)
console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2))

// 可选：--shot=<path> 抓一张整页截图（给人工看）
const shotArg = process.argv.find((a) => a.startsWith('--shot='))
if (shotArg) {
  await send('Page.enable')
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(shotArg.slice('--shot='.length), Buffer.from(shot.result.data, 'base64'))
  console.log('shot saved:', shotArg.slice('--shot='.length))
}
ws.close()
process.exit(0)
