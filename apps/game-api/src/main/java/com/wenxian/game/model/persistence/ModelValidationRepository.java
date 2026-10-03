package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelDefinition;

import java.time.Instant;
import java.util.Optional;

/**
 * 模型受控验证的配置快照与结果写入边界。
 *
 * <p>验证开始时读取供应商和模型配置版本；验证结束后只追加脱敏结果。配置在验证期间变化时，
 * 新记录因版本不匹配自动成为历史证据，不会错误放行新配置。</p>
 */
public interface ModelValidationRepository {
    Optional<Target> findTarget(String providerCode, String modelCode);

    void insertRun(ValidationRun run);

    record Target(ModelDefinition model, long providerConfigVersion, long modelConfigVersion,
                  String platformSecretCiphertext) { }

    record ValidationRun(String id, String providerCode, String modelCode,
                         long providerConfigVersion, long modelConfigVersion,
                         String status, String capabilityCodes, Integer latencyMs,
                         String errorCode, String errorSummary, String checkedBy, Instant checkedAt) { }
}
