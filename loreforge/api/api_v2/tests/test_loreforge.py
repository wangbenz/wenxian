"""开物只读裁剪的本地 SQLite 验证；不加载上游全量内容或启动外部服务。"""

import copy
import json
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory

from django.core.management import call_command, get_commands
from django.core.management.base import CommandError
from django.test import TestCase, override_settings
from api_v2.models import Document, Item, Publisher, Size, Spell


def fixture_records():
    def row(model, pk, **fields):
        return {"model": "api_v2." + model, "pk": pk, "fields": fields}
    # 刻意把法术放在来源之前，验证同批前向引用。
    return [
        row("spell", "test-fire", name="火弹术", desc="仅用于测试中文读取。", document="test-doc",
            level=0, school="test-school", range_text="自身", casting_time="action",
            duration="instantaneous", target_type="creature", target_count=None, classes=[]),
        row("spellschool", "test-school", name="攻击", desc="测试分类。", document="test-doc"),
        row("document", "test-doc", name="结构测试来源", desc="仅作自动验证。", publisher="test-publisher",
            gamesystem="test-system", licenses=["test-license"], author="本地测试",
            publication_date="2026-09-26T00:00:00", permalink="https://example.com/test",
            distance_unit="feet", weight_unit="lb"),
        row("publisher", "test-publisher", name="本地测试"),
        row("license", "test-license", name="内部验证说明", desc="不作为正式资料。"),
        row("gamesystem", "test-system", name="测试体系", desc="测试结构。", content_prefix="test"),
    ]


