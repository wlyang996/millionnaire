package com.millionnaire.gateway.characters;

import com.millionnaire.gateway.room.ClientException;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/** Decode bounded PNGs before accepting them into an immutable character pack. */
public final class CharacterImages {
    public static final List<String> SLOTS = List.of("avatar", "pawn", "portrait", "give", "receive", "worry", "celebrate");
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public record Info(int width, int height, String sha256, int bytes) {}
    public static Info inspect(String slot, byte[] bytes) {
        if (!SLOTS.contains(slot)) throw bad("未知素材项目");
        if (bytes == null || bytes.length < 24 || bytes.length > MAX_BYTES) throw bad("每张图片须为 PNG，且不超过 5MB");
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw bad("无法读取图片");
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase("png")) throw bad("仅支持 PNG");
                reader.setInput(in, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 64 || h < 64 || w > 2048 || h > 2048) throw bad("宽高须在 64～2048 像素内");
                double ratio = (double) w / h;
                if (ratio < .35 || ratio > 2) throw bad("图片宽高比须在 0.35～2 之间");
                if (slot.equals("avatar") && Math.abs(ratio - 1) > .05) throw bad("头像须为正方形");
                var image = reader.read(0);
                if (!slot.equals("avatar")) {
                    if (!image.getColorModel().hasAlpha()) throw bad("人物素材须有透明背景");
                    int transparent = 0;
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                        if ((image.getRGB(x, y) >>> 24) == 0) transparent++;
                    if (transparent < (long) w * h / 100) throw bad("透明背景至少须占图片的 1%");
                }
                return new Info(w, h, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length);
            } finally { reader.dispose(); }
        } catch (ClientException e) { throw e; }
        catch (Exception e) { throw bad("PNG 损坏或无法解码"); }
    }
    private static ClientException bad(String message) { return new ClientException("INVALID_IMAGE", message); }
    private CharacterImages() {}
}
