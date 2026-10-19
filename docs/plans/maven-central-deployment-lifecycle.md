# Maven Central Deployment 生命周期管理计划

## 1. 背景

当前插件通过 `publishToMavenCentral` 完成以下流程：

```text
Gradle publications
    ↓
本地 staging repository
    ↓
bundle.zip / bundle.tar.gz
    ↓
Maven Central Publisher API
    ↓
轮询 deployment 状态
```

插件已经支持 `AUTOMATIC` 和 `USER_MANAGED` 两种发布模式，并会将
deployment ID 写入 `build/maven-central-publishing/deployment-id.txt`。

但是，在上传后 CI 中断、网络请求超时或需要人工审批时，用户缺少一组独立的恢复工具：

- 无法只根据 deployment ID 查询远端状态；
- 无法在新的 Gradle 进程中恢复轮询；
- 无法明确执行后续的发布或丢弃操作；
- 上传请求结果不明确时，容易误以为上传失败并重复上传。

## 2. 目标

本计划的目标是让一次 Maven Central deployment 具备可恢复的生命周期管理能力：

1. 上传成功后始终可靠地保存并输出 deployment ID；
2. 支持脱离原始上传任务查询 deployment 状态；
3. 支持 CI 中断后的状态恢复和继续等待；
4. 为 `USER_MANAGED` 模式提供显式的发布和丢弃入口；
5. 对超时、重试和重复执行提供清晰且安全的行为；
6. 保持现有 `publishToMavenCentral` 的默认用法和配置兼容。

## 3. 非目标

本计划不包含以下内容：

- 在插件中实现完整的审批系统、审批人和审批策略；
- 替代 GitHub Actions、Jenkins 等 CI/CD 平台的发布编排；
- 修改 Maven Central Snapshot 发布流程；
- 自动重传可能已经成功接收的 bundle；
- 破坏现有 `MavenCentralExtension` 配置或任务名称；
- 为正式发布引入额外的强制交互确认。

## 4. 设计原则

### 4.1 默认流程保持简单

已有用户继续执行：

```shell
./gradlew publishToMavenCentral
```

默认任务的行为不变。生命周期管理任务只作为显式的高级能力提供。

### 4.2 查询优先于重试上传

当上传请求超时或连接中断时，插件不能直接重新上传。应优先使用已知 deployment ID 查询远端状态；如果 deployment ID 未知，则必须报告状态不确定并让用户决定后续操作。

### 4.3 任务必须可用于 CI

任务应支持通过 Gradle 属性传递 deployment ID，也应能自动读取当前项目默认的 deployment ID 文件。所有重要结果都应输出适合 CI 日志和脚本处理的信息。

### 4.4 状态转换必须显式

客户端应区分以下概念：

- 上传：创建 deployment；
- 查询：读取 deployment 状态；
- 发布：将已验证 deployment 正式发布；
- 丢弃：放弃未发布 deployment。

查询任务不得产生状态变更，发布和丢弃任务不得被默认查询任务隐式调用。

## 5. 分阶段实施

### 阶段一：部署状态查询与恢复

**目标：低风险地解决 CI 中断和网络超时后的恢复问题。**

新增任务：

```shell
./gradlew mavenCentralDeploymentStatus
./gradlew mavenCentralDeploymentStatus -PdeploymentId=<deployment-id>
```

建议行为：

1. 优先使用 `-PdeploymentId`；未提供时读取 `deployment-id.txt`；
2. 调用 Maven Central Publisher API 查询状态；
3. 输出 deployment ID、状态、错误信息和远端消息；
4. 对非终态支持按配置轮询；
5. 查询失败时只报告失败，不重新上传；
6. 将最终状态以稳定格式输出，便于 CI 识别。

建议新增配置：

```groovy
mavenCentral {
  statusRequestInterval = Duration.ofSeconds(5)
  maxStatusRetries = 3
  maxDeploymentWait = Duration.ofMinutes(60)
}
```

阶段一应优先复用现有的 `MavenCentralPublisherAPI`、状态解析和等待配置，避免产生两套轮询实现。

### 阶段二：显式发布和丢弃

**目标：支持 `USER_MANAGED` 模式下的人工审批和后续操作。**

新增任务：

```shell
./gradlew publishMavenCentralDeployment
./gradlew publishMavenCentralDeployment -PdeploymentId=<deployment-id>

./gradlew dropMavenCentralDeployment
./gradlew dropMavenCentralDeployment -PdeploymentId=<deployment-id>
```

建议规则：

- 只允许操作明确提供或可读取的 deployment ID；
- 发布前检查 deployment 已达到 Maven Central 允许发布的状态；
- 已发布 deployment 重复执行时返回幂等成功或明确的已完成状态；
- 已丢弃 deployment 不允许再次发布；
- 发布和丢弃任务不得自动选择其他 deployment；
- API 返回非成功状态时，应包含 deployment ID 和远端错误信息。

### 阶段三：CI 集成与结果报告

**目标：让生命周期操作更容易接入自动化流水线。**

可选增强：

- 输出 JSON 格式结果，例如 `build/maven-central-publishing/deployment-result.json`；
- 增加只等待成功或失败的任务模式；
- 提供稳定的退出码映射；
- 在 GitHub Actions 中生成 step summary；
- 记录上传时间、最后查询时间、最终状态和 deployment URL（若 API 提供）。

