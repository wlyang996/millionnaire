package com.millionnaire.gateway.audio;

import com.millionnaire.gateway.room.ClientException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** PCM WAV is decoded directly: no external decoder or encoder padding is needed for looping. */
public final class AudioFiles {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public record Info(String sha256, int bytes, double seconds, int sampleRate, int channels) {}
    private AudioFiles() {}
    public static Info inspect(byte[] data) {
        if (data == null || data.length < 44 || data.length > MAX_BYTES) throw bad("音频须为有效 WAV，大小不超过 10MB");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (!tag(data, 0, "RIFF") || !tag(data, 8, "WAVE") || Integer.toUnsignedLong(b.getInt(4)) != data.length - 8L)
            throw bad("请选择 PCM WAV 音频；MP3 请先转换为 WAV");
        int channels = 0, rate = 0, block = 0, pcmBytes = 0;
        boolean format = false, audio = false;
        for (int p = 12; p < data.length;) {
            if (p + 8 > data.length) throw bad("WAV 文件被截断");
            long size = Integer.toUnsignedLong(b.getInt(p + 4));
            long end = p + 8L + size + (size & 1);
            if (end > data.length) throw bad("WAV 数据不完整");
            if (tag(data, p, "fmt ")) {
                if (format || size < 16) throw bad("WAV 格式块无效");
                format = true;
                channels = Short.toUnsignedInt(b.getShort(p + 10)); rate = b.getInt(p + 12);
                int byteRate = b.getInt(p + 16); block = Short.toUnsignedInt(b.getShort(p + 20));
                if (Short.toUnsignedInt(b.getShort(p + 8)) != 1 || b.getShort(p + 22) != 16
                        || channels < 1 || channels > 2 || !java.util.Set.of(22050, 44100, 48000).contains(rate)
                        || block != channels * 2 || byteRate != rate * block)
                    throw bad("WAV 需为 16 位 PCM、单声道或双声道，采样率 22050/44100/48000Hz");
            } else if (tag(data, p, "data")) {
                if (audio || !format || size == 0 || size % block != 0) throw bad("WAV 采样数据无效");
                audio = true; pcmBytes = (int) size;
            }
            p = (int) end;
        }
        double seconds = format ? pcmBytes / (double) (rate * block) : 0;
        if (!audio || seconds < .1 || seconds > 120) throw bad("音频时长须在 0.1～120 秒内");
        try { return new Info(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)), data.length, seconds, rate, channels); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean tag(byte[] data, int p, String s) {
        return new String(data, p, 4, StandardCharsets.US_ASCII).equals(s);
    }
    static ClientException bad(String text) { return new ClientException("INVALID_AUDIO", text); }
}
