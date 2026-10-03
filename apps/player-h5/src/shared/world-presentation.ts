export const RACE_NAMES: Record<string, string> = {
  HUMAN: "人族",
  DEMON: "妖族",
  SPIRIT: "灵族",
};

const PHASE_NAMES: Record<StoryWorldTime["phase"], string> = {
  DAWN: "清晨", MORNING: "上午", NOON: "正午", AFTERNOON: "午后",
  DUSK: "黄昏", NIGHT: "入夜", LATE_NIGHT: "夜深",
};

export function formatWorldTime(time: StoryWorldTime) {
  return `世界历${time.year}年 · 第${time.day}日 · ${PHASE_NAMES[time.phase]}`;
}

export function formatEventTime(time: StoryWorldTime, currentWorldYear: number) {
  return time.year === currentWorldYear
    ? `第${time.day}日 ${PHASE_NAMES[time.phase]}`
    : `${time.year}年 第${time.day}日`;
}
import type { StoryWorldTime } from "../game-api";
