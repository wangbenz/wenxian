import { useQuery } from "@tanstack/vue-query";
import type { EndpointToFindOneTypeMap } from "./api";

function formatQueryString(params?: Record<string, string>): string {
  // iterate over params: format as string[] of the form `field=value`
  if (!params) return "/";
  const query = new URLSearchParams(params).toString();
  return query ? `/?${query}` : "/";
}

export function useFindOne<T extends keyof EndpointToFindOneTypeMap>(
  endpoint: T,
  id: MaybeRef<string>,
  options?: {
    params?: Record<string, string>;
    enabled?: MaybeRef<boolean>;
  }
) {
  const { get } = useAPI();
  const queryString = formatQueryString(options?.params);

  const query = useQuery({
    queryKey: [endpoint, id, options],
    enabled: options?.enabled ?? true,
    queryFn: async (): Promise<EndpointToFindOneTypeMap[T]> => {
      const data = await get(endpoint, unref(id), queryString);
      return data;
    },
  });
  onServerPrefetch(async () => {
    if (unref(options?.enabled ?? true)) await query.suspense();
  });
  return query;
}
