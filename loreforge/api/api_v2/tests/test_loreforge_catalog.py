"""真实通用资料批次的导入、只读查询及无损迁移验收，使用隔离测试库。"""

import copy
import json
import re
from collections import Counter
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory

from django.core.management import call_command
from django.core.management.base import CommandError
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.test import TestCase, TransactionTestCase, override_settings

from api_v2.models import Document, Item, MagicItem, Rule, Spell
from api_v2.tests.test_loreforge_cultivation import load_directory, read_records, source_snapshot


class CatalogTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        load_directory("starter")
        load_directory("cultivation")
        cls.old_records = read_records("starter") + read_records("cultivation")
        cls.before = source_snapshot(cls.old_records)
        load_directory("xianxia")

    def catalogue(self, **params):
        response = self.client.get("/v2/catalog/", {"document__key__in": "xianxia-core", **params})
        self.assertEqual(response.status_code, 200)
        return response.json()

    def test_full_native_catalogue_and_all_detail_routes(self):
        page = self.catalogue(limit=100)
        self.assertEqual(page["count"], 107)
        self.assertIsNotNone(page["next"])
        page["results"] += self.catalogue(limit=100, page=2)["results"]
        self.assertEqual(Counter(row["resource"] for row in page["results"]),
                         {"spells": 16, "magicitems": 27, "items": 17, "rules": 47})
        keys = {row["key"] for row in page["results"]}
        for row in page["results"]:
            with self.subTest(key=row["key"]):
                response = self.client.get(row["api_url"])
                self.assertEqual(response.status_code, 200)
                body = response.json()
                self.assertEqual(body["key"], row["key"])
                self.assertEqual(body["document"]["key"], "xianxia-core")
                self.assertIn("http", body["desc"])
                for ref in re.findall(r"\]\(/(?:spells|magic-items|equipment|rules/entries)/(xianxia_[^)]+)\)", body["desc"]):
                    self.assertIn(ref, keys)
        shield = self.client.get("/v2/magicitems/xianxia_item-floating-shield/").json()
        self.assertIn("/spells/xianxia_spell-fire-bolt", shield["desc"])
        self.assertEqual(shield["spells"], [])  # 相关资料不能被误写为器物可施法能力。

    def test_filters_pagination_and_separate_sources(self):
        expected = {"realm": 8, "practice": 4, "spell": 16, "artifact": 12, "talisman": 9,
                    "pill": 6, "material": 15, "creature": 15, "setting": 13, "rule": 7, "item": 2}
        for kind, count in expected.items():
            page = self.catalogue(kind=kind)
            self.assertEqual(page["count"], count)
            self.assertTrue(all(row["kind"] == kind for row in page["results"]))
        seen = set()
        for number in range(1, 6):
            page = self.catalogue(limit=25, page=number)
            current = {row["key"] for row in page["results"]}
            self.assertEqual(len(current), 25 if number < 5 else 7)
            self.assertFalse(seen & current)
            seen |= current
        self.assertEqual(len(seen), 107)
        self.assertEqual(self.catalogue(name__icontains="筑基丹")["count"], 1)
        self.assertEqual(self.catalogue(name__icontains="没有这样的资料")["results"], [])
        other = self.catalogue(document__key__in="srd-2024")
        self.assertTrue(other["count"] > 0)
        self.assertTrue(all(row["document"]["key"] == "srd-2024" for row in other["results"]))
        realms = self.client.get("/v2/rules/", {"ruleset__content_kind": "realm"}).json()
        self.assertEqual(realms["count"], 8)
        self.assertFalse(Document.objects.filter(key__contains="qinghe").exists())

    def test_reading_excerpts_and_declared_item_categories(self):
        page = self.catalogue(limit=100)
        for row in page["results"]:
            self.assertTrue(row["excerpt"], row["key"])
            self.assertLessEqual(len(row["excerpt"]), 141)
            self.assertNotIn("**", row["excerpt"])
        fox = next(row for row in page["results"] if row["key"] == "xianxia_creature-nine-tail-fox")
        self.assertEqual(fox["excerpt"], "带九尾、可能捕食人的高警惕性异兽。")
        pill = self.client.get("/v2/magicitems/xianxia_pill-foundation/").json()
        self.assertEqual(pill["category"]["content_kind"], "pill")
        stone = self.client.get("/v2/items/xianxia_resource-spirit-stone/").json()
        self.assertEqual(stone["category"]["content_kind"], "material")
        Spell.objects.filter(pk="xianxia_spell-fire-bolt").update(desc="### 用途\n\n**定义。** 查阅[灵石](/equipment/stone)与<b>器物</b>。")
        spell = self.catalogue(name__icontains="火弹术")["results"][0]
        self.assertEqual(spell["excerpt"], "查阅灵石与器物。")

    def test_realm_order_neighbors_and_independent_ascension_process(self):
        expected = ["炼气", "筑基", "结丹", "元婴", "化神", "炼虚", "合体", "大乘"]
        rows = self.catalogue(kind="realm", ordering="sequence")["results"]
        self.assertEqual([row["name"] for row in rows], expected)
        self.assertEqual([row["sequence"] for row in rows], list(range(1, 9)))
        # 即使别的来源复用同一章节，也不能串入当前来源的进阶阅读关系。
        Rule.objects.create(key="foreign-realm", name="别的来源", desc="独立内容", index=3,
                            document_id="srd-2024", ruleset_id="xianxia_realm")
        for i, row in enumerate(rows):
            body = self.client.get(row["api_url"]).json()
            neighbors = body["reading_neighbors"]
            for field, offset in (("previous", -1), ("next", 1)):
                other = i + offset
                expected_ref = {"key": rows[other]["key"], "name": expected[other]} if 0 <= other < 8 else None
                self.assertEqual(neighbors[field], expected_ref)
            self.assertIn("境界衔接", body["desc"])
            self.assertIn("关联资料与用途", body["desc"])
        process = self.client.get("/v2/rules/xianxia_process-tribulation-ascension/").json()
        self.assertEqual(process["content_kind"], "rule")
        self.assertIsNone(process["reading_neighbors"])
        self.assertIn("不作为第九个", process["desc"])
        self.assertIn("xianxia_realm-mahayana", process["desc"])
        self.assertEqual(self.client.get("/v2/catalog/", {"ordering": "sequence"}).status_code, 400)
        foundation = self.client.get("/v2/rules/xianxia_realm-foundation/").json()
        self.assertIn("常规突破资源", foundation["desc"])
        self.assertIn("xianxia_pill-foundation", foundation["desc"])
        treasure = self.client.get("/v2/rules/xianxia_item-class-magic-treasure/").json()
        self.assertIn("xianxia_realm-core-formation", treasure["desc"])

    def test_unknown_values_are_not_fabricated_and_reimport_preserves_existing_sources(self):
        for spell in Spell.objects.filter(document_id="xianxia-core"):
            for field in ("level", "duration", "verbal", "somatic", "material", "concentration", "range"):
                self.assertIsNone(getattr(spell, field))
        for model in (Item, MagicItem):
            for item in model.objects.filter(document_id="xianxia-core"):
                for field in ("cost", "weight", "size", "armor_class", "hit_points", "nonmagical_attack_immunity"):
                    self.assertIsNone(getattr(item, field))
        imported = source_snapshot(read_records("xianxia"))
        load_directory("xianxia")
        self.assertEqual(source_snapshot(read_records("xianxia")), imported)
        self.assertEqual(source_snapshot(self.old_records), self.before)

    def test_invalid_categories_roll_back_batch_and_invalid_queries_fail_clearly(self):
        records = copy.deepcopy(read_records("xianxia"))
        next(row for row in records if row["pk"] == "xianxia-core")["fields"]["name"] = "必须回滚"
        next(row for row in records if row["model"] == "api_v2.ruleset")["fields"]["content_kind"] = "unknown"
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "content.json").write_text(json.dumps(records))
            with override_settings(LOREFORGE_DATA_ROOT=root), self.assertRaises(CommandError):
                call_command("load_loreforge", directory, stdout=StringIO())
        self.assertEqual(Document.objects.get(pk="xianxia-core").name, "凡人流修仙资料")
        for params in ({"kind": "unknown"}, {"ordering": "desc"}, {"search": "字" * 201}):
            self.assertEqual(self.client.get("/v2/catalog/", params).status_code, 400)
        for method in ("post", "put", "patch", "delete"):
            self.assertEqual(getattr(self.client, method)("/v2/catalog/").status_code, 405)


