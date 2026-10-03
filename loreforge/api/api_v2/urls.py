"""保留上游只读视图，首轮仅收窄公开路由，未删除关联模型和迁移。"""

from django.urls import include, path
from rest_framework import routers
from api_v2 import views
from api_v2.views.catalog import CatalogViewSet

router = routers.DefaultRouter()
router.register("catalog", CatalogViewSet, basename="catalog")
router.register("spells", views.SpellViewSet)
router.register("items", views.ItemViewSet, basename="items")
router.register("magicitems", views.MagicItemViewSet, basename="magicitems")
router.register("rules", views.RuleViewSet)
router.register("rulesets", views.RuleSetViewSet)
router.register("conditions", views.ConditionViewSet)
router.register("documents", views.DocumentViewSet)
router.register("licenses", views.LicenseViewSet)
router.register("publishers", views.PublisherViewSet)
router.register("gamesystems", views.GameSystemViewSet)
router.register("spellschools", views.SpellSchoolViewSet)
router.register("itemcategories", views.ItemCategoryViewSet)
router.register("itemrarities", views.ItemRarityViewSet)
router.register("sizes", views.SizeViewSet)
router.register("damagetypes", views.DamageTypeViewSet)

urlpatterns = [path("v2/", include(router.urls))]
