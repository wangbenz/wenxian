import { ApiError } from "../game-api";

export type UiProblem = {
  code?: string;
  category: string;
  title: string;
  message: string;
  suggestions: string[];
  retryable: boolean;
  turnConsumed: boolean;
  stateChanged: boolean;
  statePending?: boolean;
  referenceId?: string;
};

export function problemFrom(error: unknown): UiProblem {
  if (!(error instanceof ApiError)) return {
    category: "SYSTEM",
    title: "命卷暂时无法响应",
    message: "本次操作没有完成，请稍后重试。",
    suggestions: ["稍后重新尝试"],
    retryable: true,
    turnConsumed: false,
    stateChanged: false,
  };
  const technical = /DeepSeek|Agent|Java|API|后端|模型/i.test(error.message);
  const system = [500, 502, 503, 504].includes(error.status) || error.category === "SYSTEM";
  return {
    code: error.code,
    category: error.category ?? (system ? "SYSTEM" : "INPUT"),
    title: error.title ?? (system ? "本次推演未能完成" : "本次操作未能完成"),
    message: technical ? "本次操作没有完成，命卷没有发生变化。" : error.message,
    suggestions: error.suggestions.length ? error.suggestions : system ? ["稍后重新尝试"] : [],
    retryable: error.retryable || [502, 503, 504].includes(error.status),
    turnConsumed: error.turnConsumed,
    stateChanged: error.stateChanged,
    statePending: error.details.statePending === true,
    referenceId: error.referenceId,
  };
}

export function ProblemNotice({ problem, compact = false, context = "default" }: { problem: UiProblem; compact?: boolean; context?: "default" | "story" }) {
  const storyProblem = context === "story";
  return <section className={`${compact ? "problem-notice compact" : "problem-notice"}${storyProblem ? " story-problem-notice" : ""}`} data-category={problem.category} role="alert">
    <p className="eyebrow">{storyProblem ? "续写未完成" : problem.category === "SYSTEM" ? "命卷推演中断" : "本次操作未完成"}</p>
    <h3>{problem.title}</h3>
    <p>{problem.message}</p>
    {!compact && <p className="problem-state">{problem.statePending
      ? "结果仍在确认 · 不会重复提交本次行动"
      : `${problem.turnConsumed ? "本轮已计入回合" : "未消耗回合"} · ${problem.stateChanged ? "部分变化已经记录" : storyProblem ? "命卷没有变化" : "存档没有变化"}`}</p>}
    {problem.suggestions.length > 0 && <ul>{problem.suggestions.map((suggestion) => <li key={suggestion}>{suggestion}</li>)}</ul>}
    {problem.referenceId && <small>问题编号：<strong>{problem.referenceId}</strong></small>}
  </section>;
}
