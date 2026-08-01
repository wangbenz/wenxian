package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Owns wallet compatibility and arithmetic. Narrative output may propose ledger entries,
 * but it never writes a final balance.
 */
public final class CurrencyAuthority {
    public static final int ECONOMY_VERSION = 1;
    public static final String COPPER_WEN = "COPPER_WEN";
    public static final String SPIRIT_STONE_LOW = "SPIRIT_STONE_LOW";
    public static final String SPIRIT_STONE_MIDDLE = "SPIRIT_STONE_MIDDLE";
    public static final String SPIRIT_STONE_HIGH = "SPIRIT_STONE_HIGH";
    private static final long MAX_BALANCE = 999_999_999L;
    private static final long MAX_CHANGE = 99_999_999L;
    private static final Set<String> CURRENCIES = Set.of(
            COPPER_WEN, SPIRIT_STONE_LOW, SPIRIT_STONE_MIDDLE, SPIRIT_STONE_HIGH);

    private CurrencyAuthority() {}

    public static boolean repairLegacyState(GameState state) {
        if (state == null) return false;
        boolean changed = false;
        if (state.economyVersion < ECONOMY_VERSION || state.wallet == null) {
            long legacyCopper = state.player == null ? 0 : Math.max(0, state.player.silver);
            state.wallet = new GameState.Wallet(legacyCopper, 0, 0, 0);
            state.economyVersion = ECONOMY_VERSION;
            changed = true;
        }
        if (state.inventory == null) {
            state.inventory = new ArrayList<>();
            changed = true;
        }
        if (state.inventory.removeIf(CurrencyAuthority::legacyCurrencyItem)) changed = true;
        if (state.player != null) {
            int mirror = (int) Math.min(Integer.MAX_VALUE, Math.max(0, state.wallet.copperWen));
            if (state.player.silver != mirror) {
                state.player.silver = mirror;
                changed = true;
            }
        }
        return changed;
    }

    public static List<AgentResult.CurrencyChange> validate(
            List<AgentResult.CurrencyChange> requested, GameState state) {
        repairLegacyState(state);
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.size() > 6) throw new CurrencyValidationException("CURRENCY_CHANGE_LIMIT", "单轮货币流水过多");
        List<AgentResult.CurrencyChange> safe = new ArrayList<>();
        for (AgentResult.CurrencyChange change : requested) {
            if (change == null || !CURRENCIES.contains(change.currency())) {
                throw new CurrencyValidationException("CURRENCY_UNKNOWN", "存在未知货币种类");
            }
            if (change.amount() == 0 || change.amount() == Long.MIN_VALUE || Math.abs(change.amount()) > MAX_CHANGE) {
                throw new CurrencyValidationException("CURRENCY_AMOUNT_INVALID", "货币流水金额不合法");
            }
            String reason = change.reason() == null ? "" : change.reason().strip();
            if (reason.isBlank() || reason.length() > 160) {
                throw new CurrencyValidationException("CURRENCY_REASON_REQUIRED", "货币流水必须说明来源或用途");
            }
            safe.add(new AgentResult.CurrencyChange(change.currency(), change.amount(), reason));
        }
        ensureBalances(state.wallet, safe);
        return List.copyOf(safe);
    }

    public static void apply(GameState state, List<AgentResult.CurrencyChange> changes) {
        repairLegacyState(state);
        List<AgentResult.CurrencyChange> safe = changes == null ? List.of() : changes;
        ensureBalances(state.wallet, safe);
        for (AgentResult.CurrencyChange change : safe) {
            switch (change.currency()) {
                case COPPER_WEN -> state.wallet.copperWen += change.amount();
                case SPIRIT_STONE_LOW -> state.wallet.lowSpiritStones += change.amount();
                case SPIRIT_STONE_MIDDLE -> state.wallet.middleSpiritStones += change.amount();
                case SPIRIT_STONE_HIGH -> state.wallet.highSpiritStones += change.amount();
                default -> throw new CurrencyValidationException("CURRENCY_UNKNOWN", "存在未知货币种类");
            }
        }
        if (state.player != null) {
            state.player.silver = (int) Math.min(Integer.MAX_VALUE, state.wallet.copperWen);
        }
    }

    public static boolean isCurrencyInventoryChange(AgentResult.InventoryChange change) {
        return change != null && ("CURRENCY".equals(change.category())
                || "copper-coin".equals(change.definitionId())
                || change.definitionId() != null && change.definitionId().startsWith("spirit-stone-"));
    }

    private static boolean legacyCurrencyItem(GameState.InventoryItem item) {
        return item != null && ("CURRENCY".equals(item.category)
                || "copper-coin".equals(item.definitionId)
                || item.definitionId != null && item.definitionId.startsWith("spirit-stone-"));
    }

    private static void ensureBalances(GameState.Wallet wallet, List<AgentResult.CurrencyChange> changes) {
        long copper = wallet.copperWen;
        long low = wallet.lowSpiritStones;
        long middle = wallet.middleSpiritStones;
        long high = wallet.highSpiritStones;
        for (AgentResult.CurrencyChange change : changes) {
            switch (change.currency()) {
                case COPPER_WEN -> copper = add(copper, change.amount());
                case SPIRIT_STONE_LOW -> low = add(low, change.amount());
                case SPIRIT_STONE_MIDDLE -> middle = add(middle, change.amount());
                case SPIRIT_STONE_HIGH -> high = add(high, change.amount());
                default -> throw new CurrencyValidationException("CURRENCY_UNKNOWN", "存在未知货币种类");
            }
        }
        if (copper < 0 || low < 0 || middle < 0 || high < 0) {
            throw new CurrencyValidationException("CURRENCY_INSUFFICIENT", "交易支出超过当前持有货币");
        }
    }

    private static long add(long balance, long amount) {
        try {
            long result = Math.addExact(balance, amount);
            if (result > MAX_BALANCE) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException error) {
            throw new CurrencyValidationException("CURRENCY_BALANCE_OVERFLOW", "货币余额超出可记录范围");
        }
    }

    public static final class CurrencyValidationException extends IllegalArgumentException {
        private final String code;

        public CurrencyValidationException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
