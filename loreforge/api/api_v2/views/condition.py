from rest_framework import viewsets

from django_filters import CharFilter, FilterSet

from api_v2 import models, serializers
from .mixins import EagerLoadingMixin, ExcludeFieldsMixin

class ConditionFilterSet(FilterSet):
    # 通用状态身份可来自 core；筛选实际说明来源时不能替换 document 的含义。
    source_document = CharFilter(
        field_name='conditiondescription__document__key', distinct=True)

    class Meta:
        model = models.Condition
        fields = {
            'key': ['in', 'iexact', 'exact' ],
            'name': ['iexact', 'exact','contains'],
            'document__key': ['in','iexact','exact'],
            'document__gamesystem__key': ['in','iexact','exact'],
        }


class ConditionViewSet(EagerLoadingMixin, ExcludeFieldsMixin, viewsets.ReadOnlyModelViewSet):
    """
    list: API endpoint for returning a list of conditions.
    retrieve: API endpoint for returning a particular condition.
    """
    queryset = models.Condition.objects.all().order_by('pk')
    serializer_class = serializers.ConditionSerializer
    filterset_class = ConditionFilterSet

    select_related_fields = []
    prefetch_related_fields = ['document__gamesystem',
                                'icon']
