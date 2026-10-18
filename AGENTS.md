# Maven Central Publishing Gradle Plugin

## 构建与测试

- 使用仓库自带的 Gradle Wrapper（Gradle 9.8.1），不要依赖系统 Gradle；源码目标为 Java 17。
- 常用验证：`./gradlew check --no-daemon --console=plain`。CI 使用 `./gradlew -DCI=true --no-daemon --no-parallel check --scan`。
- 运行单个测试类：`./gradlew test --tests cn.taketoday.publishing.MavenCentralPublisherAPITests --no-daemon --console=plain`；测试使用 JUnit Platform 和 AssertJ。
- `settings.gradle` 通过 `includeBuild('publishing-bootstrap')` 加载本地引导插件；引导构建从 `../src/main/java` 编译同一份主源码。修改插件主代码后，优先用根项目 Wrapper 验证，避免只验证引导子构建。

## 代码边界

- 插件入口是 `cn.taketoday.publishing.MavenCentralPublishingPlugin`，插件 ID 为 `cn.taketoday.maven-central-publishing`。
- `src/main/java` 实现发布插件、Maven Central 上传任务和 HTTP API；`src/test/java` 目前主要覆盖 Publisher API 的 HTTP 行为。
- 插件先发布到本地 `build/maven-central-publishing/staging-repository`，再打包为 `build/maven-central-publishing/bundle.zip`，最终由 `uploadMavenCentralBundle` 上传；相关任务由 `publishToMavenCentral` 汇总。

## 发布

- Maven Central 发布需要 `mavenCentralUsername`、`mavenCentralPassword`；可通过 `-P`、`ORG_GRADLE_PROJECT_*` 或 `~/.gradle/gradle.properties` 提供，凭据不要写入仓库。
- 发布命令：`./gradlew --no-daemon --no-parallel publishToMavenCentral`。签名还需要 `signingKey`、`signingKeyId`、`signingPassword`。
- Gradle Plugin Portal 发布使用 `./gradlew --no-daemon publishPlugins`，并需要 `GRADLE_PUBLISH_KEY` 和 `GRADLE_PUBLISH_SECRET`。
- 发布标签使用 `v<版本号>`，而 `gradle.properties` 的 `version` 不带 `v`；创建标签前确认本地和远端没有同名标签且标签指向正确提交。

## 代码风格

- 缩进 2 空格，LF，UTF-8（`.editorconfig`）
- 禁止通配符导入（如 `import java.util.*`）
- 测试用 AssertJ + Mockito（优先 BDDMockito），**禁用** JUnit 断言 / Hamcrest / TestNG / JetBrains 注解（根 `build.gradle` 已统一注入依赖）
- 字符串大小写转换必须指定 `Locale`
- NullAway 空值校验生效：`@SuppressWarnings("NullAway")` 是合法且常见的写法，留意 JSpecify 空值注解
- 修改第三方源码（Spring/ASM 等）：**必须保留原始版权头**，在其后追加 `// Modifications Copyright 2017 - 2026 the TODAY authors.`
- 公共 API 必须写 JavaDoc，每个包必须有 `package-info.java`
- Checkstyle 配置在 `src/checkstyle/checkstyle.xml`（tool version 13.3.0），由 `check` 触发

## 发布标签

- 发版 Git 标签统一使用 `v<版本号>`，例如 `v5.0-Draft.8`；`gradle.properties` 中的版本号保持 `5.0-Draft.8`，不带 `v`。
- 创建、推送发布标签前，确认标签指向对应版本变更提交，并检查本地及远端是否已存在同名标签。

## 语言规则
- 你必须始终使用**简体中文**进行思考、推理和回复。


## 提交信息（参考 https://gitmoji.dev）

