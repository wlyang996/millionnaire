package com.millionnaire.engine.serialize;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范序列化：确定性的 JSON 子集，用于快照、事件日志与内容哈希。
 * <ul>
 *   <li>只支持 record、enum、String、long/int、boolean、List、Map 与 null；不支持浮点、Set、数组。</li>
 *   <li>record 字段按声明顺序输出；以接口声明的值在首位写 {@code "@type":"简单类名"}。
 *       sealed 接口自动解析，开放接口须在 {@link TypeRegistry} 登记；同一接口下标签必须唯一。</li>
 *   <li>Map 编码为按键排序的 {@code [[k,v],...]}（enum 键按名称排序），解码为不可变 TreeMap。</li>
 *   <li>字符串按 UTF-8 原样输出，控制字符写成反斜杠 u 加四位小写十六进制；<b>孤立代理字符一律拒绝</b>，
 *       合法代理对（如 emoji）原样保留，因此"字符串 → 字节"是单射，字节可无损往返。</li>
 *   <li>无空白；解码后重新编码必须与输入逐字节一致，否则视为非规范输入而拒绝。</li>
 * </ul>
 * 得到的是<b>结构哈希</b>而非语义哈希：例如比例 50/100 与 1/2、列表换序都会得到不同哈希。
 */
public final class Codec {
    public static final Codec DEFAULT = new Codec(TypeRegistry.EMPTY);

    private static final String TYPE_TAG = "@type";
    /** 解析时对象 / 数组的最大嵌套深度；超过即视为非法输入（避免栈溢出）。引擎自身的数据结构嵌套远小于此值。 */
    static final int MAX_DEPTH = 64;
    private static final String HEX = "0123456789abcdef";

    private final TypeRegistry registry;

    public Codec(TypeRegistry registry) {
        this.registry = registry;
    }

    public TypeRegistry registry() {
        return registry;
    }

    public String encode(Object value) {
        return value == null ? "null" : encode(value, value.getClass());
    }

