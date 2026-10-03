from django.db import models
from django.core.validators import MinValueValidator

from .abstracts import HasName
from .abstracts import distance_unit_field, damage_die_type_field
from .document import FromDocument

class Size(HasName, FromDocument):
    """
    This is the definition of the Size class.

    The Size class will be used by Objects (and all children classes).
    Basically it describes the size.
    """

    rank = models.IntegerField(
        unique=True,
        help_text='Ranking of the size, smallest has the lowest values.')

    # 开物单点修复：上游 Tiny=2.5，整数距离字段会静默截断；不改其他距离字段。
    space_diameter = models.FloatField(
        null=True,
        blank=True,
        validators=[MinValueValidator(0)],
        help_text="Used to measure distance.",
    )
    distance_unit = distance_unit_field()

    suggested_hit_dice = damage_die_type_field()

    @property
    def get_distance_unit(self):
        if self.distance_unit is None:
            return self.document.distance_unit
        return self.distance_unit
