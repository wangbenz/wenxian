"""资料设定应用服务：准备可确认的开局、验证本局快照；不写公共资料或调用模型。"""

from pathlib import Path

from .domain import RuntimeProblem
from .library import LibraryReader
from .repository import ROOT
from .world_domain import WorldProfile, WorldSetting


class WorldSettingService:
    """远程读取只发生在准备新卷时；已绑定/已创建的卷读取本地固定快照。"""

    def __init__(self, repository, library_url, transport=None, profile_path=None):
        self.repo, self.library_url, self.transport = repository, library_url, transport
        self.profile_path = Path(profile_path or ROOT / "resources/world-profile.json")

    def current(self) -> WorldSetting:
        try:
            profile = WorldProfile.model_validate_json(self.profile_path.read_text())
        except (OSError, ValueError):
            raise RuntimeProblem("WORLD_SETTING_UNAVAILABLE", "当前开篇设定暂时无法读取，填写资料已保留。", 503) from None
        reader = LibraryReader(self.library_url, transport=self.transport)
        try:
            for ref in profile.references():
                reader.get(ref.category, ref.key, revision=getattr(ref, "revision", None))
            return WorldSetting(schema_version="library-setting-2" if profile.adopted_definitions is not None
                                else "library-setting-1", profile=profile, definitions=reader.reads)
        except ValueError:
            raise RuntimeProblem("WORLD_SETTING_UNAVAILABLE", "当前开篇资料尚未确认，请稍后重试。", 503) from None
        finally:
            reader.close()

    def preview(self):
        setting = self.current()
        state = setting.opening_state()
        return {
            "opening_id": setting.profile.opening.id, "title": state.scene_title,
            "narrative": state.narrative, "realm": state.realm, "condition": state.condition,
            "location": state.location, "items": [item.name for item in state.items],
            "abilities": [ability.name for ability in state.abilities],
            "setting_hash": setting.digest(),
            "setting_summary": setting.profile.summary,
        }

    def prepare(self, character):
        if not character.world_setting_hash:
            raise RuntimeProblem("SETTING_CONFIRMATION_REQUIRED", "请先读取并确认当前开篇设定，填写资料已保留。")
        setting = self.current()
        if setting.digest() != character.world_setting_hash:
            raise RuntimeProblem("SETTING_CHANGED", "开篇采用的资料已有更新，请重新读取并确认，填写资料已保留。")
        return setting.opening_state(character), setting

    def saved(self, save_id):
        row = self.repo.world_setting(save_id)
        if row is None:
            return None
        try:
            value = WorldSetting.model_validate_json(row["setting_json"])
            if value.digest() != row["setting_hash"]:
                raise ValueError("本局设定散列不一致")
            return value
        except (ValueError, TypeError):
            raise RuntimeProblem("WORLD_SETTING_UNREADABLE", "这份命卷的设定暂时无法确认，原记录已保留。", 409) from None
