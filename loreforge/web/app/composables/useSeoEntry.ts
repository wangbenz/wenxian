import type { Open5eData } from '@/types';

export function useSeoEntry(data: Ref<Open5eData>) {
	const url = useRequestURL();

	watchEffect(() => {
		if (!data.value) return;
    useState<string | undefined>('pageTitle').value = data.value.name;

		useSeoMeta({
			title: `${data.value.name} | 开物`,
			ogTitle: `${data.value.name} | 开物`,
			ogUrl: url.href,
		});
	});
}