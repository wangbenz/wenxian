import {
  ChartBar,
  Cpu,
  PaperPlaneTilt,
  ShieldCheck,
  UsersThree,
  type Icon,
} from "@phosphor-icons/react";

export type AdminModuleId = "data" | "players" | "operations" | "inference" | "system";

export type AdminPageId =
  | "dashboard"
  | "growth"
  | "gameplay"
  | "ai"
  | "users"
  | "credits"
  | "errors"
  | "releases"
  | "operations"
  | "access"
  | "models"
  | "tierPlans"
  | "limits"
  | "byok"
  | "health"
  | "admins"
  | "logs";

export type AdminModuleConfig = {
  id: AdminModuleId;
  label: string;
  icon: Icon;
};

export type AdminPageConfig = {
  id: AdminPageId;
  label: string;
  module: AdminModuleId;
  path: string;
  planned?: boolean;
};

/**
 * 一级模块是管理台长期稳定的信息架构。新增业务时优先放进现有模块，避免侧栏
 * 随功能数量持续增长；只有出现新的独立运营领域时才增加一级模块。
 */
export const ADMIN_MODULES: AdminModuleConfig[] = [
  { id: "data", label: "数据中心", icon: ChartBar },
  { id: "players", label: "玩家中心", icon: UsersThree },
  { id: "operations", label: "运营管理", icon: PaperPlaneTilt },
  { id: "inference", label: "推演服务", icon: Cpu },
  { id: "system", label: "系统设置", icon: ShieldCheck },
];

/**
 * 页面、URL、二级标签与模块归属共用这一份配置，防止标题、页签和路由各自维护后逐渐
 * 产生不一致。planned 页面只建立已确认的信息架构，不伪造尚未存在的后端能力。
 */
export const ADMIN_PAGES: AdminPageConfig[] = [
  { id: "dashboard", label: "运营总览", module: "data", path: "/data/overview" },
  { id: "growth", label: "增长留存", module: "data", path: "/data/growth" },
  { id: "gameplay", label: "游玩行为", module: "data", path: "/data/gameplay" },
  { id: "ai", label: "模型用量", module: "data", path: "/data/model-usage" },

  { id: "users", label: "玩家档案", module: "players", path: "/players/profiles" },
  { id: "credits", label: "推演点账户", module: "players", path: "/players/points" },
  { id: "errors", label: "问题查询", module: "players", path: "/players/problems" },

  { id: "releases", label: "版本公告", module: "operations", path: "/operations/releases" },
  { id: "operations", label: "运营配置", module: "operations", path: "/operations/config" },
  { id: "access", label: "灰度访问", module: "operations", path: "/operations/access" },

  { id: "models", label: "模型库", module: "inference", path: "/inference/models" },
  { id: "tierPlans", label: "三档方案", module: "inference", path: "/inference/plans" },
  { id: "limits", label: "点数规则", module: "inference", path: "/inference/points" },
  { id: "byok", label: "玩家自备服务", module: "inference", path: "/inference/byok" },

  { id: "health", label: "运行状态", module: "system", path: "/system/health" },
  { id: "admins", label: "管理账号", module: "system", path: "/system/admins" },
  { id: "logs", label: "操作记录", module: "system", path: "/system/audit" },
];

export const DEFAULT_PAGE_BY_MODULE: Record<AdminModuleId, AdminPageId> = {
  data: "dashboard",
  players: "users",
  operations: "releases",
  inference: "models",
  system: "health",
};

export const DEFAULT_ADMIN_PATH = ADMIN_PAGES[0].path;

export const findAdminPage = (pageId: AdminPageId) =>
  ADMIN_PAGES.find((page) => page.id === pageId) ?? ADMIN_PAGES[0];

export const findAdminPageByPath = (pathname: string) => {
  const normalized = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  // 旧回合账户链接继续可打开同一页面，避免浏览器书签在术语迁移后失效。
  if (normalized === "/players/turn-credits") return findAdminPage("credits");
  return ADMIN_PAGES.find((page) => page.path === normalized);
};

export const findAdminModule = (moduleId: AdminModuleId) =>
  ADMIN_MODULES.find((module) => module.id === moduleId) ?? ADMIN_MODULES[0];

export const pagesInModule = (moduleId: AdminModuleId) =>
  ADMIN_PAGES.filter((page) => page.module === moduleId);
