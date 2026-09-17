(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const out = []
  const menuItems = [...document.querySelectorAll('.admin-menu .ant-menu-item')]
  out.push({ menu: menuItems.map((i) => i.innerText.trim()) })
  for (const item of menuItems) {
    const menuName = item.innerText.trim()
    item.click()
    await sleep(2600)
    const tables = [...document.querySelectorAll('.table-card')].slice(0, 2)
    out.push({
      menu: menuName,
      hash: window.location.hash,
      tables: tables.map((card) => ({
        cols: [...card.querySelectorAll('thead th')].map((th) => {
          const txt = th.innerText.trim()
          return `${txt.slice(0, 12)}=${Math.round(th.getBoundingClientRect().width)}`
        }),
      })),
    })
  }
  return JSON.stringify({ viewport: window.innerWidth, out }, null, 1)
})()
