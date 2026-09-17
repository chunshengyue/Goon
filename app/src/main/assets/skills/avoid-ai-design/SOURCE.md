# 来源

- 上游：https://github.com/funboy322/avoid-ai-design
- 版本：`8337060636a8`（2026-06-18）
- 许可：MIT（见同目录 LICENSE，版权归 ungspirit）
- 引入方式：**原样复制，未做任何改写**。`SKILL.md` 是常驻可读部分；
  `references/ai-tells-catalog.md`（约 60 条，带 P0/P1/P2 分级）与
  `references/aesthetic-directions.md`（7 个可选方向）按需通过 `read_skill` 的 `file` 参数读取。

平台差异（不改原文，只在此说明）：本技能面向 HTML/CSS 与 React+Tailwind+shadcn，并且假设可以渲染页面后再判断
视觉类问题（原文自己标注了哪些条目 `needs render`）。本项目恰好有渲染管线：`run_preview` 会按设备分辨率出图
并做审计，可以按原文的"先渲染再判断"来做。Tailwind 相关的类名规则在本项目里要按等价的原生 CSS 去理解。
