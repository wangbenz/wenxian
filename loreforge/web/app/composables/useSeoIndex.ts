interface UseSeoIndexProps {
  title: string
};

export function useSeoIndex(data: UseSeoIndexProps) {
  const { title } = data;
  useState<string | undefined>('pageTitle').value = title;
  const url = useRequestURL();

  useSeoMeta({
      title: `${title} | 开物`,
      ogTitle: `${title} | 开物`,
      ogUrl: url.href,
  });
}