class CatalogMigrationTests(TransactionTestCase):
    def migrate(self, name):
        executor = MigrationExecutor(connection)
        executor.migrate([("api_v2", name)])
        return executor.loader.project_state([("api_v2", name)]).apps

    def test_upgrade_preserves_old_values_and_reverse_protects_new_content(self):
        before, after = "0084_cultivation_content_references", "0085_catalog_reading"
        old_apps = self.migrate(before)
        try:
            load_directory("starter", old_apps)
            load_directory("cultivation", old_apps)
            records = read_records("starter") + read_records("cultivation")
            original = source_snapshot(records, old_apps)
            self.migrate(after)
            self.assertEqual(source_snapshot(records, old_apps), original)
            self.migrate(before)  # 尚未使用新增分类或空值时可以无损回退。
            self.assertEqual(source_snapshot(records, old_apps), original)
            self.migrate(after)
            Spell.objects.filter(pk="loreforge_invisibility").update(verbal=None)
            with self.assertRaisesRegex(RuntimeError, "新增空值"):
                self.migrate(before)
            Spell.objects.filter(pk="loreforge_invisibility").update(verbal=True)
            load_directory("xianxia")
            with self.assertRaisesRegex(RuntimeError, "资料分类"):
                self.migrate(before)
            self.assertEqual(Rule.objects.filter(document_id="xianxia-core").count(), 47)
            self.assertEqual(source_snapshot(records, old_apps), original)
        finally:
            self.migrate(after)
