package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从人物已经提交的结构化属性中读取可用于精确身份解析的称谓。
 *
 * <p>人物当前姓名并不是唯一合法称呼，道号、职务和已确认称谓同样可能被玩家长期使用。本类只读取
 * 明确的结构字段，不扫描自由摘要、不做中文关键词或模糊相似度匹配，因此不会把“老人”“路人”等
 * 场景泛称猜成某个既有人物。</p>
 */
public final class StoryCharacterIdentityNames {
    private static final List<String> SCALAR_FIELDS = List.of(
            "role", "title", "daoName", "courtesyName", "nickname"
    );
    private static final List<String> ARRAY_FIELDS = List.of("aliases", "titles");

    private StoryCharacterIdentityNames() {}

    /** 返回人物当前结构化身份称谓，保持字段顺序并去重。 */
    public static List<String> from(EntityState entity) {
        if (entity == null || !"CHARACTER".equalsIgnoreCase(entity.entityType())) return List.of();
        JsonNode attributes = entity.attributes();
        if (attributes == null || !attributes.isObject()) return List.of();
        Set<String> values = new LinkedHashSet<>();
        SCALAR_FIELDS.forEach(field -> add(values, attributes.path(field)));
        ARRAY_FIELDS.forEach(field -> {
            JsonNode array = attributes.path(field);
            if (array.isArray()) array.forEach(value -> add(values, value));
        });
        return List.copyOf(new ArrayList<>(values));
    }

    private static void add(Set<String> values, JsonNode node) {
        if (node == null || !node.isTextual()) return;
        String value = node.asText("").strip();
        if (!value.isBlank()) values.add(value);
    }
}
