package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

/** 产生版本固定、Unicode 规范化且键顺序稳定的世界包 JSON 和 SHA-256。 */
final class WorldPackageCanonicalizer {
    private final ObjectMapper mapper;

    WorldPackageCanonicalizer(ObjectMapper source) {
        if (source == null) throw new IllegalArgumentException("JSON mapper 不能为空");
        this.mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    CanonicalResult canonicalize(WorldPackageDocument document, CompiledWorldPackage compiled) {
        try {
            String fullDocument = write(document);
            ContentPayload payload = new ContentPayload(
                    new ContentContract(
                            document.identity().formatVersion(),
                            document.identity().runtimeContract(),
                            document.identity().requiredCapabilities().stream().sorted().toList()),
                    document.metadata(),
                    document.world(),
                    document.creation(),
                    document.progression(),
                    document.loreEntries().stream().sorted(Comparator.comparing(
                            WorldPackageDocument.LoreEntry::loreId)).toList(),
                    document.assets().stream().sorted(Comparator.comparing(
                            WorldPackageDocument.AssetDescriptor::path)).toList()
            );
            String contentPayload = write(payload);
            String compiledJson = write(compiled);
            return new CanonicalResult(fullDocument, sha256(contentPayload), compiledJson, sha256(compiledJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("世界包规范化序列化失败", exception);
        }
    }

    private String write(Object value) throws JsonProcessingException {
        JsonNode normalized = normalize(mapper.valueToTree(value));
        return mapper.writeValueAsString(normalized);
    }

    private JsonNode normalize(JsonNode value) {
        if (value == null || value.isNull()) return JsonNodeFactory.instance.nullNode();
        if (value.isTextual()) {
            return JsonNodeFactory.instance.textNode(Normalizer.normalize(value.textValue(), Normalizer.Form.NFC));
        }
        if (value.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> array.add(normalize(item)));
            return array;
        }
        if (value.isObject()) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            List<String> names = new java.util.ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> object.set(name, normalize(value.get(name))));
            return object;
        }
        return value.deepCopy();
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    record CanonicalResult(
            String canonicalJson,
            String contentHash,
            String compiledJson,
            String compiledHash
    ) {}

    /** 内容哈希故意排除发布者、包 ID、修订号和 Bundle 文件布局。 */
    private record ContentPayload(
            ContentContract contract,
            WorldPackageDocument.ContentMetadata metadata,
            WorldPackageDocument.WorldContent world,
            WorldPackageDocument.CreationContent creation,
            WorldPackageDocument.ProgressionContent progression,
            List<WorldPackageDocument.LoreEntry> loreEntries,
            List<WorldPackageDocument.AssetDescriptor> assets
    ) {}

    private record ContentContract(
            int formatVersion,
            String runtimeContract,
            List<String> requiredCapabilities
    ) {}
}
