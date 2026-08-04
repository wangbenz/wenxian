package com.wenxian.game.v014.service;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.v014.model.V014ApiModels.WorldOption;
import com.wenxian.game.v014.model.V014State.WorldProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class V014WorldCatalog {
    public static final String CUSTOM = "CUSTOM";
    private final Map<String, WorldOption> worlds = new LinkedHashMap<>();
    private final boolean customWorldEnabled;

    public V014WorldCatalog(@Value("${wenxian.v014.custom-world-enabled:false}") boolean customWorldEnabled) {
        this.customWorldEnabled = customWorldEnabled;
        add(new WorldOption("XUANXU", "玄墟界",
                "仙路真假难辨，宗门、世族、妖庭与散修仍为长生争夺资源。",
                "古典宗门修仙，机缘与代价并存。", "东陆青岐州", false, true, ""));
        add(new WorldOption("TAIHUA", "太华天",
                "仙朝与宗门共同统治九洲，出身可以带来高起点，也会带来必须偿还的责任。",
                "仙朝、世家、宗门并立，偏权谋与游历。", "云京外三郡", false, true, ""));
        add(new WorldOption("WANYAO", "万妖原",
                "人、妖、灵三族以血脉、香火与契约争夺成道之机，仙并非唯一答案。",
                "多种族修行，重血脉觉醒与族群关系。", "苍梧妖原", false, true, ""));
        add(new WorldOption(CUSTOM, "自定世界",
                "由玩家写下世界的核心设定，问仙录据此展开一部独立命卷。",
                "自行设定世界背景、修炼体系与故事基调。", "由设定决定", true,
                customWorldEnabled, customWorldEnabled ? "" : "基础玩法稳定后开放"));
    }

    private void add(WorldOption world) { worlds.put(world.code(), world); }

    public List<WorldOption> all() { return List.copyOf(worlds.values()); }

    public WorldProfile resolve(String code, String customWorld) {
        WorldOption option = worlds.get(code);
        if (option == null) throw new IllegalArgumentException("没有找到所选世界");
        if (option.custom() && !option.available()) {
            throw GameApiException.of(HttpStatus.CONFLICT, "CUSTOM_WORLD_NOT_AVAILABLE", "BUSINESS",
                    "自定世界尚未开放", "自定世界将在基础玩法稳定后开放，当前请选择一个已开放世界。",
                    List.of("选择玄墟界", "体验其他官方世界"));
        }
        if (!option.custom()) {
            return new WorldProfile(option.code(), option.name(), "OFFICIAL", option.premise(),
                    option.cultivationStyle(), option.openingRegion());
        }
        String premise = customWorld == null ? "" : customWorld.strip();
        if (premise.length() < 20) throw new IllegalArgumentException("自定世界至少需要二十个字的核心设定");
        return new WorldProfile(CUSTOM, "自定命界", "CUSTOM", premise,
                "以玩家设定为准，由故事逐步发现规则。", "故事起点");
    }
}
