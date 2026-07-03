package org.gscript.util;

import java.util.ArrayList;

/**
 * 最小优先队列（替代 java.util.concurrent.PriorityQueue）。
 *
 * <p>Java 1.4 兼容：有序 ArrayList + 二分插入。peek/poll 返回最小元素（index 0）。
 * 仅实现项目用到的 API：offer / peek / poll / isEmpty / remove / size。
 *
 * <p>注意：ArrayList.remove(0) 是 O(n)，但定时器数量通常很小（<100），性能可接受。
 * 若需 O(log n) 可改用二叉堆实现，但对本项目无必要。
 */
public class MinPriorityQueue {

    /** 比较器接口（替代 java.util.Comparator，避免泛型）。 */
    public static interface Comparator {
        int compare(Object a, Object b);
    }

    private final ArrayList elements = new ArrayList();
    private final Comparator comparator;

    public MinPriorityQueue(Comparator comparator) {
        this.comparator = comparator;
    }

    /**
     * 按比较器顺序插入元素（二分查找插入位置）。
     * 相同优先级的元素按插入顺序排列（稳定）。
     */
    public void offer(Object e) {
        int lo = 0;
        int hi = elements.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (comparator.compare(elements.get(mid), e) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        elements.add(lo, e);
    }

    /** 返回最小元素（不移除），队列空返回 null。 */
    public Object peek() {
        if (elements.isEmpty()) {
            return null;
        }
        return elements.get(0);
    }

    /** 移除并返回最小元素，队列空返回 null。 */
    public Object poll() {
        if (elements.isEmpty()) {
            return null;
        }
        return elements.remove(0);
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public int size() {
        return elements.size();
    }

    /** 移除指定元素（按 identity 比较）。 */
    public boolean remove(Object e) {
        return elements.remove(e);
    }
}