class LoreforgeReadOnlyTests(TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "starter"
        self.directory.mkdir()
        self.settings_override = override_settings(LOREFORGE_DATA_ROOT=self.root)
        self.settings_override.enable()
        self.addCleanup(self.settings_override.disable)
        self.records = fixture_records()
        self.load(self.records)

    def load(self, records):
        (self.directory / "content.json").write_text(json.dumps(records, ensure_ascii=False), encoding="utf-8")
        call_command("load_loreforge", str(self.directory), stdout=StringIO())

    def test_import_repeats_update_stable_keys_and_preserve_m2m(self):
        self.records[0]["fields"]["name"] = "火弹术修订"
        self.load(self.records)
        self.assertEqual(Spell.objects.count(), 1)
        self.assertEqual(Spell.objects.get(pk="test-fire").name, "火弹术修订")
        self.assertEqual(list(Document.objects.get(pk="test-doc").licenses.values_list("pk", flat=True)), ["test-license"])

    def test_invalid_field_after_update_rolls_back_whole_batch(self):
        broken = copy.deepcopy(self.records)
        broken[0]["fields"]["name"] = "不得生效"
        broken[-1]["fields"]["unexpected_field"] = True
        with self.assertRaises(CommandError):
            self.load(broken)
        self.assertEqual(Spell.objects.get(pk="test-fire").name, "火弹术")
        self.assertEqual(Publisher.objects.count(), 1)

    def test_fixture_fields_cannot_override_primary_key(self):
        broken = copy.deepcopy(self.records)
        broken[0]["fields"]["name"] = "前序修改也不得生效"
        broken[3]["fields"]["key"] = "redirected-publisher"
        with self.assertRaisesRegex(CommandError, "fields 不得包含主键字段"):
            self.load(broken)
        self.assertEqual(Spell.objects.get(pk="test-fire").name, "火弹术")
        self.assertEqual(Publisher.objects.get(pk="test-publisher").name, "本地测试")
        self.assertFalse(Publisher.objects.filter(pk="redirected-publisher").exists())

        # 自增整数主键模型同样只能由 fixture.pk 指定，不能在 fields.id 覆盖。
        with self.assertRaisesRegex(CommandError, "fields 不得包含主键字段"):
            self.load([{"model": "api_v2.spellcastingoption", "pk": 123,
                        "fields": {"id": 456, "parent": "test-fire", "type": "default"}}])
        from api_v2.models import SpellCastingOption
        self.assertFalse(SpellCastingOption.objects.exists())

    def test_missing_relation_rolls_back_updates_and_old_links(self):
        broken = copy.deepcopy(self.records)
        broken[0]["fields"]["name"] = "不得生效"
        broken[2]["fields"]["licenses"] = ["missing-license"]
        with self.assertRaises(CommandError):
            self.load(broken)
        self.assertEqual(Spell.objects.get(pk="test-fire").name, "火弹术")
        self.assertEqual(Document.objects.get(pk="test-doc").licenses.get().pk, "test-license")

    def test_missing_foreign_key_and_invalid_choices_roll_back(self):
        for field, invalid in (("school", "missing-school"), ("casting_time", "not-a-choice")):
            with self.subTest(field=field):
                broken = copy.deepcopy(self.records)
                broken[0]["fields"][field] = invalid
                with self.assertRaises(CommandError):
                    self.load(broken)
                self.assertEqual(Spell.objects.get(pk="test-fire").school_id, "test-school")

    def test_only_selected_models_and_data_directory(self):
        with self.assertRaises(CommandError):
            self.load([{"model": "api_v2.creature", "pk": "forbidden", "fields": {}}])
        with TemporaryDirectory() as outside:
            with self.assertRaises(CommandError):
                call_command("load_loreforge", outside, stdout=StringIO())
        self.assertEqual(Spell.objects.count(), 1)

    def test_duplicate_identity_is_rejected_before_writing(self):
        with self.assertRaises(CommandError):
            self.load(self.records + [self.records[0]])
        self.assertEqual(Spell.objects.count(), 1)

    def test_fractional_size_survives_import_database_and_api(self):
        tiny = {"model": "api_v2.size", "pk": "test-tiny", "fields": {
            "name": "微型", "document": "test-doc", "rank": 0,
            "space_diameter": 2.5, "distance_unit": None,
        }}
        self.load([tiny])
        self.assertEqual(Size.objects.get(pk="test-tiny").space_diameter, 2.5)
        response = self.client.get("/v2/sizes/", {"key__in": "test-tiny"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["results"][0]["space_diameter"], 2.5)
        self.assertEqual(response.json()["results"][0]["distance_unit"], "feet")
        self.assertIsInstance(response.json()["results"][0]["space_diameter"], float)

    def test_explicit_default_empty_string_is_preserved_without_relaxing_name(self):
        rows = [
            {"model": "api_v2.size", "pk": "test-item-size", "fields": {
                "name": "微型", "document": "test-doc", "rank": 0, "space_diameter": 2.5,
            }},
            {"model": "api_v2.itemcategory", "pk": "test-category", "fields": {
                "name": "测试药材", "document": "test-doc",
            }},
            {"model": "api_v2.item", "pk": "test-empty-default", "fields": {
                "name": "测试材料", "desc": "仅作回归验证。", "document": "test-doc",
                "category": "test-category", "size": "test-item-size", "armor_detail": "",
            }},
        ]
        self.load(rows)
        self.assertEqual(Item.objects.get(pk="test-empty-default").armor_detail, "")
        self.assertEqual(self.client.get("/v2/items/test-empty-default/").status_code, 200)
        rows[-1]["fields"]["name"] = ""
        with self.assertRaises(CommandError):
            self.load(rows)
        self.assertEqual(Item.objects.get(pk="test-empty-default").name, "测试材料")
        self.assertEqual(Item.objects.get(pk="test-empty-default").armor_detail, "")

    def test_source_publication_date_remains_naive_without_invented_timezone(self):
        self.assertIsNone(Document.objects.get(pk="test-doc").publication_date.tzinfo)
        response = self.client.get("/v2/documents/test-doc/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["publication_date"], "2026-09-26T00:00:00")

    def test_nonstandard_json_numbers_are_rejected_before_writing(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=str(value)):
                broken = copy.deepcopy(self.records)
                broken[0]["fields"]["name"] = "不得生效"
                broken[0]["fields"]["range"] = value
                with self.assertRaisesRegex(CommandError, "JSON 不允许非有限常量"):
                    self.load(broken)
                self.assertEqual(Spell.objects.get(pk="test-fire").name, "火弹术")

    def test_v2_reading_chinese_filters_and_pagination(self):
        response = self.client.get("/v2/spells/", {"name__icontains": "火弹", "limit": 1})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["count"], 1)
        self.assertEqual(response.json()["results"][0]["name"], "火弹术")
        detail = self.client.get("/v2/spells/test-fire/")
        self.assertEqual(detail.status_code, 200)
        self.assertEqual(detail.json()["document"]["key"], "test-doc")
        self.assertEqual(self.client.get("/v2/spells/", {"name__icontains": "不存在"}).json()["count"], 0)

    def test_all_exposed_endpoints_refuse_write_methods(self):
        endpoints = self.client.get("/v2/").json()
        self.assertEqual(set(endpoints), {
            "spells", "items", "magicitems", "rules", "rulesets", "conditions", "documents",
            "licenses", "publishers", "gamesystems", "spellschools", "itemcategories",
            "itemrarities", "sizes", "damagetypes", "catalog",
        })
        for resource in endpoints:
            for method in ("post", "put", "patch", "delete"):
                with self.subTest(resource=resource, method=method):
                    response = getattr(self.client, method)(f"/v2/{resource}/", data="{}", content_type="application/json")
                    self.assertEqual(response.status_code, 405)
        for method in ("post", "put", "patch", "delete"):
            self.assertEqual(getattr(self.client, method)("/v2/spells/test-fire/", data="{}", content_type="application/json").status_code, 405)
        self.assertEqual(Spell.objects.count(), 1)

    def test_closed_routes_and_legacy_setup_not_active(self):
        for route in ("/spells/", "/v1/spells/", "/admin/", "/__debug__/", "/search/", "/v2/creatures/", "/v2/classes/", "/v2/weapons/", "/schema/"):
            self.assertEqual(self.client.get(route).status_code, 404, route)
        self.assertNotIn("quicksetup", get_commands())
        self.assertNotIn("api", __import__("django.conf", fromlist=["settings"]).settings.INSTALLED_APPS)

    def test_cors_allows_only_local_browser_origins(self):
        allowed = self.client.get("/v2/spells/", HTTP_ORIGIN="http://127.0.0.1:5178")
        self.assertEqual(allowed["Access-Control-Allow-Origin"], "http://127.0.0.1:5178")
        denied = self.client.get("/v2/spells/", HTTP_ORIGIN="https://example.com")
        self.assertNotIn("Access-Control-Allow-Origin", denied)