    public String encode(Object value, Type declared) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, declared);
        return sb.toString();
    }

    public byte[] bytes(Object value) {
        return encode(value).getBytes(StandardCharsets.UTF_8);
    }

    public <T> T decode(String text, Class<T> type) {
        return type.cast(decode(text, (Type) type));
    }

    public Object decode(String text, Type type) {
        Parser p = new Parser(text);
        Object tree = p.value();
        p.end();
        Object result = bind(tree, type);
        if (!encode(result, type).equals(text)) {
            throw new IllegalArgumentException("non-canonical input");
        }
        return result;
    }

    /** 从字节解码；非法 UTF-8 直接拒绝（不做替换）。 */
    public <T> T decode(byte[] utf8, Class<T> type) {
        return decode(strictUtf8(utf8), type);
    }

    static String strictUtf8(byte[] utf8) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF-8", e);
        }
    }

    // ---------------------------------------------------------------- encode

    private void write(StringBuilder sb, Object v, Type declared) {
        Class<?> raw = rawClass(declared);
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Long || v instanceof Integer) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else if (v instanceof Record r) {
            if (raw.isInterface()) {
                Class<?> expected = implementations(raw).get(r.getClass().getSimpleName());
                if (expected != r.getClass()) {
                    throw new IllegalArgumentException(r.getClass().getName() + " is not a registered " + raw.getName());
                }
            }
            writeRecord(sb, r, raw.isInterface());
        } else if (v instanceof List<?> list) {
            Type elem = typeArg(declared, 0);
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object item = list.get(i);
                if (item == null) {
                    throw new IllegalArgumentException("null list element");
                }
                write(sb, item, elem);
            }
            sb.append(']');
        } else if (v instanceof Map<?, ?> map) {
            Type kt = typeArg(declared, 0);
            Type vt = typeArg(declared, 1);
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort((x, y) -> compareKeys(x.getKey(), y.getKey()));
            sb.append('[');
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('[');
                write(sb, entries.get(i).getKey(), kt);
                sb.append(',');
                write(sb, entries.get(i).getValue(), vt);
                sb.append(']');
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported type: " + v.getClass().getName());
        }
    }

    private void writeRecord(StringBuilder sb, Record r, boolean tagged) {
        sb.append('{');
        boolean first = true;
        if (tagged) {
            writeString(sb, TYPE_TAG);
            sb.append(':');
            writeString(sb, r.getClass().getSimpleName());
            first = false;
        }
        for (RecordComponent rc : r.getClass().getRecordComponents()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, rc.getName());
            sb.append(':');
            Object fieldValue;
            try {
                fieldValue = rc.getAccessor().invoke(r);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("cannot read " + rc, e);
            }
            write(sb, fieldValue, rc.getGenericType());
        }
        sb.append('}');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                sb.append(c).append(s.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("lone surrogate U+" + Integer.toHexString(c) + " at index " + i);
            } else if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append("\\u00").append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Object a, Object b) {
        if (a instanceof Enum<?> x && b instanceof Enum<?> y) {
            return x.name().compareTo(y.name());
        }
        if (a instanceof Comparable ca && a.getClass() == b.getClass()) {
            return ca.compareTo(b);
        }
        throw new IllegalArgumentException("unsupported map key: " + a.getClass().getName());
    }

    // ---------------------------------------------------------------- decode

    private record JField(String name, Object value) {
    }

    private record JObject(List<JField> fields) {
    }

    private Object bind(Object node, Type type) {
        Class<?> raw = rawClass(type);
        if (node == null) {
            if (raw.isPrimitive()) {
                throw new IllegalArgumentException("null for primitive " + raw);
            }
            return null;
        }
        if (raw == long.class || raw == Long.class) {
            return expect(node, Long.class);
        }
        if (raw == int.class || raw == Integer.class) {
            try {
                return Math.toIntExact(expect(node, Long.class));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("int out of range: " + node, e);
            }
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return expect(node, Boolean.class);
        }
        if (raw == String.class) {
            return expect(node, String.class);
        }
        if (raw.isEnum()) {
            return enumValue(raw, expect(node, String.class));
        }
        if (raw == List.class) {
            Type elem = typeArg(type, 0);
            List<Object> out = new ArrayList<>();
            for (Object item : expect(node, List.class)) {
                out.add(bind(item, elem));
            }
            return Collections.unmodifiableList(out);
        }
        if (raw == Map.class) {
            Type kt = typeArg(type, 0);
            Type vt = typeArg(type, 1);
            TreeMap<Object, Object> out = new TreeMap<>(keyOrder(rawClass(kt)));
            for (Object pairNode : expect(node, List.class)) {
                List<?> pair = expect(pairNode, List.class);
                if (pair.size() != 2) {
                    throw new IllegalArgumentException("map entry must be [k,v]");
                }
                Object key = bind(pair.get(0), kt);
                if (key == null) {
                    throw new IllegalArgumentException("null map key");
                }
                out.put(key, bind(pair.get(1), vt));
            }
            return Collections.unmodifiableSortedMap(out);
        }
        if (raw.isRecord() || raw.isInterface()) {
            return bindRecord(expect(node, JObject.class), raw);
        }
        throw new IllegalArgumentException("unsupported type: " + raw.getName());
    }

    private Object bindRecord(JObject obj, Class<?> declared) {
        List<JField> fields = obj.fields();
        Class<?> target = declared;
        int offset = 0;
        if (declared.isInterface()) {
            if (fields.isEmpty() || !fields.get(0).name().equals(TYPE_TAG)) {
                throw new IllegalArgumentException("missing " + TYPE_TAG + " for " + declared.getSimpleName());
            }
            String tag = expect(fields.get(0).value(), String.class);
            target = implementations(declared).get(tag);
            if (target == null) {
                throw new IllegalArgumentException("unknown subtype " + tag + " of " + declared.getSimpleName());
            }
            offset = 1;
        }
        RecordComponent[] comps = target.getRecordComponents();
        if (fields.size() - offset != comps.length) {
            throw new IllegalArgumentException("field count mismatch for " + target.getSimpleName());
        }
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            JField f = fields.get(i + offset);
            if (!f.name().equals(comps[i].getName())) {
                throw new IllegalArgumentException("expected field " + comps[i].getName() + " but got " + f.name());
            }
            args[i] = bind(f.value(), comps[i].getGenericType());
            types[i] = comps[i].getType();
        }
        try {
            Constructor<?> ctor = target.getDeclaredConstructor(types);
            return ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException("invalid " + target.getSimpleName() + ": " + e.getCause().getMessage(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot construct " + target.getName(), e);
        }
    }

    /** 接口的允许实现：先查登记表，否则按 sealed 层级解析。 */
    private Map<String, Class<?>> implementations(Class<?> iface) {
        return registry.implementations(iface).orElseGet(() -> {
            if (!iface.isSealed()) {
                throw new IllegalArgumentException("open interface " + iface.getName() + " is not registered");
            }
            return TypeRegistry.sealedLeaves(iface);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> raw, String name) {
        return Enum.valueOf((Class<? extends Enum>) raw.asSubclass(Enum.class), name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<Object> keyOrder(Class<?> keyClass) {
        if (keyClass.isEnum()) {
            return (a, b) -> ((Enum) a).compareTo((Enum) b);
        }
        return (a, b) -> ((Comparable) a).compareTo(b);
    }

    private static <T> T expect(Object node, Class<T> cls) {
        if (!cls.isInstance(node)) {
            throw new IllegalArgumentException("expected " + cls.getSimpleName() + " but got "
                    + (node == null ? "null" : node.getClass().getSimpleName()));
        }
        return cls.cast(node);
    }

    // ---------------------------------------------------------------- types

    private static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        throw new IllegalArgumentException("unsupported type " + t);
    }

    private static Type typeArg(Type t, int i) {
        if (t instanceof ParameterizedType pt) {
            return pt.getActualTypeArguments()[i];
        }
        throw new IllegalArgumentException("generic type required, got " + t);
    }

    // ---------------------------------------------------------------- parser

    private static final class Parser {
        private final String s;
        private int i;
        private int depth;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    return number();
            }
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw error("nesting deeper than " + MAX_DEPTH);
            }
        }

        void end() {
            if (i != s.length()) {
                throw error("trailing data");
            }
        }

        private JObject object() {
            enter();
            i++;
            List<JField> fields = new ArrayList<>();
            if (peek() == '}') {
                i++;
                depth--;
                return new JObject(fields);
            }
            while (true) {
                String name = string();
                expectChar(':');
                fields.add(new JField(name, value()));
                char c = next();
                if (c == '}') {
                    depth--;
                    return new JObject(fields);
                }
                if (c != ',') {
                    throw error("expected , or }");
                }
            }
        }

        private List<Object> array() {
            enter();
            i++;
            List<Object> items = new ArrayList<>();
            if (peek() == ']') {
                i++;
                depth--;
                return items;
            }
            while (true) {
                items.add(value());
                char c = next();
                if (c == ']') {
                    depth--;
                    return items;
                }
                if (c != ',') {
                    throw error("expected , or ]");
                }
            }
        }

        private String string() {
            expectChar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\' -> sb.append(e);
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw error("bad escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad escape");
                            }
                            i += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Long number() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            try {
                return Long.parseLong(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error("bad number");
            }
        }

        private void literal(String word) {
            if (!s.startsWith(word, i)) {
                throw error("bad literal");
            }
            i += word.length();
        }

        private char peek() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expectChar(char c) {
            if (next() != c) {
                throw error("expected " + c);
            }
        }

        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at " + i);
        }
    }
}
