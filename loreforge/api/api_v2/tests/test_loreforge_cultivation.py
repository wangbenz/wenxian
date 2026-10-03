"""修仙试录的空值、稳定引用和无损迁移验证；只使用隔离测试库。"""

import copy
import json
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from contextlib import ExitStack

from django.apps import apps
from django.core.management import call_command
from django.core.management.base import CommandError
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.test import TestCase, TransactionTestCase, override_settings

from api_v2.models import Condition, Document, MagicItem, Spell
from api_v2.serializers.item import MagicItemSerializer
from api_v2.serializers.spell import SpellSerializer


DATA_ROOT = Path(__file__).resolve().parents[3] / "data"
PILOT_DOCUMENT = "loreforge-pilot"
PILOT_INVISIBLE = "loreforge_invisible"
PILOT_INVISIBILITY = "loreforge_invisibility"
PILOT_DETECTION = "loreforge_detect-spiritual-effects"
PILOT_WAND = "loreforge_detection-wand"


def read_records(directory):
    return [record for path in sorted((DATA_ROOT / directory).glob("*.json"))
            for record in json.loads(path.read_text(encoding="utf-8"))]


def load_directory(directory, registry=None):
    # 历史迁移验证使用当时的模型，不能用新增字段查询尚未升级的表。
    with ExitStack() as stack:
        if registry is not None:
            stack.enter_context(patch("api_v2.management.commands.load_loreforge.apps", registry))
            stack.enter_context(patch("django.core.serializers.python.apps", registry))
        call_command("load_loreforge", str(DATA_ROOT / directory), stdout=StringIO())


def source_snapshot(records, registry=apps):
    """比较来源中每条记录的全部标量和既有关系，包含英文正文。"""
    snapshot = {}
    for record in records:
        model = registry.get_model(record["model"])
        instance = model.objects.get(pk=record["pk"])
        values = {field.attname: getattr(instance, field.attname)
                  for field in model._meta.concrete_fields}
        for name in record["fields"]:
            field = model._meta.get_field(name)
            if field.many_to_many:
                values[name] = sorted(getattr(instance, name).values_list("pk", flat=True))
        snapshot[(record["model"], record["pk"])] = values
    return snapshot


