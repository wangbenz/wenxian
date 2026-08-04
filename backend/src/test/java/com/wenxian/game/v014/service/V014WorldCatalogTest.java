package com.wenxian.game.v014.service;

import com.wenxian.game.api.GameApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014WorldCatalogTest {
    @Test
    void customWorldIsVisibleButUnavailableByDefault() {
        V014WorldCatalog catalog = new V014WorldCatalog(false);

        var custom = catalog.all().stream().filter(world -> world.custom()).findFirst().orElseThrow();
        assertFalse(custom.available());
        assertEquals("基础玩法稳定后开放", custom.availabilityNote());

        GameApiException error = assertThrows(GameApiException.class,
                () -> catalog.resolve(V014WorldCatalog.CUSTOM, "这是一个足够长但尚未开放的自定义世界设定文本"));
        assertEquals("CUSTOM_WORLD_NOT_AVAILABLE", error.code());
    }

    @Test
    void featureFlagCanOpenCustomWorldLaterWithoutChangingTheApi() {
        V014WorldCatalog catalog = new V014WorldCatalog(true);

        var custom = catalog.all().stream().filter(world -> world.custom()).findFirst().orElseThrow();
        assertTrue(custom.available());
        assertEquals("CUSTOM", catalog.resolve(V014WorldCatalog.CUSTOM,
                "这里的山川会记住来客姓名，修行依靠交换记忆与岁月而完成。流传下来的仙法各有代价。"
        ).source());
    }
}
