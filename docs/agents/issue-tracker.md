# 问题跟踪器：GitHub

本仓库的工单与规格存放在 `TAKETODAY/maven-central-publishing-gradle-plugin` 的 GitHub Issues。
所有操作使用 `gh` CLI，在仓库内执行时由 Git 远端推断目标仓库。

## 常用操作

- 创建：`gh issue create --title "..." --body "..."`；多行正文可使用 `--body-file`。
- 读取：`gh issue view <number> --json number,title,body,labels,comments`。
- 列表：`gh issue list --state open --json number,title,body,labels,comments`；
  按需使用 `--label` 和 `--state`。
- 评论：`gh issue comment <number> --body "..."`。
- 添加或移除标签：`gh issue edit <number> --add-label "..."` 或 `--remove-label "..."`。
- 关闭：`gh issue close <number> --comment "..."`。

技能要求“发布到问题跟踪器”时，创建 GitHub Issue；
要求“获取相关工单”时，读取对应 Issue。

## Pull requests as a triage surface

**PRs as a request surface: no.**

## 父子工单与依赖

- 支持时使用 GitHub 原生子工单。
  `gh` 2.94+ 可使用 `gh issue create --parent <parent> ...`，
  或 `gh issue edit <parent> --add-sub-issue <child>`。
- 旧版 CLI 可调用：
  `gh api --method POST repos/TAKETODAY/maven-central-publishing-gradle-plugin/issues/<parent>/sub_issues -F sub_issue_id=<child-db-id>`。
- 无法使用子工单时，在父工单中维护任务列表，并在子工单正文顶部写 `Part of #<parent>`。
- 阻塞关系优先使用 GitHub 原生依赖：
  `gh api --method POST repos/TAKETODAY/maven-central-publishing-gradle-plugin/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`。
- 数据库 ID 通过
  `gh api repos/TAKETODAY/maven-central-publishing-gradle-plugin/issues/<number> --jq .id`
  获取；它不是工单编号或 `node_id`。
- 无法使用原生依赖时，在子工单正文顶部写 `Blocked by: #<number>`。
  所有阻塞工单关闭后，该工单才可执行。

## Wayfinder 约定

- 地图：使用一个带 `wayfinder:map` 标签的 Issue，维护笔记、已确定决策和待解问题。
- 子任务：关联到地图，使用 `wayfinder:<type>` 标签；
  type 为 `research`、`prototype`、`grilling` 或 `task`。
- 可执行前沿：按地图顺序选择未关闭、无未完成阻塞项且无人认领的子任务。
  原生依赖的未完成阻塞数由 `issue_dependencies_summary.blocked_by` 提供；
  文本回退则逐一检查阻塞工单。
- 认领：首次写操作为 `gh issue edit <number> --add-assignee @me`。
- 解决：先评论结论，再关闭工单，最后将摘要和链接补充到地图的已确定决策中。