class LoreforgeCultivationTests(TestCase):
    def setUp(self):
        self.original = read_records("starter")
        self.pilot = read_records("cultivation")
        load_directory("starter")
        self.original_snapshot = source_snapshot(self.original)
        # 反向排列内容，确保器物→法术、法术→状态均支持同批前向引用。
        self.load(list(reversed(self.pilot)))

    def load(self, records):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "content.json").write_text(
                json.dumps(records, ensure_ascii=False), encoding="utf-8")
            with override_settings(LOREFORGE_DATA_ROOT=root):
                call_command("load_loreforge", str(root), stdout=StringIO())

    def test_unknown_values_remain_null_in_database_and_read_api(self):
        null_spell_fields = ("level", "school", "casting_time", "attack_roll", "higher_level",
                             "saving_throw_ability", "damage_roll", "range", "range_unit")
        for key in (PILOT_INVISIBILITY, PILOT_DETECTION):
            spell = Spell.objects.get(pk=key)
            response = self.client.get(f"/v2/spells/{key}/")
            self.assertEqual(response.status_code, 200)
            for field in null_spell_fields:
                with self.subTest(key=key, field=field):
                    self.assertIsNone(getattr(spell, field))
                    self.assertIsNone(response.json()[field])
            self.assertTrue(response.json()["verbal"])
            self.assertTrue(response.json()["somatic"])
            self.assertFalse(response.json()["material"])
            self.assertEqual(response.json()["material_specified"], "")
            self.assertIsNone(spell.search_result_extra_fields()["school"])
        invisibility = self.client.get(f"/v2/spells/{PILOT_INVISIBILITY}/").json()
        self.assertIsNone(invisibility["ritual"])
        self.assertIsNone(invisibility["concentration"])
        detection = self.client.get(f"/v2/spells/{PILOT_DETECTION}/").json()
        self.assertFalse(detection["ritual"])
        self.assertFalse(detection["concentration"])

        wand = MagicItem.objects.get(pk=PILOT_WAND)
        for field in ("size", "weight", "armor_class", "armor_detail", "hit_points", "hit_dice",
                      "nonmagical_attack_immunity", "nonmagical_attack_resistance", "cost",
                      "requires_attunement", "attunement_detail", "rarity"):
            self.assertIsNone(getattr(wand, field), field)
        wand_body = self.client.get(f"/v2/magicitems/{PILOT_WAND}/").json()
        for field in ("size", "weight", "cost", "requires_attunement", "weight_unit", "rarity"):
            self.assertIsNone(wand_body[field], field)
        document = self.client.get(f"/v2/documents/{PILOT_DOCUMENT}/").json()
        self.assertIsNone(document["publication_date"])
        self.assertIsNone(document["permalink"])
        self.assertEqual(document["licenses"], [])

    def test_reference_summaries_are_read_only_and_do_not_recurse(self):
        spell = self.client.get(f"/v2/spells/{PILOT_INVISIBILITY}/").json()
        self.assertEqual(spell["applies_conditions"], [{"key": PILOT_INVISIBLE, "name": "隐身"}])
        wand = self.client.get(f"/v2/magicitems/{PILOT_WAND}/").json()
        self.assertEqual(wand["spells"], [{"key": PILOT_DETECTION, "name": "探灵术"}])
        self.assertTrue(SpellSerializer().fields["applies_conditions"].read_only)
        self.assertTrue(MagicItemSerializer().fields["spells"].read_only)
        # 不提供删除目标的 HTTP 通道，因此读取客户端无法令已声明的引用消失。
        for endpoint in (f"conditions/{PILOT_INVISIBLE}", f"spells/{PILOT_DETECTION}",
                         f"magicitems/{PILOT_WAND}", f"documents/{PILOT_DOCUMENT}"):
            for method in ("post", "put", "patch", "delete"):
                response = getattr(self.client, method)(
                    f"/v2/{endpoint}/", data="{}", content_type="application/json")
                self.assertEqual(response.status_code, 405, (endpoint, method))
        self.assertEqual(Spell.objects.get(pk=PILOT_INVISIBILITY).applies_conditions.get().pk, PILOT_INVISIBLE)
        self.assertEqual(MagicItem.objects.get(pk=PILOT_WAND).spells.get().pk, PILOT_DETECTION)

    def test_repeat_import_and_explicit_empty_relations(self):
        self.load(self.pilot)
        self.assertEqual(Spell.objects.filter(document=PILOT_DOCUMENT).count(), 2)
        cleared = copy.deepcopy(self.pilot)
        for record in cleared:
            for field in ("applies_conditions", "spells"):
                if field in record["fields"]:
                    record["fields"][field] = []
        self.load(cleared)
        self.assertFalse(Spell.objects.get(pk=PILOT_INVISIBILITY).applies_conditions.exists())
        self.assertFalse(MagicItem.objects.get(pk=PILOT_WAND).spells.exists())
        self.load(self.pilot)
        self.assertEqual(Spell.objects.get(pk=PILOT_INVISIBILITY).applies_conditions.count(), 1)
        self.assertEqual(MagicItem.objects.get(pk=PILOT_WAND).spells.count(), 1)
        self.assertEqual(source_snapshot(self.original), self.original_snapshot)

    def test_dangling_reference_rolls_back_prior_changes_and_relation_clears(self):
        for key, relation in ((PILOT_INVISIBILITY, "applies_conditions"), (PILOT_WAND, "spells")):
            with self.subTest(relation=relation):
                broken = copy.deepcopy(self.pilot)
                for record in broken:
                    if record["pk"] == PILOT_DOCUMENT:
                        record["fields"]["name"] = "本次改名必须回滚"
                    if record["pk"] == PILOT_INVISIBILITY:
                        record["fields"]["applies_conditions"] = []
                    if record["pk"] == key:
                        record["fields"][relation] = ["missing-reference"]
                with self.assertRaisesRegex(CommandError, "引用不存在"):
                    self.load(broken)
                self.assertEqual(Document.objects.get(pk=PILOT_DOCUMENT).name, "开物修仙试录")
                self.assertEqual(Spell.objects.get(pk=PILOT_INVISIBILITY).applies_conditions.get().pk, PILOT_INVISIBLE)
                self.assertEqual(MagicItem.objects.get(pk=PILOT_WAND).spells.get().pk, PILOT_DETECTION)
                self.assertEqual(source_snapshot(self.original), self.original_snapshot)

    def test_non_null_validation_still_rejects_bad_values(self):
        invalid_values = ((PILOT_INVISIBILITY, "level", -1), (PILOT_INVISIBILITY, "level", 10),
                          (PILOT_INVISIBILITY, "school", "missing-school"),
                          (PILOT_INVISIBILITY, "casting_time", "随意施展"),
                          (PILOT_INVISIBILITY, "duration", "随意持续"),
                          (PILOT_INVISIBILITY, "name", ""),
                          (PILOT_WAND, "weight", "-1"), (PILOT_WAND, "cost", "-1"),
                          (PILOT_WAND, "armor_class", 101), (PILOT_WAND, "hit_points", 10001),
                          (PILOT_DOCUMENT, "permalink", "not-a-url"))
        for key, field, value in invalid_values:
            with self.subTest(key=key, field=field, value=value):
                broken = copy.deepcopy(self.pilot)
                next(record for record in broken if record["pk"] == key)["fields"][field] = value
                with self.assertRaises(CommandError):
                    self.load(broken)
        self.assertEqual(source_snapshot(self.original), self.original_snapshot)
        self.assertIsNone(Spell.objects.get(pk=PILOT_INVISIBILITY).level)
        self.assertIsNone(MagicItem.objects.get(pk=PILOT_WAND).weight)

    def test_source_filters_separate_pilot_and_srd_content(self):
        for endpoint, count in (("spells", 2), ("magicitems", 1)):
            for field, value in (("document__key__in", PILOT_DOCUMENT),
                                 ("document__gamesystem__key__in", "cultivation")):
                response = self.client.get(f"/v2/{endpoint}/", {field: value})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()["count"], count)
                self.assertTrue(all(row["document"]["key"] == PILOT_DOCUMENT
                                    for row in response.json()["results"]))
        for source, key in (("srd-2024", "invisible"), (PILOT_DOCUMENT, PILOT_INVISIBLE)):
            response = self.client.get("/v2/conditions/", {"source_document": source})
            self.assertEqual(response.status_code, 200)
            self.assertEqual([row["key"] for row in response.json()["results"]], [key])
        # 通用状态的身份来源保留 core，不改写为其说明所属的 SRD。
        self.assertEqual(Condition.objects.get(pk="invisible").document_id, "core")
        self.assertEqual(source_snapshot(self.original), self.original_snapshot)


