package com.millionnaire.gateway.audio;

import static org.assertj.core.api.Assertions.*;
import com.millionnaire.gateway.room.ClientException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

class AudioCatalogTest {
    @Test void candidatesRemainPrivateAndSilentUntilExplicitPublication() {
        var c=new AudioCatalog(()->null,new Objects(),Clock.systemUTC());
        var candidate=c.assets().stream().filter(a->a.kind().equals("effect")).findFirst().orElseThrow();
        assertThat(c.assets().stream().filter(a->a.kind().equals("effect"))).hasSize(11);
        assertThat(c.current().settings().effectsEnabled()).isFalse();
        assertThatThrownBy(()->c.bytes(candidate.id(),false)).hasMessageContaining("不存在");
        assertThat(c.bytes(candidate.id(),true)).isNotEmpty();
        var s=c.current().settings();var bindings=new HashMap<>(s.effects());bindings.put("dice",candidate.id());
        var draft=c.save(new AudioCatalog.Settings(true,30,true,65,400,s.scenes(),bindings),0,0);
        assertThatThrownBy(()->c.bytes(candidate.id(),false)).hasMessageContaining("不存在");
        c.publish(draft.revision(),0);assertThat(c.bytes(candidate.id(),false)).isNotEmpty();
        c.rollback(0,c.current().version(),c.draft().revision());assertThat(c.current().settings().effectsEnabled()).isFalse();
        assertThat(c.bytes(candidate.id(),false)).isNotEmpty(); // Prior releases retain immutable resources.
    }
    static class Objects implements AudioObjectStore {
        final Map<String, byte[]> data = new HashMap<>(); boolean fail;
        public boolean ready() { return true; }
        public void put(String key, byte[] bytes) { if (fail) throw new ClientException("COS_FAILED", "test"); data.put(key, bytes.clone()); }
        public byte[] get(String key) { return data.get(key).clone(); }
    }
    static byte[] wav() {
        int size = 22050 * 2;
        var b = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(size + 36).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(16).putShort((short)1).putShort((short)1).putInt(22050).putInt(44100).putShort((short)2).putShort((short)16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(size);
        return b.array();
    }
    @Test void validatesDecodedPcmAndRejectsTruncationWrongFormatAndOversize() {
        assertThat(AudioFiles.inspect(wav()).seconds()).isEqualTo(1);
        var broken = wav(); broken[20] = 3;
        assertThatThrownBy(() -> AudioFiles.inspect(broken)).hasMessageContaining("PCM");
        assertThatThrownBy(() -> AudioFiles.inspect(Arrays.copyOf(wav(), 99))).hasMessageContaining("PCM WAV");
        assertThatThrownBy(() -> AudioFiles.inspect(new byte[AudioFiles.MAX_BYTES + 1])).hasMessageContaining("10MB");
        var trailing = Arrays.copyOf(wav(), wav().length + 1); ByteBuffer.wrap(trailing).order(ByteOrder.LITTLE_ENDIAN).putInt(4,trailing.length-8);
        assertThatThrownBy(() -> AudioFiles.inspect(trailing)).hasMessageContaining("截断");
    }
    @Test void uploadDraftPublishAndRollbackKeepOldAssetsAndProtectUnpublished() {
        var o = new Objects(); var c = new AudioCatalog(() -> null, o, Clock.systemUTC());
        assertThat(c.assets().stream().filter(a->a.kind().equals("music"))).extracting(AudioCatalog.Asset::id).containsExactly("morning-town", "friends-play");
        var a = c.upload("新背景", "music", wav());
        assertThatThrownBy(() -> c.bytes(a.id(), false)).hasMessageContaining("不存在");
        var s = c.current().settings(); var scenes = new HashMap<>(s.scenes()); scenes.put("board", new AudioCatalog.Binding(a.id(),true));
        var custom = new AudioCatalog.Settings(true,40,false,65,500,scenes,s.effects());
        var d = c.save(custom,0,0);
        assertThat(c.current().version()).isZero(); assertThat(c.bytes(a.id(),true)).isEqualTo(wav());
        var r = c.publish(d.revision(),0);
        assertThat(c.current()).isEqualTo(r); assertThat(c.bytes(a.id(),false)).isEqualTo(wav());
        assertThatThrownBy(() -> c.save(s,d.revision(),0)).isInstanceOf(ClientException.class).hasMessageContaining("已变化");
        c.rollback(0,r.version(),c.draft().revision());
        assertThat(c.current().settings()).isEqualTo(AudioCatalog.defaults());
        assertThat(c.load(r.version()).settings()).isEqualTo(custom);
        assertThat(c.bytes(a.id(),false)).isEqualTo(wav());
    }
    @Test void failedUploadOrReadbackNeverActivatesCorruptAssetsAndKindsStaySeparate() {
        var o = new Objects(); var c = new AudioCatalog(() -> null,o,Clock.systemUTC());
        o.fail = true; assertThatThrownBy(() -> c.upload("失败", "music", wav())).hasMessageContaining("test");
        assertThat(c.assets().stream().filter(a->!a.builtin())).isEmpty(); o.fail = false;
        var a = c.upload("独立音效", "effect", wav());
        var s = c.current().settings(); var scenes = new HashMap<>(s.scenes()); scenes.put("board",new AudioCatalog.Binding(a.id(),true));
        assertThatThrownBy(() -> c.save(new AudioCatalog.Settings(true,30,true,65,0,scenes,s.effects()),0,0)).hasMessageContaining("背景音乐");
        var effects = new HashMap<>(s.effects()); effects.put("dice",a.id());
        var d = c.save(new AudioCatalog.Settings(true,30,true,65,0,s.scenes(),effects),0,0);
        var changed = wav(); changed[44] = 2; o.data.put(a.key(),changed);
        assertThatThrownBy(() -> c.publish(d.revision(),0)).hasMessageContaining("完整性");
        assertThat(c.current().version()).isZero();
    }
}
