"""创角候选协议；候选只能经玩家选择进入草稿，不能写命卷、物品或已发生事件。"""

from typing import Annotated, Literal

from pydantic import Field, StringConstraints, model_validator

from .domain import StrictModel

Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=2, max_length=6)]
DraftText = Annotated[str, StringConstraints(strip_whitespace=True, max_length=240)]
SuggestionId = Annotated[str, StringConstraints(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")]


class SuggestionInput(StrictModel):
    """同一请求固定资料；姓名不依赖远端场景，来历候选必须绑定已预览的世界。"""

    request_id: SuggestionId
    kind: Literal["names", "paths"]
    name: Annotated[str, StringConstraints(max_length=6)] = ""
    gender: Literal["未设定", "男", "女"] = "未设定"
    goal: DraftText = ""
    origin: DraftText = ""
    story_preference: DraftText = ""
    exclude_names: list[Name] = Field(default_factory=list, max_length=16)
    world_setting_hash: Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")] | None = None

    @model_validator(mode="after")
    def path_identity(self):
        if self.kind == "paths" and (len(self.name.strip()) < 2 or not self.world_setting_hash):
            raise ValueError("来历候选需要姓名及已确认的开篇设定")
        return self


class NameSuggestions(StrictModel):
    names: list[Name] = Field(min_length=4, max_length=4)

    @model_validator(mode="after")
    def distinct(self):
        if len(set(self.names)) != len(self.names):
            raise ValueError("姓名候选不能重复")
        return self


class PathSuggestion(StrictModel):
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=2, max_length=24)]
    origin: DraftText = Field(min_length=1)
    goal: DraftText = Field(min_length=1)


class PathSuggestions(StrictModel):
    paths: list[PathSuggestion] = Field(min_length=3, max_length=3)

    @model_validator(mode="after")
    def distinct(self):
        if len({(p.origin, p.goal) for p in self.paths}) != len(self.paths):
            raise ValueError("来历候选必须有所区别")
        return self
