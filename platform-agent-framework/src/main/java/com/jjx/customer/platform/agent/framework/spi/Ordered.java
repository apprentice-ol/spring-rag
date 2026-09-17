package com.jjx.customer.platform.agent.framework.spi;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 有序扩展点（Spring {@code org.springframework.core.Ordered} 范式）：数值小者先执行。
 *
 * <p>所有可插拔组件（监听器 / 拦截器 / 节点执行器 / 路由策略 / 元数据贡献者）实现本接口表达顺序，
 * 由框架统一排序，不再依赖注册顺序。</p>
 */
public interface Ordered {

    int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;

    int LOWEST_PRECEDENCE = Integer.MAX_VALUE;

    /** 顺序（默认 0 = 不限；数值小者先执行）。 */
    default int order() {
        return 0;
    }

    /** 按 {@link #order()} 升序排序（框架内部统一入口）。 */
    static <T extends Ordered> List<T> sorted(Collection<T> items) {
        return items == null ? List.of()
                : items.stream().sorted(Comparator.comparingInt(Ordered::order)).toList();
    }
}
