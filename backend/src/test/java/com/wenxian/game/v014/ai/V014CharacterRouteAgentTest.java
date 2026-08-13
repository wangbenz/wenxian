package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V014CharacterRouteAgentTest {
    private final V014CharacterRouteAgent agent = new V014CharacterRouteAgent(
            null, new ObjectMapper(), null, null);

    @Test
    void acceptsFencedJsonAndAssignsStableIds() {
        var routes = agent.parse("""
                ```json
                {"routes":[
                  {"title":"石中初醒","origin":"一方古砚在灵脉中生出灵智。","opening":"矿洞将塌，采石人仍被困在深处。","preference":"探索来历与人情。"},
                  {"title":"客栈旧契","origin":"镇店石狮受百年香火点化。","opening":"客栈易主，旧契约忽然显形。","preference":"经营关系与地方故事。"},
                  {"title":"剑冢余音","origin":"断剑残片听尽剑冢风声后化灵。","opening":"盗剑者闯入，惊醒沉睡的残灵。","preference":"游历、剑道与旧案。"}
                ]}
                ```
                """);

        assertEquals(3, routes.size());
        assertEquals("route-1", routes.getFirst().id());
        assertEquals("石中初醒", routes.getFirst().title());
        assertEquals("境界未明", routes.getFirst().startingRealm());
    }

    @Test
    void keepsStartingRealmConsistentWithPowerfulOrigin() {
        var routes = agent.parse("""
                {"routes":[
                  {"title":"狐主临世","origin":"九尾天狐一脉之主，修为冠绝北荒。","opening":"你在妖庭来使面前醒来。","preference":"爽文与势力经营。","startingRealm":"妖王境"},
                  {"title":"旧王归山","origin":"昔日妖王散去修为后重归故地。","opening":"旧部正在山门外等候。","preference":"重修与旧缘。","startingRealm":"启灵"},
                  {"title":"山野幼狐","origin":"山野白狐初次生出灵智。","opening":"猎户的脚步正在逼近。","preference":"自由成长。","startingRealm":"启灵"}
                ]}
                """);

        assertEquals("妖王境", routes.getFirst().startingRealm());
    }

    @Test
    void rejectsAnythingOtherThanThreeCompleteRoutes() {
        assertThrows(IllegalStateException.class, () -> agent.parse("""
                {"routes":[{"title":"一","origin":"出身","opening":"开局","preference":"方向"}]}
                """));
    }
}
