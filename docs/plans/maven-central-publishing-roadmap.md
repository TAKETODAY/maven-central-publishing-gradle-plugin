# Maven Central Publishing 功能路线图

## 1. 文档目的

本文规划发布生命周期管理以外的后续功能，目标是让插件在发布前、发布中和发布后都更容易使用、更容易诊断，并适合接入 CI。

当前插件已经支持：

- 正式版本上传到 Maven Central Portal；
- `AUTOMATIC` 和 `USER_MANAGED` 发布模式；
- ZIP / TAR.GZ bundle；
- 根项目聚合多模块 publication；
- Snapshot 版本直接发布到 Maven Central Snapshots 仓库；
- deployment 状态轮询和 deployment ID 文件输出。

本路线图不改变这些基础能力，而是在其上补充校验、选择、报告和集成能力。

## 2. 总体目标

后续功能围绕四个问题展开：

1. **发布前能否尽早发现问题？**
2. **发布失败时能否快速定位原因？**
3. **多模块项目能否精确控制发布内容？**
4. **CI 能否获得稳定、结构化的结果？**

推荐原则：先做本地、确定性强、不会改变远端状态的功能，再做可能改变发布行为的功能。

## 3. 功能优先级

| 功能 | 主要收益 | 远端副作用 | 建议版本 |
|---|---|---:|---:|
| 发布前校验 | 减少 Central 校验失败 | 无 | 1.1 |
| 结构化错误诊断 | 缩短排障时间 | 无 | 1.1 |
| publication / 模块筛选 | 支持大型多模块仓库 | 有，改变发布范围 | 1.2 |
| 发布结果报告 | 方便 CI 和审计 | 无 | 1.1 |
| 凭据和签名体验优化 | 降低配置门槛 | 无 | 1.2 |
| CI 集成辅助 | 提升自动化体验 | 无 | 1.2 |
| bundle 可复现性和校验 | 提高供应链可信度 | 无 | 1.3 |
| 发布前后扩展点 | 支持团队定制 | 取决于扩展 | 1.3 |

## 4. 发布前校验

### 4.1 目标

新增一个不上传、不访问 Maven Central 的本地任务：

```shell
./gradlew validateMavenCentralPublication
```

任务检查最终进入 bundle 的 publication 是否满足 Maven Central 的基本要求。

### 4.2 校验内容

- group、artifact 和 version 是否存在且格式有效；
- 正式版本是否意外包含 `-SNAPSHOT`；
- POM 是否包含名称、描述、项目 URL、许可证、开发者和 SCM 信息；
- 是否存在 sources 和 javadoc 配套 artifact；
- publication 是否包含签名文件；
- 多模块是否存在重复坐标；
- bundle 内是否存在 Maven metadata 或不应上传的临时文件；
- 发布范围是否为空；
- `USER_MANAGED` 和 `AUTOMATIC` 配置是否与当前版本类型匹配。

### 4.3 设计要求

- 默认只报告错误和警告，不修改 publication；
- 错误导致任务失败，警告不影响任务成功；
- 提供 `--info` 或专门报告选项输出每个 publication 的检查结果；
- 支持按规则关闭单项检查，但关闭行为必须显式配置；
- 正式发布任务可以依赖该校验任务，但 Snapshot 发布不应强制套用不适用的正式版规则。

## 5. 结构化错误诊断

### 5.1 目标

将当前较粗粒度的 HTTP 错误转换为用户可执行的诊断信息。

错误至少应区分：

- 凭据缺失或无效；
- 权限不足；
- bundle 内容校验失败；
- deployment 状态失败；
- 网络连接失败；
- 服务端限流；
- 服务端临时错误；
- 请求超时但远端状态不确定。

### 5.2 错误信息格式

建议统一包含：

```text
错误类别：BUNDLE_VALIDATION
操作：upload
deployment：<如果已知>
原因：<安全的服务端消息>
建议：运行 ./gradlew mavenCentralDeploymentStatus 或检查 publication 配置
```

禁止输出：

- 密码或令牌；
- Authorization 请求头；
- 完整的带敏感参数 URL；
- 未经脱敏的服务端响应全文。

### 5.3 重试策略

- 网络错误、连接重置和限流可以有限重试；
- 凭据错误、参数错误和 bundle 校验失败不应重试；
- 上传超时后禁止自动重新上传；
- 重试次数和退避时间应可配置，并在日志中说明实际重试原因。

## 6. publication 和模块筛选

### 6.1 使用场景

大型仓库常见以下需求：

- 只发布某个子模块；
- 排除示例模块和测试工具；
- 同一模块拥有多个 publication，但只发布其中一个；
- 在 CI 中根据变更范围选择发布内容。

### 6.2 配置草案

```groovy
mavenCentral {
  includeProjects = [':core', ':http']
  excludeProjects = [':examples']
  includePublications = ['mavenJava']
}
```

具体配置名待 API 设计时确定。应优先使用 Gradle Provider、路径和 publication 名称，而不是依赖文件路径匹配。

### 6.3 约束

- include 和 exclude 同时命中时，行为必须明确并有配置错误提示；
- 筛选后没有 publication 时必须失败；
- 默认行为保持“发布所有已发现 publication”；
- staging repository 和 bundle 中的内容必须与筛选结果一致；
- Snapshot 和正式版本使用同一套筛选规则，避免发布范围不一致。

## 7. 发布结果报告

### 7.1 目标

发布任务结束后生成稳定的结果文件，供 CI、构建扫描和审计系统使用。

建议文件：

```text
build/maven-central-publishing/deployment-result.json
```

### 7.2 建议字段

