package com.millionnaire.gateway.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.config.ExperienceConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExperienceConfigTest {
    @Test void oldSnapshotAndPublishedConfigRemainValid() throws Exception {
        var mapper=new ObjectMapper();
        var old=mapper.valueToTree(SettingsMapper.defaults());((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("experience");
        var parsed=mapper.treeToValue(old,GameSettings.class);
        assertEquals(ExperienceConfig.DEFAULT,SettingsMapper.experienceOf(parsed));
        assertTrue(SettingsMapper.validate(parsed).isEmpty());
        var json=mapper.valueToTree(SettingsMapper.defaults());
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.get("experience")).put("emoteCooldownMs",1500).put("hapticAllowed",false);
        var custom=SettingsMapper.normalized(mapper.treeToValue(json,GameSettings.class));
        assertEquals(1500,SettingsMapper.toRuleConfig(custom).experience().emoteCooldownMs());
        assertFalse(SettingsMapper.experienceOf(custom).hapticAllowed());
    }
    @Test void invalidRateLimitCannotBePublished() throws Exception {
        var mapper=new ObjectMapper();var tree=mapper.valueToTree(SettingsMapper.defaults());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("experience")).put("emoteCooldownMs",0);
        assertTrue(SettingsMapper.validate(mapper.treeToValue(tree,GameSettings.class)).stream().anyMatch(x->x.contains("表情间隔")));
    }
}
