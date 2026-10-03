const names: Record<string, string> = {
  library: "修仙资料库",
  spells: "法术",
  equipment: "物品",
  "magic-items": "魔法物品",
  rules: "规则",
  conditions: "状态",
  sources: "资料来源",
};
export const useBreadcrumbs = (titleOverride?: Ref<string | undefined>) => {
  const route = useRoute();
  return computed(() => {
    let url = "";
    const segments = route.path.split("/").filter(Boolean);
    return segments.flatMap((segment, index) => {
      url += "/" + segment;
      // entries 是单条规则的路由命名空间，没有可打开的中间目录。
      if (segments[0] === "rules" && index === 1 && segment === "entries") return [];
      return [{
        url,
        src: segment,
        title:
          index === segments.length - 1 && unref(titleOverride)
            ? unref(titleOverride)
            : names[segment] || "条目详情",
        subtitle: "",
      }];
    });
  });
};
