(() => {
  const rows = [...document.querySelectorAll('.table-card tbody tr.ant-table-row')].slice(0, 4)
  return JSON.stringify(
    rows.map((r) => {
      const tds = [...r.querySelectorAll('td')]
      const tag = tds[0]?.querySelector('.ant-tag')
      return {
        rowH: Math.round(r.getBoundingClientRect().height),
        col0W: Math.round(tds[0]?.getBoundingClientRect().width || 0),
        tagText: tag?.innerText || '',
        tagW: Math.round(tag?.getBoundingClientRect().width || 0),
        tagH: Math.round(tag?.getBoundingClientRect().height || 0),
        tagScrollW: tag ? tag.scrollWidth : 0,
        q: (tds[1]?.innerText || '').slice(0, 24),
        qTruncated: tds[1] ? tds[1].scrollWidth > tds[1].clientWidth + 1 : null,
      }
    }),
    null,
    1,
  )
})()
