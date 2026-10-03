"""将明确选择的本地中文资料直接写库；整批校验失败即回滚，不提供 HTTP 写入口。"""

import json
from pathlib import Path

from django.apps import apps
from django.conf import settings
from django.core import serializers
from django.core.exceptions import ValidationError
from django.core.management.base import BaseCommand, CommandError
from django.db import IntegrityError, connection, transaction

# 保留原模型含义；未开放的职业、武器、图片等模型不由此命令写入。
ALLOWED_MODELS = {
    "publisher", "license", "gamesystem", "document", "spellschool",
    "spell", "spellcastingoption", "itemcategory", "itemrarity", "size",
    "item", "magicitem", "ruleset", "rule", "condition",
    "conditiondescription", "damagetype", "damagetypedescription",
}


def unique_object(pairs):
    """拒绝同名 JSON 字段，避免后值悄悄覆盖前值。"""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"JSON 字段重复：{key}")
        result[key] = value
    return result


def reject_nonstandard_number(value):
    """JSON 标准不允许 NaN/Infinity，解析阶段即拒绝，避免入库后无法读取。"""
    raise ValueError(f"JSON 不允许非有限常量：{value}")


class Command(BaseCommand):
    """限定资料根目录和模型白名单，复用 Django 字段校验与数据库事务。

    同稳定主键重导会更新该定义和显式 M2M 集合；未列出的资料不删除。
    不清库、不建索引、不访问网络，任一字段或引用错误会撤销整批修改。
    """

    help = "直接导入 loreforge/data 下指定目录的标准 Django JSON fixtures"

    def add_arguments(self, parser):
        parser.add_argument("directory", help="资料目录，如 ../data/starter")

    def handle(self, *args, **options):
        root = Path(settings.LOREFORGE_DATA_ROOT).resolve()
        directory = Path(options["directory"]).resolve()
        if not directory.is_dir() or not directory.is_relative_to(root):
            raise CommandError("只能导入 loreforge/data 内已有的资料目录")
        files = sorted(directory.glob("*.json"))
        if not files:
            raise CommandError("指定目录没有 JSON 资料文件")

        records, identities = [], set()
        try:
            for path in files:
                if not path.resolve().is_relative_to(root):
                    raise ValueError("资料文件不能通过符号链接越过资料根目录")
                if path.stat().st_size > 8 * 1024 * 1024:
                    raise ValueError(f"资料文件超过 8 MiB：{path.name}")
                content = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object,
                                     parse_constant=reject_nonstandard_number)
                if not isinstance(content, list):
                    raise ValueError(f"{path.name} 必须是 Django fixture 数组")
                for record in content:
                    if not isinstance(record, dict) or set(record) != {"model", "pk", "fields"}:
                        raise ValueError(f"{path.name} 每条必须恰好包含 model、pk、fields")
                    model = record["model"]
                    if not isinstance(model, str) or not model.startswith("api_v2."):
                        raise ValueError("只允许 api_v2 资料模型")
                    if model[7:] not in ALLOWED_MODELS:
                        raise ValueError(f"本轮未开放导入模型：{model}")
                    pk = record["pk"]
                    if model == "api_v2.spellcastingoption":
                        if isinstance(pk, bool) or not isinstance(pk, int) or pk < 1:
                            raise ValueError("施法变体必须使用稳定的正整数主键")
                    elif not isinstance(pk, str) or not pk.strip() or len(pk) > 100:
                        raise ValueError(f"{model} 必须使用不超过100字符的稳定字符串主键")
                    if (model, pk) in identities:
                        raise ValueError(f"同批出现重复主键：{model}/{pk}")
                    if not isinstance(record["fields"], dict):
                        raise ValueError(f"{model}/{pk} 的 fields 必须为对象")
                    identities.add((model, pk))
                    records.append(record)
            if len(records) > 5000:
                raise ValueError("单批资料超过5000条")
            self.load_records(records)
        except (ValueError, ValidationError, IntegrityError, serializers.base.DeserializationError) as exc:
            raise CommandError(f"资料导入失败，整批未生效：{exc}") from exc
        self.stdout.write(self.style.SUCCESS(f"已原子导入 {len(records)} 条资料；同主键更新，未列出的条目保留。"))

    @transaction.atomic
    def load_records(self, records):
        """先保存所有主项再验证引用和关系，允许同批前向引用，异常整体回滚。"""
        pending = []
        for record in records:
            model_name = record["model"][7:]
            model = apps.get_model("api_v2", model_name)
            relation_fields = {field.name: field for field in model._meta.many_to_many}
            fields = dict(record["fields"])
            primary_names = {model._meta.pk.name, model._meta.pk.attname}
            if primary_names.intersection(fields):
                raise ValueError(f"{record['model']}/{record['pk']} 的 fields 不得包含主键字段；身份只能由 pk 指定")
            relations = {key: fields.pop(key) for key in list(fields) if key in relation_fields}
            for name, targets in relations.items():
                if not isinstance(targets, list) or any(not isinstance(key, str) for key in targets):
                    raise ValueError(f"{record['model']}/{record['pk']} 的 {name} 必须是字符串主键数组")
                if len(set(targets)) != len(targets):
                    raise ValueError(f"{record['model']}/{record['pk']} 的 {name} 不可重复")
            payload = {**record, "fields": fields}
            deserialized = next(serializers.deserialize("json", json.dumps([payload], ensure_ascii=False)))
            deserialized.save(save_m2m=False)
            pending.append((deserialized.object, relation_fields, relations))

        for instance, relation_fields, relations in pending:
            # 使用上游模型的类型、choices、长度、Decimal 和外键校验，不另建解释器。
            # blank 是表单约束：保留数据库允许的 NULL，以及模型明确声明的默认空串。
            # 只跳过这两种空值，不放宽无显式空串默认值的 name 等必填字段。
            form_only_empty = [
                field.name for field in instance._meta.concrete_fields
                if (field.null and getattr(instance, field.attname) is None)
                or (field.has_default() and field.default == ""
                    and getattr(instance, field.attname) == "")
            ]
            instance.full_clean(exclude=form_only_empty)
            instance.save()
            for name, keys in relations.items():
                target_model = relation_fields[name].remote_field.model
                found = set(target_model.objects.filter(pk__in=keys).values_list("pk", flat=True))
                missing = set(keys) - found
                if missing:
                    raise ValueError(f"{instance._meta.label}/{instance.pk} 的 {name} 引用不存在：{sorted(missing)}")
                getattr(instance, name).set(keys)

        # SQLite 的延后外键也在本批结束前显式检查，错误仍在事务内抛出。
        connection.check_constraints()
