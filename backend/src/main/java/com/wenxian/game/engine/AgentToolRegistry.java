package com.wenxian.game.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {
    private final ObjectMapper objectMapper;

    public AgentToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> definitions() {
        return List.of(
                tool("get_player_state", "读取主角当前权威状态，包括境界、身体、身份和目标", emptySchema()),
                tool("get_inventory", "读取主角当前真实拥有的背包物品", emptySchema()),
                tool("get_abilities", "读取主角已经掌握的天赋、功法和能力", emptySchema()),
                tool("get_character", "读取玩家已经认识的指定角色。不得用于探查未知角色。",
                        objectSchema(Map.of("characterId", stringProperty("角色ID")), List.of("characterId"))),
                tool("get_recent_events", "读取当前世界时间线中最近发生的事件", emptySchema()),
                tool("get_active_threads", "读取当前进行中的剧情线、危险和未解线索", emptySchema())
        );
    }

    public String execute(String name, String rawArguments, GameState state) {
        try {
            JsonNode arguments = rawArguments == null || rawArguments.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(rawArguments);
            Object result = switch (name) {
                case "get_player_state" -> state.player;
                case "get_inventory" -> state.inventory;
                case "get_abilities" -> state.abilities;
                case "get_recent_events" -> state.events.stream().limit(12).toList();
                case "get_active_threads" -> state.threads;
                case "get_character" -> character(arguments.path("characterId").asText(), state);
                default -> Map.of("error", "UNKNOWN_TOOL", "message", "工具不存在");
            };
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            return "{\"error\":\"INVALID_TOOL_ARGUMENTS\"}";
        }
    }

    private Object character(String characterId, GameState state) {
        if (characterId == null || characterId.isBlank()) {
            return Map.of("error", "MISSING_CHARACTER_ID");
        }
        return state.characters.stream().filter(value -> characterId.equals(value.id)).findFirst()
                .<Object>map(value -> value)
                .orElseGet(() -> Map.of("error", "CHARACTER_NOT_KNOWN"));
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", description, "parameters", parameters
        ));
    }

    private Map<String, Object> emptySchema() {
        return objectSchema(Map.of(), List.of());
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false
        );
    }

    private Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }
}
