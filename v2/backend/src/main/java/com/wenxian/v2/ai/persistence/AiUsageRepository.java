package com.wenxian.v2.ai.persistence;

import com.wenxian.v2.ai.domain.ModelContracts.ModelRequest;
import com.wenxian.v2.ai.domain.ModelContracts.ModelResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class AiUsageRepository {
    private final JdbcTemplate jdbc;

    public AiUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void success(ModelRequest request, ModelResult result) {
        success(request, result, "PLATFORM");
    }

    public void success(ModelRequest request, ModelResult result, String credentialSource) {
        insert(request, result.providerCode(), result.modelCode(), result.providerRequestId(),
                result.promptTokens(), result.completionTokens(), result.latencyMillis(), "SUCCESS", null,
                credentialSource);
    }

    public void failure(ModelRequest request, String provider, String model, long latencyMillis, String errorCode) {
        failure(request, provider, model, latencyMillis, errorCode, "PLATFORM");
    }

    public void failure(ModelRequest request, String provider, String model, long latencyMillis,
                        String errorCode, String credentialSource) {
        insert(request, provider, model, null, 0, 0, latencyMillis, "FAILED", errorCode, credentialSource);
    }

    private void insert(ModelRequest request, String provider, String model, String providerRequestId,
                        int promptTokens, int completionTokens, long latencyMillis, String status, String errorCode,
                        String credentialSource) {
        try {
            jdbc.update("""
                    insert into v2_ai_usage_log(id,user_id,save_id,operation_id,task_type,provider_code,
                                                model_code,credential_source,prompt_tokens,completion_tokens,
                                                latency_millis,result_status,provider_request_id,error_code,created_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, "v2-usage-" + UUID.randomUUID(), request.userId(), request.saveId(), request.operationId(),
                    request.taskType(), provider, model, credentialSource, promptTokens, completionTokens, latencyMillis, status,
                    providerRequestId, errorCode, Timestamp.from(Instant.now()));
        } catch (RuntimeException ignored) {
            // Accounting must never replace the player-facing model result.
        }
    }
}
