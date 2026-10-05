package com.millionnaire.engine.serialize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 开放接口（如 Command、Event、DomainState）的多态登记表：接口 → {标签 → record 实现}。
 * 标签为实现类的简单类名，同一接口下必须唯一，冲突在构建时即报错。sealed 接口无需登记，自动解析。
 */
public final class TypeRegistry {
    public static final TypeRegistry EMPTY = new TypeRegistry(new TreeMap<>());

    private final Map<String, Map<String, Class<?>>> byInterface;

    private TypeRegistry(Map<String, Map<String, Class<?>>> byInterface) {
        this.byInterface = byInterface;
    }

    public static Builder builder() {
        return new Builder(new TreeMap<>());
    }

    /** 合并两张登记表；同一接口下标签冲突时报错。 */
    public TypeRegistry merge(TypeRegistry other) {
        Builder b = new Builder(copy(byInterface));
        other.byInterface.forEach((iface, tags) -> tags.values().forEach(c -> b.put(iface, c)));
        return b.build();
    }

    Optional<Map<String, Class<?>>> implementations(Class<?> iface) {
        return Optional.ofNullable(byInterface.get(iface.getName()));
    }

    /** sealed 层级的全部 record 叶子（递归穿过 sealed 子接口），按标签索引并检查唯一。 */
    static Map<String, Class<?>> sealedLeaves(Class<?> sealed) {
        TreeMap<String, Class<?>> out = new TreeMap<>();
        for (Class<?> leaf : leaves(sealed)) {
            if (out.put(leaf.getSimpleName(), leaf) != null) {
                throw new IllegalArgumentException("duplicate type tag " + leaf.getSimpleName() + " under " + sealed.getName());
            }
        }
        return out;
    }

    private static List<Class<?>> leaves(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        if (type.isRecord()) {
            out.add(type);
        } else if (type.isSealed()) {
            for (Class<?> sub : type.getPermittedSubclasses()) {
                out.addAll(leaves(sub));
            }
        } else {
            throw new IllegalArgumentException(type.getName() + " is neither a record nor sealed");
        }
        return out;
    }

    private static Map<String, Map<String, Class<?>>> copy(Map<String, Map<String, Class<?>>> m) {
        TreeMap<String, Map<String, Class<?>>> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, new TreeMap<>(v)));
        return out;
    }

    /** 构建器。 */
    public static final class Builder {
        private final Map<String, Map<String, Class<?>>> byInterface;

        private Builder(Map<String, Map<String, Class<?>>> byInterface) {
            this.byInterface = byInterface;
        }

        /** 登记 iface 的实现；参数可以是 record，也可以是 sealed 接口（展开为全部 record 叶子）。 */
        public Builder add(Class<?> iface, Class<?>... implementations) {
            for (Class<?> impl : implementations) {
                for (Class<?> leaf : leaves(impl)) {
                    if (!iface.isAssignableFrom(leaf)) {
                        throw new IllegalArgumentException(leaf.getName() + " does not implement " + iface.getName());
                    }
                    put(iface.getName(), leaf);
                }
            }
            return this;
        }

        private void put(String iface, Class<?> leaf) {
            Map<String, Class<?>> tags = byInterface.computeIfAbsent(iface, k -> new TreeMap<>());
            Class<?> prev = tags.putIfAbsent(leaf.getSimpleName(), leaf);
            if (prev != null && prev != leaf) {
                throw new IllegalArgumentException("duplicate type tag " + leaf.getSimpleName() + " for " + iface
                        + ": " + prev.getName() + " vs " + leaf.getName());
            }
        }

        public TypeRegistry build() {
            TreeMap<String, Map<String, Class<?>>> frozen = new TreeMap<>();
            byInterface.forEach((k, v) -> frozen.put(k, Collections.unmodifiableMap(new TreeMap<>(v))));
            return new TypeRegistry(Collections.unmodifiableMap(frozen));
        }
    }
}
