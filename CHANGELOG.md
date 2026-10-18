# Changelog

本文件记录本项目的重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [1.0.1] - 2026-10-18

### 新增

- 快照发布支持 Gradle 配置缓存。`-SNAPSHOT` 版本通过 `publishToMavenCentral` 发布时可以写入并复用配置缓存。

### 变更

- **不兼容**：快照发布仓库和任务名统一为 `mavenCentral`，移除 `mavenCentralSnapshots` 命名：
  - `publishAllPublicationsToMavenCentralSnapshotsRepository` → `publishAllPublicationsToMavenCentralRepository`
  - `publish<Publication>PublicationToMavenCentralSnapshotsRepository` → `publish<Publication>PublicationToMavenCentralRepository`
  - `MavenCentralPublishingPlugin.SNAPSHOT_REPOSITORY_NAME` 的值由 `mavenCentralSnapshots` 改为 `mavenCentral`。
- 快照发布复用 `mavenCentralUsername` / `mavenCentralPassword`。使用默认 Gradle 凭据时，配置缓存只保存 `mavenCentral` 凭据身份，不保存凭据值。
- 通过 `mavenCentral { credentials { ... } }` 显式设置凭据时，Gradle 会按标准行为禁用发布任务的配置缓存。
- `--dry-run` 不要求提供发布凭据；真实发布时按 `mavenCentral` 凭据身份解析凭据。

### 修复

- 修复开启配置缓存后快照发布失败并报 `The value of this property has been discarded during serialization` 的问题。
- 避免在快照发布任务执行阶段访问已被配置缓存丢弃的 `PublishToMavenRepository.repository`。

### 文档

- 补充发布插件公共常量的 Javadoc。
- 更新快照发布、保留仓库名称和配置缓存相关文档。

## [1.0] - 2026-10-18

### 新增

- 首个稳定版本。
- 正式版本通过本地 staging 仓库、ZIP/TAR.GZ bundle 和 Maven Central Portal Publisher API 发布。
- 支持多模块 publication 聚合、发布状态轮询与失败重试。
- 支持 `-SNAPSHOT` 版本直接发布到 Maven Central Snapshots。
- 提供 `mavenCentral {}` DSL，用于配置 bundle、凭据、发布类型、超时和重试策略。
- 支持 Gradle 配置缓存下的 bundle 创建与上传依赖图。

[1.0.1]: https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/compare/v1.0...v1.0.1
[1.0]: https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/releases/tag/v1.0
