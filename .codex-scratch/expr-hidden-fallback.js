(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const snap = () => ({
    hash: window.location.hash,
    selected: [...document.querySelectorAll('.admin-menu .ant-menu-item-selected')].map((i) => i.innerText.trim()),
    title: (document.querySelector('.admin-content .page-title') || {}).innerText || '',
  })
  window.location.hash = '#/admin/cache'
  await sleep(1200)
  const cache = snap()
  window.location.hash = '#/admin/agents'
  await sleep(1200)
  const agents = snap()
  window.location.hash = '#/admin/agent-traces'
  await sleep(1500)
  return JSON.stringify({ cache, agents, traces: snap() }, null, 1)
})()
