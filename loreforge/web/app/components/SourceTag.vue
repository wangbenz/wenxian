<script lang="ts">
/**
 * SourceTag.vue - Renders a colorful bubble containing infomation about an
 * item from the Open5e API's source document. Inline, designed by follow the
 * title of the API result.
 *
 * -= PROPS (INPUTS) =-
 * @prop {String} text - Text displayed on the tag. Typically a source's 'key'
 * @prop {String} title - Tooltip text that appears on hover
 * @prop {String} textColor - text color of tag
 * @prop {String} background - background color of tag
 * @prop {String} border - border color of tag
 */
</script>

<template>
  <span
    :class="['tag-element', 'font-sans', 'font-medium', 'ml-2']"
    :title="title"
    :style="tagStyle"
  >
    {{ text }}
  </span>
</template>

<script setup lang="ts">
import colors from 'tailwindcss/colors';

const props = defineProps({
  text: { type: String, default: '' },
  title: { type: String, default: '' },
  textColor: { type: String, default: colors.slate[900] },
  background: { type: String, default: colors.slate[100] },
  border: { type: String, default: colors.slate[300] },
});

const tagStyle = computed(() => ({backgroundColor: props.background,color: props.textColor}));
</script>

<style lang="scss" scoped>
.tag-element {
  display: inline-block;
  font-size: 11px;
  padding: 1px 3px;
  line-height: 16px;
  border-radius: 4px;
  white-space: normal;
  overflow-wrap: anywhere;
  position: relative;
  height: min-content;
  top: -1px;
}
</style>
