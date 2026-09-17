(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  // 进入评测 → 数据集 tab
  window.location.hash = '#/admin/eval'
  await sleep(600)
  const tab = [...document.querySelectorAll('.ant-tabs-tab')].find((t) => t.innerText.includes('数据集'))
  tab?.querySelector('.ant-tabs-tab-btn')?.click()
  await sleep(900)
  // 点开第一个数据集，展开条目表
  document.querySelector('.table-card tbody tr.ant-table-row')?.click()
  await sleep(1500)
  const w = (el) => (el ? Math.round(el.getBoundingClientRect().width) : 0)
  return JSON.stringify(
    {
      viewport: window.innerWidth,
      tables: [...document.querySelectorAll('.table-card')].map((card) => ({
        head: (card.querySelector('.table-toolbar')?.innerText || '').trim().slice(0, 40),
        cols: [...card.querySelectorAll('thead th')].map((th) => ({
          t: th.innerText.trim().slice(0, 16),
          w: Math.round(th.getBoundingClientRect().width),
        })),
      })),
      cardBoxW: w(document.querySelector('.table-card')),
    },
    null,
    1,
  )
})()
