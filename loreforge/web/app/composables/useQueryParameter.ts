export function useQueryParameter(parameterName: string): ComputedRef<string> {
  const route = useRoute();
  return computed(() => {
    const param = route.params[parameterName];
    return Array.isArray(param) ? param[0] || "" : param || "";
  });
}
