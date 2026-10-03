package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 使用中性模型目录读取验证快照，并向验证历史追加一条不可变结果。 */
@Repository
public class JdbcModelValidationRepository implements ModelValidationRepository {
    private final JdbcTemplate jdbc;

    public JdbcModelValidationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public java.util.Optional<Target> findTarget(String providerCode, String modelCode) {
        return jdbc.query("""
                select p.provider_code, m.model_code, p.base_url, p.adapter_type, m.api_model_name,
                       m.capability_codes, m.context_window, m.maximum_output_tokens,
                       p.config_version, m.config_version, p.platform_secret_ciphertext
                from ai_provider_config p
                join ai_provider_model m on m.provider_code=p.provider_code
                where p.provider_code=? and m.model_code=?
                """, (row, index) -> new Target(new ModelDefinition(row.getString(1), row.getString(2),
                row.getString(3), ModelAdapterType.valueOf(row.getString(4).strip().toUpperCase(Locale.ROOT)),
                row.getString(5), capabilities(row.getString(6)), row.getInt(7), row.getInt(8)),
                row.getLong(9), row.getLong(10), row.getString(11)), normalize(providerCode), modelCode)
                .stream().findFirst();
    }

    @Override
    public void insertRun(ValidationRun run) {
        jdbc.update("""
                insert into ai_model_validation_run(id,provider_code,model_code,provider_config_version,
                    model_config_version,status,capability_codes,latency_ms,error_code,error_summary,
                    checked_by,checked_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, run.id(), run.providerCode(), run.modelCode(), run.providerConfigVersion(),
                run.modelConfigVersion(), run.status(), run.capabilityCodes(), run.latencyMs(), run.errorCode(),
                run.errorSummary(), run.checkedBy(), Timestamp.from(run.checkedAt()));
    }

    private Set<ModelCapability> capabilities(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(",")).map(String::strip).filter(value -> !value.isBlank())
                .map(value -> ModelCapability.valueOf(value.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
