package com.agentframework.crosscutting.filter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 过滤链：同类型进同类型出，按 order 顺序串行变换。
 *
 * @param <T> 链上统一的数据类型
 */
public final class FilterChain<T> {

    private final List<Filter<T, T>> filters = new ArrayList<>();

    /** 创建空链。 */
    public FilterChain() {
    }

    /**
     * @param filters 初始过滤器集合
     */
    public FilterChain(List<Filter<T, T>> filters) {
        if (filters != null) {
            this.filters.addAll(filters);
            this.filters.sort(Comparator.comparingInt(Filter::order));
        }
    }

    /**
     * 追加过滤器。
     *
     * @param filter 过滤器实现
     * @return 当前链
     */
    public FilterChain<T> add(Filter<T, T> filter) {
        if (filter != null) {
            filters.add(filter);
            filters.sort(Comparator.comparingInt(Filter::order));
        }
        return this;
    }

    /** @return 链上的过滤器快照 */
    public List<Filter<T, T>> filters() {
        return List.copyOf(filters);
    }

    /** @return 是否没有任何过滤器 */
    public boolean isEmpty() {
        return filters.isEmpty();
    }

    /**
     * 依次执行过滤链。
     *
     * @param input   输入数据
     * @param context 过滤上下文
     * @return 变换后的数据；链为空时原样返回
     */
    public T apply(T input, FilterContext context) {
        T current = input;
        for (Filter<T, T> filter : filters) {
            if (filter.supports(context)) {
                current = filter.filter(current, context);
            }
        }
        return current;
    }
}
