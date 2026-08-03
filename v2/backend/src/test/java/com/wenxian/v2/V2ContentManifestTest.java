package com.wenxian.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class V2ContentManifestTest {
    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void publishedQingqiManifestMatchesTheSeededContentPackage() throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/content/qingqizhou-0.2.0.json")) {
            assertThat(source).isNotNull();
            JsonNode manifest = json.readTree(source);
            assertThat(manifest.path("contentVersion").asText()).isEqualTo("qingqi-0.2.0");
            assertThat(codes(manifest, "locationCodes")).containsExactlyInAnyOrderElementsOf(databaseCodes(
                    "select location_code from v2_location_definition"));
            assertThat(codes(manifest, "currencyCodes")).containsExactlyInAnyOrderElementsOf(databaseCodes(
                    "select currency_code from v2_currency_definition"));
            assertThat(codes(manifest, "itemCodes")).containsExactlyInAnyOrderElementsOf(databaseCodes(
                    "select item_code from v2_item_definition"));
            assertThat(codes(manifest, "abilityCodes")).containsExactlyInAnyOrderElementsOf(databaseCodes(
                    "select ability_code from v2_ability_definition"));
            assertThat(codes(manifest, "eventTemplateCodes")).containsExactlyInAnyOrderElementsOf(databaseCodes(
                    "select template_code from v2_event_template"));
        }
    }

    private List<String> codes(JsonNode manifest, String field) {
        List<String> result = new ArrayList<>();
        manifest.path(field).forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private List<String> databaseCodes(String sql) {
        return jdbc.query(sql, (row, index) -> row.getString(1));
    }
}
