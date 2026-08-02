package com.wenxian.v2.ai.persistence;

import com.wenxian.v2.api.V2ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiModelRouteRepository {
    private final JdbcTemplate jdbc;

    public AiModelRouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ModelRoute require(String taskType) {
        return jdbc.query("""
                        select r.task_type,r.provider_code,r.primary_model_code,p.official_base_url
                        from ai_task_route r
                        join ai_provider_config p on p.provider_code=r.provider_code and p.active=true
                        join ai_model_catalog m on m.model_code=r.primary_model_code and m.active=true
                        where r.task_type=?
                        """, (row, index) -> new ModelRoute(row.getString("task_type"),
                        row.getString("provider_code"), row.getString("primary_model_code"),
                        row.getString("official_base_url")), taskType)
                .stream().findFirst()
                .orElseThrow(() -> V2ApiException.conflict("AI_ROUTE_UNAVAILABLE",
                        "当前推演任务没有可用模型。", "稍后重试或联系管理员"));
    }

    public record ModelRoute(String taskType, String providerCode, String modelCode, String baseUrl) {
    }
}