class CultivationMigrationTests(TransactionTestCase):
    """真实执行 0083↔0084，并验证导入后不得有损降级。"""

    def migrate(self, name):
        executor = MigrationExecutor(connection)
        executor.migrate([("api_v2", name)])
        return executor.loader.project_state([("api_v2", name)]).apps

    def test_upgrade_preserves_srd_and_reverse_refuses_loss(self):
        before = "0083_preserve_fractional_size_diameter"
        after = "0084_cultivation_content_references"
        old_apps = self.migrate(before)
        try:
            load_directory("starter", old_apps)
            original = source_snapshot(read_records("starter"), old_apps)
            after_apps = self.migrate(after)
            self.assertEqual(source_snapshot(read_records("starter"), old_apps), original)
            self.assertFalse(Spell.applies_conditions.through.objects.exists())
            self.assertFalse(MagicItem.spells.through.objects.exists())
            # 即使没有新增 null，已有关系也不能在降级时静默丢失。
            old_spell = Spell.objects.order_by("pk").first()
            old_spell.applies_conditions.add(Condition.objects.get(pk="invisible"))
            with self.assertRaisesRegex(RuntimeError, "已有资料引用"):
                self.migrate(before)
            self.assertEqual(old_spell.applies_conditions.count(), 1)
            old_spell.applies_conditions.clear()
            # 未使用新增字段时，反向迁移能完整保留旧资料。
            self.migrate(before)
            self.assertEqual(source_snapshot(read_records("starter"), old_apps), original)
            self.migrate(after)
            load_directory("cultivation", after_apps)
            with self.assertRaisesRegex(RuntimeError, "不能无损回退"):
                self.migrate(before)
            self.assertEqual(source_snapshot(read_records("starter"), old_apps), original)
            self.assertIsNone(Spell.objects.get(pk=PILOT_INVISIBILITY).level)
            self.assertEqual(MagicItem.objects.get(pk=PILOT_WAND).spells.get().pk, PILOT_DETECTION)
        finally:
            self.migrate("0085_catalog_reading")