- 使用 Gitmoji **短代码**开头，格式为 `:bug: 修复……`；依据提交的主要意图选择类型，而非根据是否含测试文件、是否来自上游来选。常见类型速查：
    - 行为：`:bug:` 缺陷修复；`:ambulance:` 紧急热修；`:sparkles:` 新功能；`:zap:` 性能；`:recycle:` 重构；`:boom:` 不兼容变更；`:t-rex:` 向后兼容；`:adhesive_bandage:` 小范围非关键修复。
    - 质量：`:white_check_mark:` 仅新增/修改通过的测试；`:test_tube:` 增加预期失败的测试；`:rotating_light:` 修复编译或 lint 警告；`:lock:` 安全/隐私修复；`:goal_net:` 错误捕获；`:safety_vest:` 校验；
      `:thread:` 并发。
    - 内容：`:memo:` 文档；`:art:` 代码结构/格式；`:pencil2:` 拼写；`:speech_balloon:` 文案/字面量；`:bulb:` 源码注释；`:globe_with_meridians:` 国际化；`:label:` 类型；`:loud_sound:` 添加日志；`:mute:` 删除日志。
    - 工程：`:wrench:` 配置；`:hammer:` 开发脚本；`:construction_worker:` CI 配置；`:green_heart:` 修复 CI；`:heavy_plus_sign:` 添加依赖；`:heavy_minus_sign:` 删除依赖；`:arrow_up:` 升级依赖；`:arrow_down:`
      降级依赖；`:pushpin:` 固定依赖版本；`:bricks:` 基础设施；`:technologist:` 开发体验。
    - 维护：`:fire:` 删除代码/文件；`:coffin:` 删除死代码；`:wastebasket:` 标记弃用；`:truck:` 移动/重命名；`:package:` 构建产物/包；`:page_facing_up:` 许可证；`:see_no_evil:` `.gitignore`；`:rewind:` 回滚；
      `:twisted_rightwards_arrows:` 合并。
    - 特定领域按实际变更选择：`:alien:` 外部 API 变更适配；`:building_construction:` 架构；`:necktie:` 业务逻辑；`:card_file_box:` 数据库；`:passport_control:` 授权；`:wheelchair:` 无障碍；
      `:children_crossing:` 易用性；`:iphone:` 响应式设计；`:airplane:` 离线；`:triangular_flag_on_post:` 特性开关；`:stethoscope:` 健康检查；`:chart_with_upwards_trend:` 分析/跟踪；`:mag:` SEO；`:seedling:`
      种子数据。
    - 其他场景：`:rocket:` 部署；`:bookmark:` 版本标签；`:tada:` 项目初始化；`:construction:` 进行中；`:lipstick:` UI 样式；`:bento:` 素材；`:camera_flash:` 快照；`:clown_face:` mock；`:egg:` 彩蛋；`:alembic:`
      实验；`:monocle_face:` 数据探索；`:busts_in_silhouette:` 贡献者；`:money_with_wings:` 赞助设施；`:dizzy:` 动画；`:beers:` / `:poop:` 属于非正式语义，不用于正常移植提交。
- 中文主题用一个动宾短句说清 **实际行为变化**，避免“同步上游”“迁移测试”之类过程描述。正文只在需要说明触发条件、修复机制、兼容取舍或上游溯源时补充，避免重复主题；不要写“逐项迁移上游
  BeanWrapperAutoGrowingTests 的……；infra-beans 对应测试全类通过”一类测试报告。
- 若上游原测试或文档与代码一同提交，以生产行为为主题；仅测试或仅文档的独立提交才分别用 `:white_check_mark:` 或 `:memo:`。测试映射、运行命令及结果集中写入批末归档，不塞进每个提交正文。代码、原测试及适用中文文档优先放在同一个目标提交，不人为拆分凑数。
- 提交信息可以按照上游的来，但是要保证中文

## Agent skills

### Issue tracker

工单与规格使用 `TAKETODAY/maven-central-publishing-gradle-plugin` 的 GitHub Issues，通过 `gh` CLI 操作。详见 `docs/agents/issue-tracker.md`。

### Triage labels

使用五个默认分诊标签，角色名称与标签字符串一致。详见 `docs/agents/triage-labels.md`。

### Domain docs

采用 single-context 布局：根目录 `GLOSSARY.md` 与 `docs/adr/`。详见 `docs/agents/domain.md`。
