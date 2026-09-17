(() => {
  const q = (sel) => document.querySelector(sel)
  const w = (el) => (el ? Math.round(el.getBoundingClientRect().width) : 0)
  return JSON.stringify(
    {
      viewport: window.innerWidth,
      adminContentW: w(q('.admin-content')),
      tableBoxW: w(q('.table-card .ant-table-wrapper')),
      rows: document.querySelectorAll('.table-card tbody tr.ant-table-row').length,
      scrollBodyW: w(q('.table-card .ant-table-body') || q('.table-card .ant-table-content')),
      cols: [...document.querySelectorAll('.table-card thead th')].map((th) => ({
        t: th.innerText.trim().slice(0, 20),
        w: Math.round(th.getBoundingClientRect().width),
      })),
      firstRow: (q('.table-card tbody tr.ant-table-row') || {}).innerText?.slice(0, 160) || '',
    },
    null,
    1,
  )
})()
