<template>
  <ReferenceEntry v-if="item?.document?.gamesystem?.key === 'xianxia'" :entry="item" :kind="item.category.content_kind" :kind-name="item.category.name" />
  <section v-else class="docs-container container">
    <div v-if="item">
      <LibraryBackLink />
      <h1>{{ item.name }}</h1>

      <!-- ITEM CARD FOR WEAPONS -->
      <div v-if="item.weapon">
        <p class="italic">
          {{ formatWeaponSubtitle(item.weapon) }}
        </p>
        <dl class="grid grid-cols-[8rem_1fr]">
          <dt class="font-bold">原规则伤害</dt>
          <dd>{{ item.weapon.damage_dice }}</dd>
          <dt class="font-bold">
            原规则伤害类型
          </dt>
          <dd class="capitalize">
            {{ item.weapon.damage_type.name }}
          </dd>
          <template v-if="item.weapon.properties?.length > 0">
            <dt class="font-bold">
              属性
            </dt>
            <dd class="capitalize">
              <!-- iterate over weapon props, generate csv list of props -->
              {{ item.weapon.properties.map(
                (p) => (p.property.name + (p.detail ? ` (${p.detail})` : "")),
              ).join(', ') }}
            </dd>
          </template>
          <template v-if="parseFloat(item.weight ?? '0') > 0">
            <dt class="font-bold">
              重量（原单位）
            </dt>
            <dd>{{ `${parseFloat(item.weight as string)} lb` }}</dd>
          </template>
          <template v-if="item.cost">
            <dt class="font-bold">
              价值（原规则）
            </dt>
            <dd>{{ formatCost(item.cost) }}</dd>
          </template>
        </dl>
        <md-viewer :text="item.desc" />
      </div>

      <!-- ITEM CARD FOR ARMOR -->
      <div v-else-if="item.armor">
        <p>{{ "原规则护甲" + (item.armor.category ? ` (${item.armor.category})` : "") }}</p>
        <md-viewer :text="item.desc" />
        <dl class="grid grid-cols-[6rem_1fr]">
          <dt class="font-bold">
            原规则防御等级
          </dt>
          <dd>{{ item.armor.ac_display }}</dd>
          <template v-if="item.armor.grants_stealth_disadvantage">
            <dt class="font-bold">
              隐匿
            </dt>
            <dd>原规则劣势</dd>
          </template>
          <template v-if="parseFloat(item.weight ?? '') > 0">
            <dt class="font-bold">
              重量（原单位）
            </dt>
            <dd>{{ `${parseFloat(item.weight ?? '')} lb` }}</dd>
          </template>
          <template v-if="item.cost">
            <dt class="font-bold">
              价值（原规则）
            </dt>
            <dd>{{ formatCost(item.cost ?? '') }}</dd>
          </template>
        </dl>
      </div>

      <!-- DISPLAY COMMON ITEM DATA: category, cost, weight, &c -->
      <div v-else>
        <dl class="grid grid-cols-[6rem_1fr]">
          <dt class="font-bold">
            类别
          </dt>
          <dd>{{ item.category.name }}</dd>
          <template v-if="parseFloat(item.weight ?? '0') > 0">
            <dt class="font-bold">
              重量（原单位）
            </dt>
            <dd>{{ `${parseFloat(item.weight ?? '')} lb` }}</dd>
          </template>
          <template v-if="parseFloat(item.cost ?? '0') > 0">
            <dt class="font-bold">
              价值（原规则）
            </dt>
            <dd>{{ formatCost(item.cost ?? '') }}</dd>
          </template>
        </dl>
        <md-viewer :text="item.desc" />
      </div>
      <p class="text-sm italic">
        来源：
        <NuxtLink
          :to="`/sources/${item.document.key}`"
        >
          {{ item.document.name }}
          <Icon name="heroicons:arrow-top-right-on-square-20-solid" />
        </NuxtLink>
      </p>
    </div>

    <p v-else>
      正在读取资料…
    </p>
  </section>
</template>

<script setup lang="ts">
import type { Item, WeaponSummary } from '@/types';

const itemId = useQueryParameter('id');
const params = { 'is_magic_item': 'false' };
const { data: item } = useFindOne(API_ENDPOINTS.equipment, itemId, { params });

useSeoEntry(item as Ref<Item>);

const formatCost = (input: string) => input ? `${input} gp（原规则金币）` : '';
const weaponHasProperties = (weapon: WeaponSummary, propertiesToFind: string[] = []): boolean => {
  if (!weapon || propertiesToFind.length === 0) return false;

  return weapon.properties.some((item) => (
    propertiesToFind.includes(item.property.name)
  ));
};

const formatWeaponSubtitle = (weapon: WeaponSummary) =>
  `${weaponHasProperties(weapon, ['Ammunition']) ? '原规则远程' : '原规则近战'}武器 `
  + `(${weapon.is_martial ? '军用' : '简易'}`
  + `${weapon.name.length > 0 ? `, ${weapon.name.toLowerCase()}` : ''})`;

</script>
