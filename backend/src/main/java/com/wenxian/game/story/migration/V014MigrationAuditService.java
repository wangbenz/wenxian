package com.wenxian.game.story.migration;

import com.wenxian.game.story.migration.V014MigrationAuditModels.AuditItem;
import com.wenxian.game.story.migration.V014MigrationAuditModels.AuditReport;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewReport;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * V0.14 源命卷的全量只读体检器。它使用稳定 ID 游标逐页调用同一套正式预演逻辑，
 * 汇总迁移状态、问题码和复杂度，但不读取邮箱、不输出正文，也不调用任何写仓储。
 */
public class V014MigrationAuditService {
    private static final int PAGE_SIZE = 50;
    private static final int COMPLEX_SAVE_LIMIT = 20;

    private final V014MigrationSourceReader sources;
    private final V014MigrationPreviewService previews;
    private final Clock clock;

    public V014MigrationAuditService(V014MigrationSourceReader sources,
                                     V014MigrationPreviewService previews) {
        this(sources, previews, Clock.systemUTC());
    }

    V014MigrationAuditService(V014MigrationSourceReader sources,
                              V014MigrationPreviewService previews,
                              Clock clock) {
        this.sources = sources;
        this.previews = previews;
        this.clock = clock;
    }

    /** 扫描当前只读事务可见的全部源命卷，并返回可序列化的脱敏报告。 */
    public AuditReport auditAll() {
        long started = System.nanoTime();
        long sourceTotal = sources.countAll();
        List<AuditItem> items = new ArrayList<>();
        String cursor = "";
        while (true) {
            List<String> ids = sources.listSaveIdsAfter(cursor, PAGE_SIZE);
            if (ids.isEmpty()) break;
            for (String id : ids) items.add(auditOne(id));
            String nextCursor = ids.getLast();
            if (nextCursor.equals(cursor)) {
                throw new IllegalStateException("全量体检游标没有前进，已终止以避免重复扫描");
            }
            cursor = nextCursor;
            if (ids.size() < PAGE_SIZE) break;
        }

        Map<String, Long> statusCounts = count(items.stream().map(AuditItem::status).toList());
        List<String> issueCodes = items.stream().flatMap(item -> item.issueCodes().stream()).toList();
        Map<String, Long> issueCounts = count(issueCodes);
        long totalTurns = items.stream().mapToLong(AuditItem::turns).sum();
        long totalEntities = items.stream().mapToLong(AuditItem::entities).sum();
        long totalMemories = items.stream().mapToLong(AuditItem::memories).sum();
        int maxTurns = items.stream().mapToInt(AuditItem::turns).max().orElse(0);
        int maxEntities = items.stream().mapToInt(AuditItem::entities).max().orElse(0);
        List<AuditItem> complex = items.stream()
                .sorted(Comparator.comparingInt(AuditItem::entities).reversed()
                        .thenComparing(Comparator.comparingInt(AuditItem::turns).reversed())
                        .thenComparing(AuditItem::sourceSaveId))
                .limit(COMPLEX_SAVE_LIMIT).toList();
        long durationMillis = (System.nanoTime() - started) / 1_000_000;
        return new AuditReport(Instant.now(clock), sourceTotal, items.size(), statusCounts, issueCounts,
                totalTurns, totalEntities, totalMemories, maxTurns, maxEntities, durationMillis,
                complex, List.copyOf(items));
    }

    private AuditItem auditOne(String sourceSaveId) {
        try {
            PreviewReport report = previews.preview(sourceSaveId);
            int entities = report.target() == null ? 0 : report.target().entities().size();
            List<String> issueCodes = report.issues().stream()
                    .map(V014MigrationPreviewModels.MigrationIssue::code).distinct().sorted().toList();
            return new AuditItem(sourceSaveId, report.status().name(), report.sourceTurnCount(), entities,
                    report.memoryCount(), issueCodes, report.beforeHash(), report.afterHash(), null);
        } catch (RuntimeException error) {
            return new AuditItem(sourceSaveId, "ERROR", 0, 0, 0, List.of("PREVIEW_EXCEPTION"),
                    null, null, "预演异常：" + error.getClass().getSimpleName());
        }
    }

    private Map<String, Long> count(List<String> values) {
        Map<String, Long> counts = new TreeMap<>();
        for (String value : values) counts.merge(value, 1L, Long::sum);
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
