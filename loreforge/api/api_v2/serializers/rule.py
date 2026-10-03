""" Serializer for the Rule model """

from rest_framework import serializers

from api_v2 import models

from .abstracts import GameContentSerializer
from .document import DocumentSummarySerializer

class RuleSerializer(GameContentSerializer):
  document = DocumentSummarySerializer()
  content_kind = serializers.CharField(source='ruleset.content_kind', read_only=True)
  ruleset_name = serializers.CharField(source='ruleset.name', read_only=True)
  reading_neighbors = serializers.SerializerMethodField()

  def get_reading_neighbors(self, rule):
    """只为同来源、同境界章节提供阅读前后项；每请求缓存索引，不载入其他正文。"""
    if rule.ruleset.content_kind != 'realm':
      return None
    cache = self.context.setdefault('realm_reading_sequences', {})
    group = (rule.ruleset_id, rule.document_id)
    if group not in cache:
      cache[group] = list(models.Rule.objects.filter(ruleset_id=group[0], document_id=group[1])
                          .order_by('index', 'key').values('key', 'name'))
    sequence = cache[group]
    position = next((i for i, entry in enumerate(sequence) if entry['key'] == rule.key), None)
    if position is None:
      return None
    return {
      'previous': sequence[position - 1] if position > 0 else None,
      'next': sequence[position + 1] if position + 1 < len(sequence) else None,
    }
  class Meta:
    model = models.Rule
    fields = '__all__'

class RuleSetSerializer(GameContentSerializer):
  document = DocumentSummarySerializer()
  rules = RuleSerializer(many=True)
  class Meta:
    model = models.RuleSet
    fields = ['name', 'key', 'document', 'desc', 'content_kind', 'rules']
