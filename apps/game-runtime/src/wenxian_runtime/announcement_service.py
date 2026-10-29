"""公告应用服务：只显示已到发布时间的服务器配置，回执不影响游戏状态。"""

import time
from datetime import datetime, timezone

from .announcement_repository import AnnouncementRepository
from .domain import RuntimeProblem


class AnnouncementService:
    """配置不可用时明确失败；不存在的公告不能被伪造已读。"""

    def __init__(self, repository, config, clock=None):
        self.repo, self.config, self.clock = AnnouncementRepository(repository), config, clock or time.time

    def _published(self):
        if self.config.unavailable:
            raise RuntimeProblem("ANNOUNCEMENT_UNAVAILABLE", "公告暂时无法读取，仍可继续查看命卷。", 503)
        return self.config.published(datetime.fromtimestamp(self.clock(), timezone.utc))

    def latest(self):
        rows = self._published()
        return rows[0].view() if rows else None

    def published(self, account_id):
        receipts = self.repo.receipts(account_id)
        return [row.view((row.id, row.digest()) not in receipts) for row in self._published()]

    def pending(self, account_id):
        return next((row for row in self.published(account_id) if row["unread"]), None)

    def mark_read(self, account_id, identifier):
        row = next((item for item in self._published() if item.id == identifier), None)
        if not row:
            raise RuntimeProblem("NOT_FOUND", "没有找到这条公告。", 404)
        self.repo.mark_read(account_id, identifier, row.digest(), int(self.clock()))
