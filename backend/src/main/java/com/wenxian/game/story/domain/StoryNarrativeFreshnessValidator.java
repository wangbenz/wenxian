package com.wenxian.game.story.domain;

import java.util.HashSet;
import java.util.Set;

/**
 * 校验新回合正文是否真正产生了新内容。
 *
 * <p>本类只比较文本新鲜度，不理解修炼、物品、人物等业务语义。逐字相同或长正文的五字片段高度重合时，
 * 候选会被退回一次语义重写；它不能代替独立模型对“是否执行玩家行动”的语义审阅。</p>
 */
public final class StoryNarrativeFreshnessValidator {
    static final int MIN_SIMILARITY_LENGTH = 240;
    static final int MIN_REPEATED_PREFIX_LENGTH = 60;
    static final int NGRAM_SIZE = 5;
    static final double MAX_CONTAINMENT = 0.90d;

    public void validate(String previous, String candidate) {
        String before = normalize(previous);
        String after = normalize(candidate);
        if (before.isEmpty() || after.isEmpty()) return;
        if (before.equals(after)) {
            throw new StoryNarrativeFreshnessException("本轮正文与上一回合完全重复，说明尚未处理玩家本轮行动；请根据当前 action 重新生成本回合标题、正文、时间和选项");
        }
        if (commonPrefixLength(before, after) >= MIN_REPEATED_PREFIX_LENGTH) {
            throw new StoryNarrativeFreshnessException("本轮正文开头重复执行了上一回合内容，说明尚未从玩家本轮行动开始；请直接从当前 action 生成，不要重演上一回合动作");
        }
        if (Math.min(before.length(), after.length()) < MIN_SIMILARITY_LENGTH) return;
        if (containment(before, after, NGRAM_SIZE) >= MAX_CONTAINMENT) {
            throw new StoryNarrativeFreshnessException("本轮正文与上一回合高度重复，说明只改写了少量措辞但没有真正处理玩家行动；请根据当前 action 重新生成本回合正文和状态变化");
        }
    }

    private int commonPrefixLength(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) index++;
        return index;
    }

    double containment(String left, String right, int size) {
        Set<String> first = grams(left, size);
        Set<String> second = grams(right, size);
        if (first.isEmpty() || second.isEmpty()) return 0;
        Set<String> smaller = first.size() <= second.size() ? first : second;
        Set<String> larger = first.size() <= second.size() ? second : first;
        long shared = smaller.stream().filter(larger::contains).count();
        return shared / (double) smaller.size();
    }

    private Set<String> grams(String value, int size) {
        Set<String> values = new HashSet<>();
        for (int index = 0; index <= value.length() - size; index++) {
            values.add(value.substring(index, index + size));
        }
        return values;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").strip();
    }
}
