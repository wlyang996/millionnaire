package com.millionnaire.engine.serialize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.EventLog;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.RoomEvent;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.RngState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalTest {
    private static final Codec CODEC = new Codec(TypeRegistry.builder()
            .add(Command.class, Tick.class, RoomCommand.class)
            .add(Event.class, KernelEvent.class, RoomEvent.class)
            .build());

    /** 测试用 record：覆盖各种支持的字段类型（command 为 sealed 接口，自动解析）。 */
    public record Sample(long big, int small, boolean flag, String text, DrawPoint point, List<Long> list,
                         Map<String, Integer> byName, Map<Integer, String> byNumber, RoomCommand command, String nothing) {
        public Sample {
            list = Immutable.list(list);
            byName = Immutable.sortedMap(byName);
            byNumber = Immutable.sortedMap(byNumber);
        }
    }

    private static Sample sample() {
        Map<String, Integer> byName = new LinkedHashMap<>();
        byName.put("zeta", 1);
        byName.put("alpha", 2);
        Map<Integer, String> byNumber = new LinkedHashMap<>();
        byNumber.put(10, "ten");
        byNumber.put(9, "nine");
        byNumber.put(-1, "minus");
        return new Sample(Long.MIN_VALUE, Integer.MAX_VALUE, true, "引号\"反斜杠\\换行\n制表\t",
                DrawPoint.MOVE_DIE, List.of(Long.MAX_VALUE, 0L, -1L), byName, byNumber,
                new RoomCommand.Join("p1", "Alice"), null);
    }

    private static final String SAMPLE_TEXT = "{\"big\":-9223372036854775808,\"small\":2147483647,\"flag\":true,"
            + "\"text\":\"引号\\\"反斜杠\\\\换行\\u000a制表\\u0009\",\"point\":\"MOVE_DIE\","
            + "\"list\":[9223372036854775807,0,-1],"
            + "\"byName\":[[\"alpha\",2],[\"zeta\",1]],"
            + "\"byNumber\":[[-1,\"minus\"],[9,\"nine\"],[10,\"ten\"]],"
            + "\"command\":{\"@type\":\"Join\",\"playerId\":\"p1\",\"nickname\":\"Alice\"},"
            + "\"nothing\":null}";

    @Test
    void stableOutputWithFixedFieldOrderSortedKeysAndTags() {
        assertEquals(SAMPLE_TEXT, Canonical.encode(sample()));
    }

    @Test
    void roundTripPreservesEqualityAndBytes() {
        Sample back = Canonical.decode(SAMPLE_TEXT, Sample.class);
        assertEquals(sample(), back);
        byte[] bytes = Canonical.bytes(sample());
        assertArrayEquals(SAMPLE_TEXT.getBytes(StandardCharsets.UTF_8), bytes);
        assertEquals(sample(), Canonical.decode(bytes, Sample.class));
    }

    @Test
    void utf8ByteRoundTripForEmojiCjkAndControlCharacters() {
        for (String s : List.of("\uD83D\uDE00", "汉字", "\u0000\u001f", "a\uD83C\uDFFDb", "\u007f\u00e9")) {
            byte[] bytes = Canonical.bytes(s);
            assertEquals(s, Canonical.decode(bytes, String.class), "round trip of " + s.codePoints().boxed().toList());
            assertArrayEquals(bytes, Canonical.bytes(Canonical.decode(bytes, String.class)));
        }
        assertEquals("\"\\u0000\\u001f\"", Canonical.encode("\u0000\u001f"));
        assertArrayEquals(new byte[] {'"', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80, '"'},
                Canonical.bytes("\uD83D\uDE00"));
    }

    @Test
    void loneSurrogatesAreRejectedSoBytesNeverCollide() {
        for (String bad : List.of("\uD800", "\uDFFF", "x\uDC00", "\uD83Dx", "\uDE00\uD83D")) {
            assertThrows(IllegalArgumentException.class, () -> Canonical.bytes(bad), bad.codePoints().boxed().toList().toString());
        }
        // 转义形式的孤立代理在解码后重编码时被拒绝
        assertThrows(IllegalArgumentException.class, () -> Canonical.decode("\"\\ud800\"", String.class));
        // 非法 UTF-8 字节被拒绝而不是替换成 U+FFFD
        assertThrows(IllegalArgumentException.class,
                () -> Canonical.decode(new byte[] {'"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"'}, String.class));
        assertThrows(IllegalArgumentException.class, () -> Canonical.decode(new byte[] {'"', (byte) 0xC3, '"'}, String.class));
        assertNotEquals(Canonical.sha256Hex("\uD83D\uDE00"), Canonical.sha256Hex("?"));
    }

    @Test
    void openInterfacesNeedRegistrationAndTagsMustBeUnique() {
        EventLog log = new EventLog(List.of(
                new KernelEvent.InputAccepted(1, 100, "ab"),
                new KernelEvent.InputRejected(2, 150, "cd", RejectionCode.NOT_HOST, "p2"),
                new KernelEvent.RandomDrawn("proto", DrawPoint.MOVE_DIE, 6, 3, new RngState(1, 2, 3, 4)),
                new RoomEvent.HostChanged(null),
                new RoomEvent.RoomClosed()));
        assertEquals(log, CODEC.decode(CODEC.encode(log), EventLog.class));
        assertThrows(IllegalArgumentException.class, () -> Canonical.encode(log), "Event is open and not registered by default");
        Input in = new Input(7, 1234, new Tick());
        assertEquals("{\"seq\":7,\"serverTime\":1234,\"command\":{\"@type\":\"Tick\"}}", CODEC.encode(in));

        record Join(String x) implements Command {
            @Override
            public String actor() {
                return x;
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> TypeRegistry.builder().add(Command.class, RoomCommand.class).add(Command.class, Join.class).build());
        assertThrows(IllegalArgumentException.class,
                () -> CODEC.encode(new Input(1, 1, new Join("x"))), "unregistered implementation");
    }

    @Test
    void rejectsNonCanonicalInput() {
        String good = CODEC.encode(new Input(7, 1234, new Tick()));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(good.replace(",", ", "), Input.class));
        assertThrows(IllegalArgumentException.class,
                () -> CODEC.decode("{\"serverTime\":1234,\"seq\":7,\"command\":{\"@type\":\"Tick\"}}", Input.class));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(good.replace(":7", ":07"), Input.class));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(good + " ", Input.class));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(good.replace("Tick", "Nope"), Input.class));
        assertThrows(IllegalArgumentException.class,
                () -> CODEC.decode("{\"seq\":7,\"serverTime\":1234,\"command\":{\"@type\":\"Tick\"},\"x\":1}", Input.class));
        assertThrows(IllegalArgumentException.class,
                () -> CODEC.decode("{\"seq\":99999999999999999999,\"serverTime\":1,\"command\":{\"@type\":\"Tick\"}}", Input.class));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(good.replace(":7", ":\u0667"), Input.class),
                "non-ASCII digits");
    }

    @Test
    void rejectsUnsortedOrDuplicateMapKeys() {
        String unsorted = SAMPLE_TEXT.replace("[[\"alpha\",2],[\"zeta\",1]]", "[[\"zeta\",1],[\"alpha\",2]]");
        assertThrows(IllegalArgumentException.class, () -> Canonical.decode(unsorted, Sample.class));
        String duplicate = SAMPLE_TEXT.replace("[[\"alpha\",2],[\"zeta\",1]]", "[[\"alpha\",2],[\"alpha\",2],[\"zeta\",1]]");
        assertThrows(IllegalArgumentException.class, () -> Canonical.decode(duplicate, Sample.class));
    }

    @Test
    void refusesUnsupportedTypes() {
        record WithDouble(double d) {
        }
        assertThrows(IllegalArgumentException.class, () -> Canonical.encode(new WithDouble(1.5)));
    }

    @Test
    void envelopeHeaderIsParsedBeforeBody() {
        String text = Envelope.wrap("snapshot", "{}");
        assertEquals("millionnaire-engine/1/snapshot\n{}", text);
        Envelope e = Envelope.parse("millionnaire-engine/7/snapshot\nanything", "snapshot");
        assertEquals(7, e.formatVersion());
        assertEquals("anything", e.body());
        assertThrows(Envelope.UnsupportedFormatException.class, () -> Envelope.parse(text, "event-log"));
        assertThrows(Envelope.UnsupportedFormatException.class, () -> Envelope.parse("other/1/snapshot\n{}", "snapshot"));
        assertThrows(Envelope.UnsupportedFormatException.class, () -> Envelope.parse("millionnaire-engine/01/snapshot\n{}", "snapshot"));
    }
}
