"""The model for an item."""

from django.db import models
from django.core.validators import MinValueValidator, MaxValueValidator

from .weapon import Weapon
from .armor import Armor
from .abstracts import HasName, HasDescription, HasPrice
from .object import Object
from .damagetype import DamageType
from .document import FromDocument
from .enums import OBJECT_ARMOR_CLASS_MAXIMUM, OBJECT_HIT_POINT_MAXIMUM
from drf_spectacular.utils import extend_schema_field
from drf_spectacular.types import OpenApiTypes
import decimal
from api_v2.catalog_types import ITEM_KINDS

class ItemRarity(HasName, FromDocument):
    """A class describing the rarity of items."""
    rank = models.IntegerField(
        unique=True,
        help_text='Ranking of the rarity, most common has the lowest values.')


class ItemCategory(HasName, FromDocument):
    """A class describing categories of items."""
    content_kind = models.CharField(
        max_length=24, choices=ITEM_KINDS, default="item", db_index=True,
        help_text="统一目录的物品分类，不推导品阶、价格或使用能力。")


class ItemBase(models.Model):
    """
    Common inheritance of Item and MagicItem models
    """
    weapon = models.ForeignKey(
        Weapon,
        on_delete=models.CASCADE,
        default=None,
        blank=True,
        null=True)

    armor = models.ForeignKey(
        Armor,
        on_delete=models.CASCADE,
        default=None,
        blank=True,
        null=True)

    category = models.ForeignKey(
        ItemCategory,
        on_delete=models.CASCADE,
        null=False
    )

    damage_vulnerabilities = models.ManyToManyField(DamageType,
        related_name="%(class)s_damage_vulnerabilities")

    damage_immunities = models.ManyToManyField(DamageType,
        related_name="%(class)s_damage_immunities")

    damage_resistances = models.ManyToManyField(DamageType,
        related_name="%(class)s_damage_resistances")
    
    class Meta:
        abstract = True

class Item(ItemBase, Object, HasDescription, FromDocument, HasPrice):
    """
    This is the model for an Item, which is an object that can be used.

    This extends the object model, but adds cost, and is_magical.
    """
    # 普通灵材也可能没有5e物理/战斗数值；仅放宽 Item，保留 Object 与 Creature。
    size = models.ForeignKey("Size", on_delete=models.CASCADE, null=True, blank=True)
    weight = models.DecimalField(default=0, null=True, blank=True, max_digits=10,
        decimal_places=3, validators=[MinValueValidator(decimal.Decimal(0.0))],
        help_text="物品重量；未知时为null，沿用来源单位。")
    armor_class = models.IntegerField(default=0, null=True, blank=True,
        validators=[MinValueValidator(0), MaxValueValidator(OBJECT_ARMOR_CLASS_MAXIMUM)],
        help_text="原规则防御等级；不适用时为null。")
    hit_points = models.IntegerField(default=0, null=True, blank=True,
        validators=[MinValueValidator(0), MaxValueValidator(OBJECT_HIT_POINT_MAXIMUM)],
        help_text="原规则生命值；不适用时为null。")
    nonmagical_attack_resistance = models.BooleanField(default=False, null=True, blank=True)
    nonmagical_attack_immunity = models.BooleanField(default=False, null=True, blank=True)


class MagicItem(ItemBase, Object, HasDescription, FromDocument, HasPrice):
    # 仅放宽法器中未设计的物理/战斗字段；普通物品与生物仍沿用 Object。
    # 保留原构造默认值以兼容旧 fixture，新资料须显式用 null 表示未知。
    size = models.ForeignKey(
        "Size", on_delete=models.CASCADE, null=True, blank=True)

    weight = models.DecimalField(
        default=0, null=True, blank=True, max_digits=10, decimal_places=3,
        validators=[MinValueValidator(decimal.Decimal(0.0))],
        help_text='Number representing the weight of the object.')

    armor_class = models.IntegerField(
        default=0, null=True, blank=True,
        validators=[MinValueValidator(0), MaxValueValidator(OBJECT_ARMOR_CLASS_MAXIMUM)],
        help_text='Integer representing the armor class of the object.')

    hit_points = models.IntegerField(
        default=0, null=True, blank=True,
        validators=[MinValueValidator(0), MaxValueValidator(OBJECT_HIT_POINT_MAXIMUM)],
        help_text='Integer representing the hit points of the object.')

    nonmagical_attack_resistance = models.BooleanField(
        null=True, blank=True, default=False,
        help_text='Whether the object is resistant to nonmagical attacks.')

    nonmagical_attack_immunity = models.BooleanField(
        null=True, blank=True, default=False,
        help_text='Whether the object is immune to nonmagical attacks.')

    requires_attunement = models.BooleanField(
        null=True,
        blank=True,
        default=False,
        help_text='If the item requires attunement.')

    attunement_detail = models.CharField(
        null=True,
        blank=True,
        max_length=128,
    )

    rarity = models.ForeignKey(
        "ItemRarity",
        null=True,
        on_delete=models.CASCADE,
        help_text="Rarity object.")

    spells = models.ManyToManyField(
        "Spell",
        blank=True,
        related_name="granting_magic_items",
        help_text="器物提供的法术能力；仅作内容引用，不自动施法或扣除次数。")


class ItemSet(HasName, HasDescription, FromDocument):
    """A set of items to be referenced."""

    items = models.ManyToManyField(Item, related_name="itemsets",help_text="The set of items.")
