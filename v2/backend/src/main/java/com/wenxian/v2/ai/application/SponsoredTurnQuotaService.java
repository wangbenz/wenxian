package com.wenxian.v2.ai.application;

import com.wenxian.v2.api.V2ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SponsoredTurnQuotaService {
    private static final String LIMIT_KEY = "account_public_turn_limit";
    private static final String MESSAGE_KEY = "account_public_turn_limit_message";
    private static final String CONTACT_KEY = "registration_contact_text";
    private static final String DEFAULT_MESSAGE = "本账号获赠的推演回合已经用完。你可以在卷外设置中接入自己的推演密钥继续游玩。";

    private final JdbcTemplate jdbc;

    public SponsoredTurnQuotaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Quota quota(String userId) {
        int limit = integerConfig(LIMIT_KEY, 20, 1, 1000);
        int legacyUsed = number("select coalesce(sum(used_count),0) from user_daily_quota where user_id=?", userId);
        int v2Used = number("""
                select count(*) from v2_turn_operation o
                where o.user_id=? and o.operation_status='SUCCEEDED'
                  and exists (select 1 from v2_ai_usage_log a
                    where a.operation_id=o.id and a.credential_source='PLATFORM' and a.result_status='SUCCESS')
                """, userId);
        int used = legacyUsed + v2Used;
        boolean playerOwned = usesPlayerCredential(userId);
        return new Quota(limit, used, Math.max(0, limit - used), playerOwned,
                config(MESSAGE_KEY, DEFAULT_MESSAGE), config(CONTACT_KEY, ""));
    }

    public void assertAvailable(String userId) {
        Quota quota = quota(userId);
        if (quota.playerOwnedCredential() || quota.remainingTurns() > 0) return;
        throw new V2ApiException(HttpStatus.TOO_MANY_REQUESTS, "PUBLIC_TURN_LIMIT_REACHED", "BUSINESS",
                quota.exhaustedMessage(), List.of("在卷外设置中接入自己的推演密钥",
                quota.contactText().isBlank() ? "稍后再试" : quota.contactText()));
    }

    private boolean usesPlayerCredential(String userId) {
        if (!Boolean.parseBoolean(config("custom_api_enabled", "true"))) return false;
        Integer count = jdbc.queryForObject("""
                select count(*) from user_ai_preference p
                join user_ai_provider_credential c on c.user_id=p.user_id and c.provider_code=p.provider_code
                join ai_provider_config provider on provider.provider_code=p.provider_code and provider.active=true
                join ai_model_catalog model on model.model_code=p.model_code and model.active=true
                where p.user_id=? and p.credential_source='USER' and c.credential_status='ACTIVE'
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    private int number(String sql, String userId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId);
        return value == null ? 0 : value;
    }

    private int integerConfig(String key, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(config(key, String.valueOf(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String config(String key, String fallback) {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(fallback);
    }

    public record Quota(int turnLimit, int usedTurns, int remainingTurns, boolean playerOwnedCredential,
                        String exhaustedMessage, String contactText) {
    }
}
