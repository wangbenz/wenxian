"""复用原生资料表的只读联合目录；SQL筛选分页，不复制正文或提供写入口。"""

from django.db.models import F, Value, CharField, IntegerField
from django.db.models.functions import Coalesce, NullIf, Substr
from django.utils.html import strip_tags
import html
import re
from rest_framework import mixins, serializers, viewsets
from rest_framework.exceptions import ValidationError
from api.utils import StandardResultsSetPagination
from api_v2 import models
from api_v2.catalog_types import CATALOG_KINDS


PAGE_PREFIXES = {"spells": "/spells/", "items": "/equipment/",
                 "magicitems": "/magic-items/", "rules": "/rules/entries/"}


class CatalogPagination(StandardResultsSetPagination):
    """目录每页最多100项，避免列表请求无界装载全文。"""
    max_page_size = 100


class CatalogSerializer(serializers.Serializer):
    """返回索引、受限原文摘录与真实详情入口；完整正文按需读取。"""
    key = serializers.CharField()
    name = serializers.CharField()
    resource = serializers.CharField()
    kind = serializers.CharField(source="library_kind")
    kind_label = serializers.SerializerMethodField()
    document = serializers.SerializerMethodField()
    page_url = serializers.SerializerMethodField()
    api_url = serializers.SerializerMethodField()
    excerpt = serializers.SerializerMethodField()
    sequence = serializers.IntegerField(allow_null=True)

    def get_excerpt(self, row):
        """截取首个正文段落供读者辨认用途；不是模型摘要，也不生成资料事实。"""
        for paragraph in re.split(r"\n\s*\n", row["excerpt_text"] or ""):
            paragraph = paragraph.strip()
            if not paragraph or paragraph.startswith("#"):
                continue
            paragraph = re.sub(r"^\*\*[^*]{1,40}\*\*\s*", "", paragraph)
            paragraph = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", paragraph)
            paragraph = html.unescape(strip_tags(paragraph)).replace("**", "").replace("`", "")
            paragraph = re.sub(r"\s+", " ", paragraph).strip()
            return paragraph[:140] + ("…" if len(paragraph) > 140 else "")
        return ""

    def get_kind_label(self, row):
        return CATALOG_KINDS[row["library_kind"]]

    def get_document(self, row):
        return {"key": row["source_key"], "name": row["source_name"]}

    def get_page_url(self, row):
        return PAGE_PREFIXES[row["resource"]] + row["key"]

    def get_api_url(self, row):
        return f'/v2/{row["resource"]}/{row["key"]}/'


class CatalogViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    """按数据库声明分类查询原生条目；非法分类/排序返回400，空结果保留200。"""
    serializer_class = CatalogSerializer
    pagination_class = CatalogPagination
    filter_backends = []

    def get_queryset(self):
        params = self.request.query_params
        kind = params.get("kind", "")
        if kind and kind not in CATALOG_KINDS:
            raise ValidationError({"kind": "未知资料分类"})
        ordering = params.get("ordering", "name")
        if ordering not in ("name", "-name", "key", "-key", "sequence"):
            raise ValidationError({"ordering": "支持名称、标识，以及境界资料的章节顺序"})
        if ordering == "sequence" and kind != "realm":
            raise ValidationError({"ordering": "章节顺序仅用于境界分类，不表示其他资料的等级"})
        name = params.get("name__icontains", params.get("search", ""))
        if len(name) > 200:
            raise ValidationError({"name__icontains": "检索词过长"})
        sources = [key for key in params.get("document__key__in", "").split(",") if key]
        parts = []
        for model, resource, category in (
            (models.Spell, "spells", Value("spell", output_field=CharField())),
            (models.Item, "items", F("category__content_kind")),
            (models.MagicItem, "magicitems", F("category__content_kind")),
            (models.Rule, "rules", F("ruleset__content_kind")),
        ):
            qs = model.objects.order_by().annotate(
                library_kind=category,
                resource=Value(resource, output_field=CharField()),
                source_key=F("document_id"),
                excerpt_text=Substr("desc", 1, 600),
                sequence=F("index") if model is models.Rule else Value(None, output_field=IntegerField()),
                source_name=Coalesce(NullIf("document__display_name", Value("")),
                                     "document__name", output_field=CharField()),
            )
            if name:
                qs = qs.filter(name__icontains=name)
            if sources:
                qs = qs.filter(document_id__in=sources)
            if kind:
                qs = qs.filter(library_kind=kind)
            parts.append(qs.values("key", "name", "library_kind", "resource", "source_key", "source_name", "excerpt_text", "sequence"))
        combined = parts[0].union(*parts[1:], all=True)
        return combined.order_by("source_key", "sequence", "key") if ordering == "sequence" else combined.order_by(ordering, "resource", "key")
