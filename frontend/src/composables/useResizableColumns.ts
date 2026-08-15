import { ref, type Directive, type Ref } from 'vue'

/**
 * 表格列宽拖拽(全站表格通用)。
 *
 * 背景:antdv v4 没有 setColumnWidth API——拖拽时改列对象的 width、原地替换数组元素,
 * 触发 antd 重渲染(ref 深响应,数组元素赋值即可,不需要整体换引用)。
 *
 * 用法:
 *   const columns = useResizableColumns([{ title: '名称', key: 'name', width: 200 }, ...])
 *   <a-table :columns="columns" :scroll="{ x: 1000 }">
 *     <template #headerCell="{ column }">
 *       <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="columns">{{ column.title }}</span>
 *     </template>
 *   </a-table>
 *
 * 注意:
 * - 可排序列(column.sorter)不加把手——headerCell 插槽内容会顶掉 antd 的排序 UI;
 * - 拖拽把手是 JS 动态创建的元素,样式必须内联(scoped 样式匹配不到);
 * - 表格需配 :scroll.x,否则非固定布局下列宽不生效。
 */

/** 最小列宽约束(px) */
const MIN_COL_WIDTH = 60

export function useResizableColumns<C extends { key: string; width?: number }>(initial: C[]): Ref<C[]> {
  // ref 深层解包会推出 UnwrapRefSimple<C>,与 C 不兼容,断言回原类型
  return ref(initial.map((c) => ({ ...c }))) as Ref<C[]>
}

/**
 * v-resize:[columnKey]="columns"
 * arg = 列 key;value = 列数组(模板里 ref 自动解包)。
 * mousedown 记录起点,move 中原地替换命中的列元素写 width。
 */
export const vResize: Directive<HTMLElement, { key: string; width?: number }[]> = {
  mounted(el, binding) {
    const handle = document.createElement('span')
    handle.style.cssText =
      'position:absolute;top:0;right:-7px;bottom:0;width:14px;cursor:col-resize;z-index:1'
    el.appendChild(handle)
    handle.addEventListener('mousedown', (e: MouseEvent) => {
      e.preventDefault()
      e.stopPropagation()
      const th = el.parentElement as HTMLElement
      const startX = e.clientX
      const startWidth = th.getBoundingClientRect().width
      const cols = binding.value
      const key = binding.arg
      const onMove = (ev: MouseEvent) => {
        const width = Math.max(MIN_COL_WIDTH, Math.round(startWidth + ev.clientX - startX))
        const idx = cols.findIndex((c) => c.key === key)
        if (idx >= 0) cols[idx] = { ...cols[idx], width }
      }
      const onUp = () => {
        document.removeEventListener('mousemove', onMove)
        document.removeEventListener('mouseup', onUp)
        document.body.style.cursor = ''
        document.body.style.userSelect = ''
      }
      document.body.style.cursor = 'col-resize'
      document.body.style.userSelect = 'none'
      document.addEventListener('mousemove', onMove)
      document.addEventListener('mouseup', onUp)
    })
  },
}
