import type { ThemeConfig } from "antd";

/**
 * Ant Design 只提供交互与组件基础，视觉语义继续沿用问仙录管理台现有配色，
 * 避免重构后变成无法辨认的默认后台模板。
 */
export const ADMIN_THEME: ThemeConfig = {
  token: {
    colorPrimary: "#356ae6",
    colorInfo: "#356ae6",
    colorSuccess: "#2f8f67",
    colorWarning: "#d98b2b",
    colorError: "#c94b45",
    colorText: "#1f2937",
    colorTextSecondary: "#667085",
    colorBorder: "#d8dee8",
    colorBgLayout: "#f5f7fa",
    colorBgContainer: "#ffffff",
    borderRadius: 6,
    controlHeight: 40,
    controlHeightSM: 32,
    controlHeightLG: 44,
    fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, "PingFang SC", sans-serif',
  },
  components: {
    Layout: {
      bodyBg: "#f5f7fa",
      headerBg: "#ffffff",
      siderBg: "#20252b",
    },
    Menu: {
      darkItemBg: "#20252b",
      darkSubMenuItemBg: "#20252b",
      darkItemColor: "#bac2cc",
      darkItemHoverBg: "#2a3038",
      darkItemHoverColor: "#ffffff",
      darkItemSelectedBg: "#356ae6",
      darkItemSelectedColor: "#ffffff",
      itemBorderRadius: 6,
      itemHeight: 44,
    },
    Tabs: {
      itemColor: "#667085",
      itemSelectedColor: "#356ae6",
      itemHoverColor: "#2456c7",
      inkBarColor: "#356ae6",
      titleFontSize: 14,
    },
  },
};
