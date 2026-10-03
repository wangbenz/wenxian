package com.wenxian.game.credit;

import com.wenxian.game.credit.TurnCreditModels.Balance;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台赠送回合的查询与兼容扣减边界。
 *
 * <p>新回合账户开启时，以赠送、预占和流水账本为权威；关闭时才读取旧的每日计数。
 * 本服务不负责模型选择、玩家密钥或故事状态，额度不足只抛出稳定业务异常。</p>
 */
@Service
public class PlatformTurnQuotaService {
    private static final String ACCOUNT_LIMIT_KEY = "account_public_turn_limit";
    private static final String ACCOUNT_LIMIT_MESSAGE_KEY = "account_public_turn_limit_message";
    private static final String CUSTOM_API_KEY = "custom_api_enabled";
    private static final String CONTACT_TEXT_KEY = "registration_contact_text";
    private static final String DEFAULT_LIMIT_MESSAGE = "本账号的推演点不足以完成所选档位。你可以切换更轻档位，或前往「设置」接入自己的推演密钥继续游玩。";
    private static final String DEFAULT_CONTACT_TEXT = "如需参与测试或反馈问题，请加入《问仙录》二群：532549329。";
    private static final Pattern SUPPORT_GROUP_PATTERN = Pattern.compile("(?<!\\d)(\\d{5,12})(?!\\d)");
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final TurnCreditService turnCredits;
    private final boolean turnCreditEnabled;

    @Autowired
    public PlatformTurnQuotaService(JdbcTemplate jdbc, TurnCreditService turnCredits,
                                    @Value("${wenxian.turn-credit.enabled:false}") boolean turnCreditEnabled) {
        this.jdbc = jdbc;
        this.turnCredits = turnCredits;
        this.turnCreditEnabled = turnCreditEnabled;
    }

    /** 供不启用新回合账本的轻量测试使用。 */
    public PlatformTurnQuotaService(JdbcTemplate jdbc) {
        this(jdbc, null, false);
    }

    @PostConstruct
    public void ensureDefaults() {
        ensureConfig(ACCOUNT_LIMIT_KEY, "20");
        ensureConfig(ACCOUNT_LIMIT_MESSAGE_KEY, DEFAULT_LIMIT_MESSAGE);
        ensureConfig(CUSTOM_API_KEY, "true");
    }

    /** 返回 H5 所需的账号级平台回合余额，不混入模型或凭据状态。 */
    public Settings settings(String userId) {
        LegacyQuota legacy = legacyQuota(userId);
        int used = legacy.usedTurns();
        int limit = legacy.turnLimit();
        int remaining = legacy.remainingTurns();
        if (turnCreditEnabled && turnCredits != null) {
            turnCredits.ensureLegacyOpening(userId, legacy.turnLimit(), legacy.usedTurns());
            Balance balance = turnCredits.balance(userId);
            limit = balance.granted();
            used = balance.consumed();
            remaining = balance.available();
        }
        return new Settings(limit, used, remaining, customApiEnabled());
    }

    /** 平台模型调用前的旧额度校验；新账本模式由预占动作完成最终并发保护。 */
    public void assertPlatformQuota(String userId) {
        if (settings(userId).remainingTurns() <= 0) throwPublicTurnLimit();
    }

    /** 使用统一玩家提示抛出额度耗尽异常。 */
    public void throwPublicTurnLimit() {
        String contactText = configValue(CONTACT_TEXT_KEY, DEFAULT_CONTACT_TEXT);
        throw new PublicTurnLimitException(
                configValue(ACCOUNT_LIMIT_MESSAGE_KEY, DEFAULT_LIMIT_MESSAGE),
                accountLimit(), contactText, supportGroup(contactText));
    }

    /** 新账本关闭期间，在成功提交故事回合后记录一次旧计数。 */
    @Transactional
    public void recordSuccessfulPlatformInvocation(String userId) {
        LocalDate today = LocalDate.now(QUOTA_ZONE);
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update("update user_daily_quota set used_count=used_count+1, updated_at=? where user_id=? and usage_date=?",
                now, userId, Date.valueOf(today));
        if (updated != 0) return;
        try {
            jdbc.update("insert into user_daily_quota(user_id, usage_date, used_count, updated_at) values (?, ?, 1, ?)",
                    userId, Date.valueOf(today), now);
        } catch (DuplicateKeyException race) {
            jdbc.update("update user_daily_quota set used_count=used_count+1, updated_at=? where user_id=? and usage_date=?",
                    now, userId, Date.valueOf(today));
        }
    }

    public int accountLimit() {
        try {
            return Math.max(1, Math.min(1000,
                    Integer.parseInt(configValue(ACCOUNT_LIMIT_KEY, "20"))));
        } catch (NumberFormatException ignored) {
            return 20;
        }
    }

    /** 仅用于建立新回合账户的期初余额；之后以新账本为准。 */
    public LegacyQuota legacyQuota(String userId) {
        int used = usedTurns(userId);
        int limit = accountLimit();
        return new LegacyQuota(limit, used, Math.max(0, limit - used));
    }

    public boolean customApiEnabled() {
        return Boolean.parseBoolean(configValue(CUSTOM_API_KEY, "true"));
    }

    private int usedTurns(String userId) {
        Integer used = jdbc.queryForObject(
                "select coalesce(sum(used_count), 0) from user_daily_quota where user_id=?",
                Integer.class, userId);
        return used == null ? 0 : used;
    }

    private String supportGroup(String contactText) {
        Matcher matcher = SUPPORT_GROUP_PATTERN.matcher(contactText == null ? "" : contactText);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void ensureConfig(String key, String value) {
        Integer count = jdbc.queryForObject("select count(*) from gameplay_config where config_key=?", Integer.class, key);
        if (count != null && count == 0) {
            jdbc.update("insert into gameplay_config(config_key, config_value, updated_at) values (?, ?, ?)",
                    key, value, Timestamp.from(Instant.now()));
        }
    }

    private String configValue(String key, String fallback) {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString("config_value"), key).stream().findFirst().orElse(fallback);
    }

    public record Settings(int turnLimit, int usedTurns, int remainingTurns, boolean customApiEnabled) {}

    public record LegacyQuota(int turnLimit, int usedTurns, int remainingTurns) {}
}
