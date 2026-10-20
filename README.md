# x-server
用于学习和实践的游戏服务端项目，使用 Codex 与 Claude Code 协同开发。

## 协作与文档入口

公共开发约定统一维护在 [AGENTS.md](./AGENTS.md)，[CLAUDE.md](./CLAUDE.md) 通过导入复用。
开发工具选择、IDEA、调试与终端使用见 [开发工具使用指南](./docs/development-tools.md)。
共享设计位于 `.superpowers/specs/`，实现计划位于 `.superpowers/plans/`，两种工具共同维护；有效项目文档随相关代码一同提交、合并。

## 当前进度

见 [PROGRESS.md](./PROGRESS.md)：已实现里程碑、当前工作、验证记录及下一步的统一入口。
架构与设计见 [架构总纲](./.superpowers/specs/00-architecture-overview.md)。
首个业务闭环的接口、启动和验收步骤见 [业务闭环指南](./docs/first-business-loop.md)。

## 开发环境
见 [DEVELOPMENT.md](./DEVELOPMENT.md)（仅服务端；Windows 原生安装，技术栈版本钉定于架构 spec §3.1）。
