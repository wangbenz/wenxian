import { ArrowRightIcon, ExternalLinkIcon } from "@radix-ui/react-icons";

type ReviewPage = {
  id: string;
  title: string;
  description: string;
  state?: string;
};

type ReviewGroup = {
  title: string;
  description: string;
  pages: ReviewPage[];
};

const reviewGroups: ReviewGroup[] = [
  {
    title: "入口与命卷",
    description: "登录、加载与已有命卷入口。",
    pages: [
      { id: "login", title: "登录 / 注册", description: "邮箱登录、注册及表单校验。", state: "已审" },
      { id: "loading", title: "启动加载", description: "读取账户与命卷时的等待状态。" },
      { id: "saves", title: "命卷列表", description: "进入、创建与管理已有命卷。", state: "已审" },
    ],
  },
  {
    title: "创建命卷",
    description: "同一流程的四个关键审稿位置。",
    pages: [
      { id: "create", title: "一 · 定其身", description: "姓名、族属与基本身份。", state: "已审" },
      { id: "create-route", title: "二 · 择其途", description: "选择人物来路与开篇方向。", state: "已审" },
      { id: "create-review", title: "三 · 观命卷", description: "创建前的事实确认与总览。", state: "已审" },
      { id: "create-loading", title: "命卷生成中", description: "提交创建后的等待覆盖层。" },
    ],
  },
  {
    title: "故事与资料",
    description: "进入命卷后，通过阅读、决定与回顾继续这一段故事。",
    pages: [
      { id: "adventure", title: "故事", description: "本回正文、推荐选择与自由决定。", state: "调整中" },
      { id: "character", title: "人物", description: "主角、已登场人物、能力与随身物。" },
      { id: "chronicle", title: "前情", description: "已经确认的重要经历与时间记录。" },
      { id: "settings", title: "设置", description: "阅读、续写服务、命卷与账号设置。" },
    ],
  },
  {
    title: "关键状态与浮层",
    description: "不易在正常流程中稳定复现的界面状态。",
    pages: [
      { id: "pending", title: "行动提交中", description: "已选行动与回合生成等待状态。" },
      { id: "error", title: "行动失败", description: "保留选择并允许恢复重试的错误状态。" },
      { id: "death", title: "命卷终章", description: "角色死亡后的纪事与后续操作。" },
      { id: "dialog", title: "版本公告", description: "覆盖在当前页面上的更新说明。" },
    ],
  },
];

const pageCount = reviewGroups.reduce((count, group) => count + group.pages.length, 0);

function previewHref(id: string) {
  return `?design=${encodeURIComponent(id)}`;
}

export function DesignReviewHub() {
  return (
    <div className="design-review-hub">
      <div className="design-review-paper" aria-hidden="true" />
      <header className="design-review-header">
        <div className="design-review-heading">
          <span className="design-review-kicker">WENXIAN · LOCAL REVIEW</span>
          <h1>问仙录 · 页面审稿</h1>
          <p>需要像真实服务一样连续操作时，从登录开始进入本地演示；下方独立入口用于快速检查指定页面状态。</p>
          <a className="design-review-demo-link" href="?demo=reset">
            从登录开始连续体验
            <ArrowRightIcon aria-hidden="true" />
          </a>
        </div>
        <div className="design-review-count" aria-label={`共 ${pageCount} 个可交互入口`}>
          <strong>{pageCount}</strong>
          <span>个可交互入口</span>
        </div>
      </header>

      <main className="design-review-groups">
        {reviewGroups.map((group, groupIndex) => (
          <section className="design-review-group" key={group.title}>
            <div className="design-review-group-heading">
              <span>{String(groupIndex + 1).padStart(2, "0")}</span>
              <div>
                <h2>{group.title}</h2>
                <p>{group.description}</p>
              </div>
            </div>
            <div className="design-review-grid">
              {group.pages.map((page) => (
                <article className="design-review-card" key={page.id}>
                  <div className="design-review-card-copy">
                    <div className="design-review-card-title">
                      <h3>{page.title}</h3>
                      {page.state && <span>{page.state}</span>}
                    </div>
                    <p>{page.description}</p>
                    <code>?design={page.id}</code>
                  </div>
                  <div className="design-review-actions">
                    <a href={previewHref(page.id)}>
                      当前页进入
                      <ArrowRightIcon aria-hidden="true" />
                    </a>
                    <a href={previewHref(page.id)} rel="noreferrer" target="_blank">
                      新标签打开
                      <ExternalLinkIcon aria-hidden="true" />
                    </a>
                  </div>
                </article>
              ))}
            </div>
          </section>
        ))}
      </main>

      <footer className="design-review-footer">
        <p>仅在本地开发环境可用，不进入正式玩家流程。</p>
        <a href="?design=adventure">返回当前故事页</a>
      </footer>
    </div>
  );
}
