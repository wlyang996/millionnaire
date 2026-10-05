package com.millionnaire.engine.serialize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 确定性不可变集合的拷贝工具。禁止使用 {@code Map.of/Set.of/Map.copyOf}：其遍历顺序按 JVM 随机化。
 * 允许 null（交由校验器报告"缺失"），null 输入原样返回。
 */
public final class Immutable {
    private Immutable() {
    }

    public static <T> List<T> list(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    public static <K extends Comparable<? super K>, V> Map<K, V> sortedMap(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableSortedMap(new TreeMap<>(source));
    }

    /** 返回追加一个元素后的新不可变列表。 */
    public static <T> List<T> append(List<T> source, T item) {
        List<T> copy = new ArrayList<>(source);
        copy.add(item);
        return Collections.unmodifiableList(copy);
    }
}