```json
{
  "deploymentId": "...",
  "deploymentName": "group:name:version",
  "version": "1.1.0",
  "publishingType": "AUTOMATIC",
  "status": "PUBLISHED",
  "bundle": "bundle.zip",
  "startedAt": "...",
  "completedAt": "...",
  "publications": [
    {
      "project": ":core",
      "name": "mavenJava",
      "group": "...",
      "artifact": "...",
      "version": "..."
    }
  ]
}
```

实际字段应保持向后兼容，未知字段允许后续增加。报告不能包含凭据。

### 7.3 CI 输出

除 JSON 文件外，任务还应输出简洁摘要：

- deployment ID；
- 最终状态；
- bundle 文件；
- publication 数量；
- 失败模块和错误类别；
- 状态查询或恢复命令。

## 8. 凭据、签名和安全体验

### 8.1 凭据来源

保持当前来源优先级，同时补充明确文档：

1. 显式 `mavenCentral { credentials { ... } }`；
2. Gradle 项目属性；
3. `ORG_GRADLE_PROJECT_*` 环境变量；
4. 用户级 Gradle 配置。

插件应提供一个非敏感的诊断任务，说明当前使用了哪种凭据来源，但不输出凭据值。

### 8.2 签名支持

后续可以提供针对 Maven Central 常见签名方式的配置辅助：

- ASCII armored key；
- key ID 和 signing password；
- CI 环境变量映射；
- 签名文件存在性和可读取性校验。

签名本身应继续交给 Gradle Signing Plugin，Maven Central 插件只负责校验和文档整合，不重复实现密码学逻辑。

### 8.3 安全要求

- 不把凭据声明为可缓存输入；
- 不把凭据写入结果报告、build scan 或普通日志；
- 错误消息中的 URL 和服务端响应需要脱敏；
- 发布前校验尽量在没有凭据的情况下完成。

## 9. CI 集成辅助

### 9.1 目标

让插件在 GitHub Actions、GitLab CI 和普通 Gradle CI 中都能稳定使用，而不绑定某一个 CI 平台。

### 9.2 建议能力

- 稳定的任务退出码；
- 稳定的状态和错误类别；
- JSON 结果文件；
- deployment ID 输出到环境变量的文档示例；
- 支持只构建 bundle、不上传；
- 支持上传后单独等待状态；
- 支持将 bundle 作为 CI artifact 保存。

建议使用如下拆分：

```shell
./gradlew mavenCentralBundle
./gradlew uploadMavenCentralBundle
./gradlew mavenCentralDeploymentStatus
```

每一步都应有清晰的输入和输出，便于 CI 在步骤之间传递文件和 deployment ID。

## 10. bundle 可复现性和供应链能力

### 10.1 可复现 bundle

检查并统一归档行为：

- 文件排序；
- 时间戳处理；
- 文件权限；
- ZIP / TAR.GZ 元数据；
- 归档文件名和目录结构。

同一组 publication 在相同输入下应尽量生成稳定的 bundle，方便缓存、审计和校验。

### 10.2 校验和

可选生成：

```text
bundle.zip.sha256
```

校验和应在 bundle 完成后生成，并在上传日志和结果报告中引用。校验和不能代替 Maven Central 的签名和服务端校验。

### 10.3 审计信息

结果报告可以记录：

- Gradle 版本；
- JDK 版本；
- 插件版本；
- bundle SHA-256；
- publication 坐标；
- deployment ID。

这些信息不应包含源码内容、凭据或完整环境变量。

## 11. 扩展点

在核心功能稳定后，可以考虑增加受控扩展点：

- bundle 生成前校验回调；
- bundle 生成后校验回调；
- 上传前结果报告回调；
- deployment 状态变化通知；
- 自定义 publisher URL。

扩展点必须满足：

- 不破坏配置缓存；
- 不阻塞默认任务的惰性配置；
- 明确异常传播行为；
- 不把敏感凭据暴露给不必要的回调；
- 公共 API 有完整 JavaDoc 和兼容性测试。

## 12. 分阶段路线

### 1.1：可靠性和诊断

- 发布前基础校验；
- 结构化错误类别；
- 有限退避重试；
- JSON 发布结果报告；
- README 和 CI 示例更新。

### 1.2：大型项目和 CI 体验

- 模块和 publication 筛选；
- 独立 bundle / upload / wait 任务说明；
- 凭据来源诊断；
- 签名配置校验；
- 稳定退出码和 CI 摘要。

### 1.3：供应链和扩展能力

- 可复现 bundle；
- SHA-256 校验和；
- 审计元数据；
- 受控扩展点；
- 更完整的发布前后生命周期钩子。

## 13. 测试计划

### 单元测试

- publication 坐标和 POM 校验；
- 缺失 sources / javadoc / 签名时的错误与警告；
- include / exclude 规则；
- 错误类别映射；
- 重试和退避边界；
- 结果 JSON 序列化；
- 凭据和敏感信息脱敏。

### 集成测试

- 多模块筛选后 staging repository 内容正确；
- 空筛选结果失败；
- `mavenCentralBundle` 可独立运行；
- 结果报告在成功、失败、超时场景下都生成；
- 配置缓存不会造成凭据或 publication 信息丢失；
- Snapshot 发布不受正式版校验规则错误影响。

### 回归验证

每次变更都应运行：

```shell
./gradlew check --no-daemon --console=plain
```

同时验证现有的 `publishToMavenCentral`、`USER_MANAGED`、多模块和 Snapshot 使用方式。

## 14. 决策结论

推荐优先实现以下最小闭环：

```text
发布前校验
    ↓
结构化错误诊断
    ↓
结果 JSON 报告
    ↓
CI 示例与文档
```

这组功能基本不改变远端发布语义，却能直接降低 Maven Central 发布失败的排查成本。模块筛选、签名辅助和供应链能力应在基础诊断稳定后再加入，避免一次引入过多配置和公开 API。
