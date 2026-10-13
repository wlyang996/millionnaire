package com.millionnaire.gateway.characters;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static com.millionnaire.gateway.characters.CharacterCatalogTest.*;

class CharacterMotionTest {
    @Test void legacyStaticPacksRemainValidButPartialMotionNeverPublishes() throws Exception {
        var catalog=new CharacterCatalog(()->null,new Objects(),Clock.systemUTC());
        var old=complete(catalog,catalog.create(null,"静态人物",0,true,0));
        catalog.publish(old.packId(),old.revision(),0);
        var motion=complete(catalog,catalog.create(null,"动作人物",1,true,0));
        motion=catalog.upload(motion.packId(),"pawncrouch",motion.revision(),png(true,64,64));
        final var partial=motion;
        assertThatThrownBy(()->catalog.publish(partial.packId(),partial.revision(),catalog.activeId())).hasMessageContaining("四张");
        for(String slot:CharacterImages.MOTION_SLOTS.subList(1,4))motion=catalog.upload(motion.packId(),slot,motion.revision(),png(true,64,64));
        var released=catalog.publish(motion.packId(),motion.revision(),catalog.activeId());
        assertThat(released.characters().stream().filter(c->c.name().equals("动作人物")).findFirst().orElseThrow().images()).hasSize(11);
        assertThat(catalog.image(motion.packId(),"pawnairborne",false)).isNotEmpty();
        catalog.rollback(1,catalog.activeId());assertThat(catalog.current().characters().stream().anyMatch(c->c.name().equals("动作人物"))).isFalse();
    }
    @Test void mismatchedCanvasesAndCorruptFramesCannotActivate() throws Exception {
        var store=new Objects();var catalog=new CharacterCatalog(()->null,store,Clock.systemUTC());
        var p=complete(catalog,catalog.create(null,"画布测试",0,true,0));
        for(String slot:CharacterImages.MOTION_SLOTS)p=catalog.upload(p.packId(),slot,p.revision(),png(true,slot.equals("pawnland")?80:64,64));
        final var mismatched=p;
        assertThatThrownBy(()->catalog.publish(mismatched.packId(),mismatched.revision(),0)).hasMessageContaining("画布尺寸");
        p=catalog.upload(p.packId(),"pawnland",p.revision(),png(true,64,64));
        store.values.put(p.character().images().get("pawnready").key(),png(true,80,80));
        final var damaged=p;assertThatThrownBy(()->catalog.publish(damaged.packId(),damaged.revision(),0)).hasMessageContaining("校验失败");
        assertThat(catalog.activeId()).isZero();
    }
}
