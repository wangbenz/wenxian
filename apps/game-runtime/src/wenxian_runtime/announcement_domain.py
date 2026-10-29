"""只读公告合同；坏配置只影响公告，不阻断账号或命卷。"""

import hashlib
import logging
import os
from datetime import datetime
from pathlib import Path

from pydantic import AwareDatetime, Field, model_validator

from .domain import Key, StrictModel


class Announcement(StrictModel):
    id: Key
    version: str = Field(min_length=1, max_length=40)
    title: str = Field(min_length=1, max_length=120)
    content: str = Field(min_length=1, max_length=20000)
    publishedAt: AwareDatetime

    def digest(self):
        return hashlib.sha256(self.model_dump_json().encode()).hexdigest()

    def view(self, unread=False):
        return {**self.model_dump(mode="json"), "unread": unread}


class AnnouncementConfig(StrictModel):
    announcements: list[Announcement] = Field(default_factory=list, max_length=100)
    unavailable: bool = Field(default=False, exclude=True)

    @model_validator(mode="after")
    def unique_ids(self):
        if len({row.id for row in self.announcements}) != len(self.announcements):
            raise ValueError("公告编号不能重复")
        return self

    def published(self, current: datetime):
        return sorted((row for row in self.announcements if row.publishedAt <= current),
                      key=lambda row: (row.publishedAt, row.id), reverse=True)


def read_announcement_config():
    source = os.getenv("WENXIAN_RUNTIME_ANNOUNCEMENTS")
    if not source:
        return AnnouncementConfig()
    try:
        return AnnouncementConfig.model_validate_json(Path(source).read_text())
    except (OSError, ValueError):
        logging.getLogger(__name__).warning("公告配置不可用，账号与命卷功能继续可用。")
        return AnnouncementConfig(unavailable=True)
