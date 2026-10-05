package com.millionnaire.engine.serialize;

/**
 * 持久化信封：首行 {@code millionnaire-engine/<formatVersion>/<kind>}，换行后为规范正文。
 * 读取时先解析首行、按 formatVersion 选择解码器，再解析正文 record；
 * 因此字段改名等格式变化不会在读到版本号之前就导致解析失败。
 */
public record Envelope(int formatVersion, String kind, String body) {
    /** 当前写出的格式版本。 */
    public static final int FORMAT_VERSION = 1;
    private static final String MAGIC = "millionnaire-engine";

    public Envelope {
        if (kind == null || !kind.matches("[a-z][a-z-]*")) {
            throw new IllegalArgumentException("invalid envelope kind: " + kind);
        }
        if (body == null) {
            throw new IllegalArgumentException("body missing");
        }
    }

    public static String wrap(String kind, String body) {
        return new Envelope(FORMAT_VERSION, kind, body).text();
    }

    public String text() {
        return MAGIC + "/" + formatVersion + "/" + kind + "\n" + body;
    }

    /** 只解析信封头（不触碰正文）；kind 不符时抛 {@link UnsupportedFormatException}。版本由调用方选择解码器。 */
    public static Envelope parse(String text, String expectedKind) {
        int nl = text.indexOf('\n');
        String[] head = (nl < 0 ? text : text.substring(0, nl)).split("/", -1);
        if (nl < 0 || head.length != 3 || !head[0].equals(MAGIC) || !head[1].matches("[1-9][0-9]{0,8}")) {
            throw new UnsupportedFormatException("not an engine envelope");
        }
        int version = Integer.parseInt(head[1]);
        if (!head[2].equals(expectedKind)) {
            throw new UnsupportedFormatException("expected kind " + expectedKind + " but got " + head[2]);
        }
        return new Envelope(version, head[2], text.substring(nl + 1));
    }

    /** 信封格式错误或版本不受支持。 */
    public static final class UnsupportedFormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public UnsupportedFormatException(String message) {
            super(message);
        }
    }
}
