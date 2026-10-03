// 空来源集合表示读取本库全部资料，不沿用 Open5e 官方来源白名单或旧浏览器选择。
export const useSourcesList = () => ({ sources: computed<string[]>(() => []) });
