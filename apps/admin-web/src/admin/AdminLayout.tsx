import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Button, Drawer, Grid, Layout, Menu, Tabs, Tag, Tooltip, Typography, type MenuProps } from "antd";
import { Key, List, SignOut, X } from "@phosphor-icons/react";
import {
  ADMIN_MODULES,
  DEFAULT_PAGE_BY_MODULE,
  findAdminModule,
  findAdminPage,
  pagesInModule,
  type AdminPageId,
} from "./navigation";

const { Header, Sider, Content } = Layout;
const { Text, Title } = Typography;

type AdminLayoutProps = {
  page: AdminPageId;
  version: string;
  reportingDate: string;
  children: ReactNode;
  onNavigate: (page: AdminPageId) => void;
  onChangePassword: () => void;
  onLogout: () => void;
};

type SidebarContentProps = Pick<AdminLayoutProps, "page" | "version" | "onNavigate" | "onChangePassword" | "onLogout"> & {
  collapsed?: boolean;
  mobile?: boolean;
  onClose?: () => void;
};

function SidebarAction({ collapsed, icon, label, onClick }: {
  collapsed: boolean;
  icon: ReactNode;
  label: string;
  onClick: () => void;
}) {
  const button = <Button block className="wx-admin-sidebar-action" icon={icon} onClick={onClick} type="text">
    {!collapsed && <span>{label}</span>}
  </Button>;
  return collapsed ? <Tooltip placement="right" title={label}>{button}</Tooltip> : button;
}

function SidebarContent({ page, version, collapsed = false, mobile = false, onNavigate, onChangePassword, onLogout, onClose }: SidebarContentProps) {
  const currentPage = findAdminPage(page);
  const menuItems = useMemo<MenuProps["items"]>(() => ADMIN_MODULES.map((module) => {
    const Icon = module.icon;
    const selected = module.id === currentPage.module;
    return {
      key: module.id,
      icon: <Icon aria-hidden="true" size={20} weight={selected ? "fill" : "regular"} />,
      label: module.label,
      title: module.label,
    };
  }), [currentPage.module]);

  const navigateModule: MenuProps["onClick"] = ({ key }) => {
    onNavigate(DEFAULT_PAGE_BY_MODULE[key as keyof typeof DEFAULT_PAGE_BY_MODULE]);
    onClose?.();
  };

  return <div className={`wx-admin-sidebar-inner${collapsed ? " is-collapsed" : ""}`}>
    <div className="wx-admin-brand">
      <span className="wx-admin-brand-seal" aria-hidden="true">问</span>
      {!collapsed && <span className="wx-admin-brand-copy"><strong>问仙录管理台</strong><small>WENXIAN OPS</small></span>}
      {mobile && <Button aria-label="关闭导航菜单" className="wx-admin-drawer-close" icon={<X size={18} weight="bold" />} onClick={onClose} type="text" />}
    </div>
    <div className="wx-admin-brand-divider" />
    <Menu
      aria-label="管理台一级导航"
      className="wx-admin-menu"
      inlineCollapsed={collapsed}
      items={menuItems}
      mode="inline"
      onClick={navigateModule}
      selectedKeys={[currentPage.module]}
      theme="dark"
    />
    <div className="wx-admin-sidebar-spacer" />
    <div className="wx-admin-account">
      <span className="wx-admin-account-avatar" aria-hidden="true">问</span>
      {!collapsed && <span><strong>问仙小筑</strong><small>运营管理员</small></span>}
    </div>
    <div className="wx-admin-sidebar-actions">
      <SidebarAction collapsed={collapsed} icon={<Key size={18} />} label="修改密码" onClick={() => { onChangePassword(); onClose?.(); }} />
      <SidebarAction collapsed={collapsed} icon={<SignOut size={18} />} label="退出管理台" onClick={onLogout} />
    </div>
    {!collapsed && <p className="wx-admin-version">当前版本 {version}</p>}
  </div>;
}

export function AdminLayout({ page, version, reportingDate, children, onNavigate, onChangePassword, onLogout }: AdminLayoutProps) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const screens = Grid.useBreakpoint();
  // 两级断点分别承担不同职责：中等宽度保留快捷侧栏，窄屏才完全切换为 Drawer。
  const mobile = screens.md === false;
  const collapsed = !mobile && screens.xl !== true;
  const currentPage = findAdminPage(page);
  const currentModule = findAdminModule(currentPage.module);
  const modulePages = pagesInModule(currentModule.id);

  useEffect(() => setDrawerOpen(false), [page]);

  return <Layout className="wx-admin-root">
    {!mobile && <Sider
      className="wx-admin-sider"
      collapsed={collapsed}
      collapsedWidth={72}
      trigger={null}
      width={216}
    >
      <SidebarContent collapsed={collapsed} onChangePassword={onChangePassword} onLogout={onLogout} onNavigate={onNavigate} page={page} version={version} />
    </Sider>}
    <Layout className={`wx-admin-main${collapsed ? " is-collapsed" : ""}${mobile ? " is-mobile" : ""}`}>
      <Header className="wx-admin-header">
        <div className="wx-admin-heading">
          {mobile && <Button aria-expanded={drawerOpen} aria-label="打开导航菜单" className="wx-admin-menu-button" icon={<List size={20} weight="bold" />} onClick={() => setDrawerOpen(true)} />}
          <div><Title level={1}>{currentModule.label}</Title><Text>{currentPage.label}</Text></div>
        </div>
        <div className="wx-admin-header-meta">
          <Tag className="wx-admin-environment" variant="filled">内部试玩环境</Tag>
          {!mobile && <Text>北京时间 · {reportingDate}</Text>}
        </div>
      </Header>
      {/* 二级页签始终位于内容顶部；移动端只横向滚动，不重复塞进一级导航抽屉。 */}
      <Tabs
        activeKey={page}
        className="wx-admin-tabs"
        items={modulePages.map((item) => ({
          key: item.id,
          label: item.planned ? <span>{item.label}<small>待接入</small></span> : item.label,
        }))}
        onChange={(key) => onNavigate(key as AdminPageId)}
        tabBarGutter={28}
      />
      <Content className="wx-admin-content-shell">
        <section className="admin-content">{children}</section>
      </Content>
    </Layout>
    <Drawer
      className="wx-admin-mobile-drawer"
      closable={false}
      onClose={() => setDrawerOpen(false)}
      open={mobile && drawerOpen}
      placement="left"
      size={320}
    >
      <SidebarContent mobile onChangePassword={onChangePassword} onClose={() => setDrawerOpen(false)} onLogout={onLogout} onNavigate={onNavigate} page={page} version={version} />
    </Drawer>
  </Layout>;
}
