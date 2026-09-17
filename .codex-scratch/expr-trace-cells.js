(() => {
  const row = document.querySelector('.table-card tbody tr.ant-table-row')
  if (!row) return JSON.stringify({ error: 'no row' })
  const heads = [...document.querySelectorAll('.table-card thead th')].map((th) => th.innerText.trim().slice(0, 10))
  const tds = [...row.querySelectorAll('td')]
  return JSON.stringify(
    {
      rowH: Math.round(row.getBoundingClientRect().height),
      cells: tds.map((td, i) => ({
        head: heads[i] || '',
        w: Math.round(td.getBoundingClientRect().width),
        h: Math.round(td.getBoundingClientRect().height),
        cls: td.className.replace('ant-table-cell', '').trim(),
        scrollW: td.scrollWidth,
        clientW: td.clientWidth,
        text: (td.innerText || '').replace(/\s+/g, ' ').slice(0, 30),
      })),
    },
    null,
    1,
  )
})()
