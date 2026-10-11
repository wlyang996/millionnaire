package com.millionnaire.gateway.characters;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CharacterCatalogTest {
    @Test void slowUploadsDoNotBlockRoomCatalogReadsAndCannotOverwriteEditedDrafts() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var store = new Objects() {
            @Override public void put(String key, byte[] bytes) {
                entered.countDown();
                try { if (!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("test upload timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                super.put(key,bytes);
            }
        };
        var c = new CharacterCatalog(() -> null,store,Clock.systemUTC());
        var p = c.create(null,"上传中",0,true,0); var png = png(true,64,64);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var upload = executor.submit(() -> c.upload(p.packId(),"avatar",0,png));
            try {
                assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(c::current).get(1,TimeUnit.SECONDS).version()).isZero();
                c.edit(p.packId(),0,"更新资料",1,true,0);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> upload.get(1,TimeUnit.SECONDS)).hasRootCauseMessage("草稿已变化，请刷新");
            assertThat(c.pack(p.packId()).character().name()).isEqualTo("更新资料");
            assertThat(c.pack(p.packId()).character().images()).isEmpty();
        }
    }
    static class Objects implements CharacterObjectStore {
        final Map<String,byte[]> values = new HashMap<>();
        boolean fail;
        public boolean ready() { return true; }
        public void put(String key, byte[] bytes) { if (fail) throw new IllegalStateException("upload failed"); values.put(key,bytes); }
        public byte[] get(String key) { return values.get(key); }
    }
    static byte[] png(boolean transparent, int width, int height) throws Exception {
        var image = new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for (int y=0; y<height; y++) for (int x=0; x<width; x++) image.setRGB(x,y,transparent && x<width/4 ? 0 : 0xff7799bb);
        var out = new ByteArrayOutputStream(); ImageIO.write(image,"png",out); return out.toByteArray();
    }
    static CharacterCatalog.Pack complete(CharacterCatalog c, CharacterCatalog.Pack p) throws Exception {
        for (String slot : CharacterImages.SLOTS) p = c.upload(p.packId(),slot,p.revision(),png(true,64,64));
        return p;
    }
    @Test void onlyCompletePacksArePublishedAndOldCatalogsStayImmutable() throws Exception {
        var store = new Objects(); var c = new CharacterCatalog(() -> null, store, Clock.systemUTC());
        var p = c.create(null,"新人物",9,true,1);
        assertThat(p.character().id()).isEqualTo(9);
        assertThat(c.selectable(9)).isFalse();
        String id = p.packId();
        assertThatThrownBy(() -> c.publish(id,0,0)).hasMessageContaining("完整七项");
        assertThatThrownBy(() -> c.image(id,"avatar",false)).hasMessageContaining("不存在");
        p = complete(c,p);
        var first = c.publish(id,p.revision(),0);
        assertThat(c.selectable(9)).isTrue();
        assertThat(c.load(0).characters()).hasSize(8);
        assertThat(c.forRoom(9,0)).isZero();
        assertThat(c.forRoom(9,first.version())).isEqualTo(9);
        assertThat(c.forRoom(8,0)).isEqualTo(8);
        assertThatThrownBy(() -> c.upload(id,"avatar",8,png(true,64,64))).hasMessageContaining("不可修改");
        var disabled = complete(c,c.create(9,"新人物改名",0,false,1));
        c.publish(disabled.packId(),disabled.revision(),first.version());
        assertThat(c.selectable(9)).isFalse();
        assertThat(c.load(first.version()).characters().stream().filter(e -> e.id()==9).findFirst().orElseThrow().enabled()).isTrue();
        c.rollback(first.version(),c.activeId());
        assertThat(c.selectable(9)).isTrue();
    }
    @Test void stalePublicationAndCorruptObjectsNeverActivatePartialPacks() throws Exception {
        var store = new Objects(); var c = new CharacterCatalog(() -> null,store,Clock.systemUTC());
        var p = complete(c,c.create(null,"测试",9,true,0));
        assertThatThrownBy(() -> c.publish(p.packId(),p.revision(),99)).hasMessageContaining("已变化");
        var asset = p.character().images().get("give");
        store.values.put(asset.key(),png(true,80,80));
        assertThatThrownBy(() -> c.publish(p.packId(),p.revision(),0)).hasMessageContaining("校验失败");
        assertThat(c.activeId()).isZero(); assertThat(c.pack(p.packId()).published()).isFalse();
    }
    @Test void failedUploadLeavesMetadataAndRevisionUnchanged() throws Exception {
        var store = new Objects(); var c = new CharacterCatalog(() -> null,store,Clock.systemUTC());
        var p = c.create(null,"测试",0,true,0); store.fail = true;
        assertThatThrownBy(() -> c.upload(p.packId(),"avatar",0,png(true,64,64))).hasMessage("upload failed");
        assertThat(c.pack(p.packId()).revision()).isZero(); assertThat(c.pack(p.packId()).character().images()).isEmpty();
        assertThatThrownBy(() -> c.create(8,"机器人",0,true,0)).hasMessageContaining("机器人");
    }
    @Test void pngValidationRejectsOpaquePosesMalformedPngAndBadDimensions() throws Exception {
        assertThatThrownBy(() -> CharacterImages.inspect("give",png(false,64,64))).hasMessageContaining("透明背景");
        assertThatThrownBy(() -> CharacterImages.inspect("avatar",png(false,64,100))).hasMessageContaining("正方形");
        assertThatThrownBy(() -> CharacterImages.inspect("pawn",png(true,32,32))).hasMessageContaining("64～2048");
        assertThatThrownBy(() -> CharacterImages.inspect("avatar",new byte[24])).hasMessageContaining("无法读取");
        assertThatThrownBy(() -> CharacterImages.inspect("avatar",new byte[CharacterImages.MAX_BYTES+1])).hasMessageContaining("5MB");
        assertThat(CharacterImages.inspect("avatar",png(false,64,64)).sha256()).hasSize(64);
    }
}