阶段三应在阶段一、二的 API 和状态模型稳定后实施，避免过早承诺结果文件格式。

## 6. 建议的内部结构

### 6.1 API 客户端

扩展 `MavenCentralPublisherAPI`，将远端操作抽象为独立方法：

```java
String upload(File bundleFile, String deploymentName, PublishingType publishingType);

JSONObject getStatus(String deploymentId);

void publish(String deploymentId);

void drop(String deploymentId);
```

具体 HTTP 方法、路径、请求体和状态枚举必须以 Maven Central Publisher API 的官方文档为准，不应根据任务名称猜测接口行为。

### 6.2 Deployment 标识解析

新增一个内部复用组件，统一处理：

1. 命令行属性；
2. deployment ID 文件；
3. 缺失值错误；
4. 空白字符和非法值校验。

命令行属性优先级高于文件，且最终使用的 deployment ID 必须在日志中明确显示。

### 6.3 任务类型

建议新增三个独立任务类：

- `MavenCentralDeploymentStatusTask`
- `MavenCentralPublishDeploymentTask`
- `MavenCentralDropDeploymentTask`

任务公共逻辑应提取到非任务类中，避免每个任务重复实现凭据读取、ID 解析、HTTP 错误处理和状态格式化。

## 7. 配置与兼容性

### 保持兼容的内容

- `publishToMavenCentral` 任务名称；
- `uploadMavenCentralBundle` 任务名称；
- `MavenCentralExtension` 现有属性；
- `deployment-id.txt` 的默认路径；
- `AUTOMATIC` 和 `USER_MANAGED` 的现有语义；
- Snapshot 发布行为。

### 需要谨慎设计的内容

- deployment ID 属性名统一使用 `deploymentId`；
- 新任务是否默认允许轮询，应通过现有等待配置控制；
- 新任务的凭据错误不能泄露用户名以外的敏感信息；
- 不应把 deployment ID、访问令牌或完整请求头写入 Gradle cache；
- 新增公开任务和常量需要 JavaDoc，并补充 API 兼容性测试。

## 8. 测试计划

### 单元测试

- deployment ID 从项目属性读取；
- deployment ID 从默认文件读取；
- 属性优先于文件；
- 缺少或空 deployment ID 时失败；
- 状态 JSON 中的成功、处理中、失败和未知状态解析；
- 网络异常按重试次数执行；
- 重试耗尽后不会再次执行上传；
- 敏感凭据不会出现在异常消息和日志中。

### HTTP 行为测试

在现有 `MavenCentralPublisherAPITests` 中补充：

- 查询 deployment 状态；
- 发布 deployment；
- 丢弃 deployment；
- 非 2xx 响应处理；
- 空响应和非法 JSON 处理；
- 发布或丢弃请求的 deployment ID 编码。

### Gradle 集成测试

- 新任务能够被插件注册；
- `USER_MANAGED` 流程可以先上传再独立查询；
- 新任务可以在新的 Gradle invocation 中读取 deployment ID 文件；
- Snapshot 项目不会注册或执行正式版本生命周期任务；
- 配置缓存不会保存凭据或导致任务执行期属性丢失。

## 9. 验收标准

阶段一完成的最低标准：


1. 上传成功后 deployment ID 仍写入默认文件，并在日志中输出；
2. 用户可以只凭 deployment ID 查询远端状态；
3. CI 中断后可以通过新一次 Gradle invocation 恢复查询；
4. 查询任务不会重复上传或改变 deployment 状态；
5. 网络超时不会触发盲目重复上传；
6. 现有测试和 `./gradlew check --no-daemon --console=plain` 通过；
7. README 和 CHANGELOG 补充新任务说明。

阶段二完成的最低标准：

1. `USER_MANAGED` deployment 可以显式发布；
2. 未发布 deployment 可以显式丢弃；
3. 重复执行和终态冲突有明确、可测试的行为；
4. 发布或丢弃失败时不会误报成功；
5. 现有自动发布路径行为不变。

## 10. 风险与决策点

### Maven Central API 能力差异

Publisher API 的发布、丢弃操作和状态枚举必须先通过官方文档或真实 API 行为确认。若 API 不提供某项能力，插件不应伪造任务或仅在本地改变状态。

### 幂等性

发布和丢弃都属于状态变更操作。请求超时后不能直接重试，必须先查询状态；无法确认远端状态时应终止并给出恢复指引。

### 任务数量增长

如果阶段一已经能解决绝大多数问题，阶段二可以延后，避免为了少量审批场景增加过多公开任务。

### 版本规划

- 阶段一建议作为 `1.1.0` 的主要能力；
- 阶段二可在 API 和用户需求明确后进入 `1.2.0`；
- 阶段三属于增量增强，不应阻塞前两个阶段。

## 11. 推荐实施顺序

```text
确认 Maven Central API 能力
        ↓
统一 deployment ID 和状态模型
        ↓
新增独立状态查询任务
        ↓
补充单元、HTTP 和集成测试
        ↓
更新 README / CHANGELOG
        ↓
收集真实 CI 使用反馈
        ↓
再决定是否实现发布和丢弃任务
```

最终建议是：**先实现查询与恢复，再根据实际使用反馈决定是否增加发布和丢弃操作。** 这样既能解决当前最现实的可靠性问题，也不会过早扩大插件的职责边界。
