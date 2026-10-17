# Gradle Publishing Plugin

![Java17](https://img.shields.io/badge/JDK-17+-success.svg)
[![Apache License 2.0](https://img.shields.io/github/license/TAKETODAY/gradle-publishing-plugin?color=blue)](./LICENSE)
[![Release](https://github.com/TAKETODAY/gradle-publishing-plugin/actions/workflows/release.yaml/badge.svg)](https://github.com/TAKETODAY/gradle-publishing-plugin/actions/workflows/release.yaml)
[![CI](https://github.com/TAKETODAY/gradle-publishing-plugin/actions/workflows/ci.yaml/badge.svg)](https://github.com/TAKETODAY/gradle-publishing-plugin/actions/workflows/ci.yaml)

### 发布配置

通过 `mavenCentral` 配置块设置发布选项（此配置块在 0.0.3 之后的版本提供）：

```groovy
import cn.taketoday.publishing.PublishingType
import cn.taketoday.publishing.BundleFormat
import java.time.Duration

mavenCentral {
  bundle {
    format = BundleFormat.ZIP
    archiveFileName = 'bundle.zip'
  }
  // 默认读取 mavenCentralUsername / mavenCentralPassword，也可映射其他属性
  credentials {
    username = providers.gradleProperty('myCentralUsername').get()
    password = providers.gradleProperty('myCentralPassword').get()
  }
  publishingType = PublishingType.AUTOMATIC
  waitUntilFullyPublished = true
  statusRequestInterval = Duration.ofSeconds(3)
  maxStatusRetries = 3
  baseUrl = uri('https://central.sonatype.com/')
  deploymentName = "${group}:${project.name}:${version}"
}
```

| 选项                      | 默认值                          | 说明                                                                             |
|---------------------------|---------------------------------|----------------------------------------------------------------------------------|
| `publishingType`          | `AUTOMATIC`                     | `AUTOMATIC` 自动发布；`USER_MANAGED` 校验后等待手动发布                          |
| `credentials`             | Gradle 凭据 `mavenCentral`      | `PasswordCredentials`，默认读取 `mavenCentralUsername` / `mavenCentralPassword`  |
| `waitUntilFullyPublished` | `false`                         | `true` 时等待 `PUBLISHED`；默认到 `PUBLISHING` 即完成。`USER_MANAGED` 时忽略此项 |
| `statusRequestInterval`   | 1 秒                            | 状态轮询及失败重试间隔；生产环境建议 3～5 秒，至少 1 毫秒                        |
| `maxStatusRetries`        | `3`                             | 状态请求连续失败后的重试次数；生产环境建议 3～5 次，`0` 表示不重试               |
| `baseUrl`                 | `https://central.sonatype.com/` | Publisher API 基础地址                                                           |
| `deploymentName`          | `group:name:version`            | 部署名称                                                                         |

`bundle.format` 支持 `BundleFormat.ZIP`（默认）和 `BundleFormat.TAR_GZ`；
Maven Central 当前支持 ZIP、TAR.GZ 等常见归档格式。`archiveFileName` 应与所选格式一致。

`statusRequestInterval` 同时控制正常状态轮询和失败后的等待时间；
`maxStatusRetries` 只统计连续失败的状态请求，成功响应后计数会清零。
例如间隔 3 秒、重试 3 次时，连续状态接口失败最多等待约 9 秒后终止任务；
正常的 `PENDING`、`VALIDATING`、`PUBLISHING` 状态不会消耗重试次数。

建议：本地调试可使用 1 秒和 1～3 次；CI 或生产发布建议使用 3～5 秒和 3～5 次，
在降低状态接口请求频率的同时覆盖短暂的网络抖动。发布耗时较长时，优先增加轮询间隔，
而不是无限增加重试次数；重试只处理请求失败，不限制部署正常处理的总时长。

所有发布选项统一通过 `mavenCentral {}` 配置，上传任务直接使用该扩展，不再提供独立的任务级发布配置。
凭据默认通过以下 Gradle 项目属性提供；`credentials {}` 可覆盖默认值，也可以将已有的
`PasswordCredentials` 对象或 `Provider<PasswordCredentials>` 设置到 `credentials` 属性。
环境变量可通过 `providers.environmentVariable('MY_CENTRAL_USERNAME').get()` 读取。

#### 发布模式

`publishingType` 是 Sonatype 上传 API 的原生参数：

- `AUTOMATIC`：校验通过后自动发布。
- `USER_MANAGED`：校验通过后进入 `VALIDATED`，由用户在 Portal 或调用发布 API 确认发布。

发布模式统一使用 `publishingType`。旧任务配置 `publish=true` 改为
`publishingType=PublishingType.AUTOMATIC`，`publish=false` 改为 `publishingType=PublishingType.USER_MANAGED`。
`USER_MANAGED` 模式下忽略 `waitUntilFullyPublished`，任务在 `VALIDATED` 时完成。

Sonatype API 省略此参数时默认 `USER_MANAGED`；本插件为兼容现有行为默认自动发布。
参考：[官方 Publisher API 文档](https://central.sonatype.org/publish/publish-portal-api/)。

#### 快照发布（SNAPSHOT）

当（根）项目版本以 `-SNAPSHOT` 结尾时（例如 `1.0.0-SNAPSHOT`），Maven Central 的快照发布
不走 Portal bundle 上传，而是通过兼容 `mvn deploy` 的接口直接部署。插件在项目评估完成后
自动切换发布路径：

- 在根项目与所有应用 `maven-publish` 的子模块上注册保留仓库 `mavenCentralSnapshots`，
  指向 `https://central.sonatype.com/repository/maven-snapshots/`，凭据复用 `mavenCentral`
  配置（Portal 用户令牌，基础认证）；
- 将 `publishToMavenCentral` 与各 `publish<Publication>PublicationToMavenCentral`
  指向对应快照仓库的发布任务，跳过暂存、打包与上传任务链（`mavenCentralStaging`、
  `mavenCentralBundle`、`uploadMavenCentralBundle` 均不会执行）。

前置条件：命名空间需先在 Central Portal 的 [Namespaces](https://central.sonatype.com/publishing/namespaces)
页面启用 SNAPSHOTs（命名空间下拉菜单 → "Enable SNAPSHOTs"）。

`-SNAPSHOT` 构件可被覆盖，且在一段时间后（当前为 90 天）被清理，与正式发布不可覆盖的语义不同。
`mavenCentralSnapshots` 与 `mavenCentralStaging` 一样属于插件保留仓库名，不允许用户自行声明。
参考：[Portal Snapshots 文档](https://central.sonatype.org/publish/publish-portal-snapshots/)。

### Maven Central Portal 凭据

你需要为 Maven Central Portal 创建一个用户令牌：

https://central.sonatype.com/account

将项目属性 `mavenCentralUsername` 和 `mavenCentralPassword` 设置为你的用户令牌信息。

- 通过命令行设置凭据

```shell
./gradlew publishToMavenCentral -PmavenCentralUsername=<用户名> -PmavenCentralPassword=<密码>
```

- 通过环境变量设置凭据

```shell
export ORG_GRADLE_PROJECT_mavenCentralUsername=<令牌用户名>
export ORG_GRADLE_PROJECT_mavenCentralPassword=<令牌密码>
```

- 通过 `~/.gradle/gradle.properties` 设置凭据

```properties
mavenCentralUsername=<令牌用户名>
mavenCentralPassword=<令牌密码>
```

### 多模块聚合发布

在根项目应用插件后（版本 0.0.5 起），会自动聚合所有子模块的发布产物：每个子模块
先发布到统一的 `mavenCentralStaging` 仓库，再由根项目一次性打包上传，
执行一次 `publishToMavenCentral` 即可发布整个构建，无需在根项目手写聚合脚本。

本插件仅允许应用到根项目；应用到子模块会在配置阶段报错。聚合发布需满足两个条件：

1. 应用 `maven-publish` 插件，并声明至少一个 `MavenPublication`（普通 `java` 或
   `java-platform` 模块均可）；
2. 根项目应用本插件。

指向根项目 `build/publishing/staging-repository` 的 `mavenCentralStaging` 仓库由根插件
自动扫描并添加：只要子模块应用了 `maven-publish`，就会被自动纳入
聚合，无需手动配置。

`mavenCentralStaging` 是插件保留仓库，由插件统一创建和配置。
根项目和子模块都不允许自行声明同名仓库，即使指向相同目录也会在配置阶段报错。
快照版本发布时，插件还会保留 `mavenCentralSnapshots` 仓库名，同样禁止自行声明。

聚合在项目评估完成（`projectsEvaluated`）之后进行，因此不依赖子模块的配置顺序，
`java-platform` 这类在根项目配置阶段尚未应用 `maven-publish` 的模块也会被正确纳入。

根项目与子模块的 staging 发布任务均先执行一次共享仓库清理；ZIP/TAR.GZ 打包等待
本次任务图中所有聚合的 staging 发布任务完成，支持使用 `--parallel` 并行构建。
本地打包支持配置缓存，执行 `mavenCentralBundle --configuration-cache` 无需提供上传凭据。

发布入口决定本次暂存的内容：`publishToMavenCentral` 暂存全部 publication（含子模块），
`publish<Publication>PublicationToMavenCentral` 只暂存根项目指定的 publication。
多个单 publication 入口在同一次构建中执行时，它们的构件合并到同一个 bundle，并上传一次。
这些入口共用 `uploadMavenCentralBundle → mavenCentralBundle → ZIP/TAR.GZ` 任务链；
归档任务通过排序约束等待本次任务图中的暂存发布完成，不会自动触发其他暂存发布。

任务依赖关系如下（子节点是父节点的 `dependsOn` 前置依赖，不表示同级任务的执行顺序）：

```plain text
publish
├─ 其他仓库发布任务
└─ publishToMavenCentral
   ├─ publishAllPublicationsToMavenCentralStagingRepository
   │  ├─ publishX1PublicationToMavenCentralStagingRepository
   │  │  └─ cleanMavenCentralStagingRepository
   │  ├─ publishX2PublicationToMavenCentralStagingRepository
   │  │  └─ cleanMavenCentralStagingRepository
   │  └─ 各子项目的全量暂存发布任务
   │     └─ 子项目各 publication 暂存任务
   │        └─ cleanMavenCentralStagingRepository
   └─ uploadMavenCentralBundle
      └─ mavenCentralBundle
         ├─ mavenCentralTarGzBundle
         └─ mavenCentralZipBundle

publishX1PublicationToMavenCentral
├─ publishX1PublicationToMavenCentralStagingRepository
│  └─ cleanMavenCentralStagingRepository
└─ uploadMavenCentralBundle
   └─ mavenCentralBundle
      ├─ mavenCentralTarGzBundle
      └─ mavenCentralZipBundle
```

ZIP/TAR.GZ 归档任务额外通过 `mustRunAfter` 等待已加入任务图的暂存发布任务完成。
共享清理任务在同一次构建中只执行一次；两个归档任务中仅配置选中的格式实际执行。

直接执行 `mavenCentralBundle` 或 `uploadMavenCentralBundle` 会使用当前 staging 目录，
不会清理或重新发布构件。需要先暂存再本地打包时，执行：
`./gradlew publishAllPublicationsToMavenCentralStagingRepository mavenCentralBundle`。

根项目 `build.gradle`：

```groovy
plugins {
  id 'cn.taketoday.maven-central-publishing' version '0.0.5'
}
```

子模块共享的发布脚本（例如 `gradle/publications.gradle`，由子模块 `apply from`）：

```groovy
apply plugin: 'maven-publish'

publishing {
  publications {
    maven(MavenPublication) {
      from components.java
    }
  }
}
```

`mavenCentralStaging` 仓库由插件统一配置，子模块无需也不允许自行声明同名仓库。

### 调试日志

插件通过 Gradle 标准日志输出发布与聚合信息，日志级别由 Gradle 命令行控制，无需额外配置：

| 命令                    | 可见内容                                                         |
|-------------------------|------------------------------------------------------------------|
| 默认                    | 仅 Gradle 常规输出                                               |
| `./gradlew --info ...`  | `INFO`：staging 仓库位置、聚合的子模块发布任务数量               |
| `./gradlew --debug ...` | `DEBUG`：每个子模块的聚合/跳过细节及原因、插件应用与任务注册情况 |

例如：

```shell
./gradlew publishToMavenCentral --dry-run --info
```

会输出类似：

```
Maven Central publishing configured for project ':', staging at '.../build/publishing/staging-repository'
Aggregated 90 subproject publishing task(s) into root project 'infra' Maven Central publishing
```

`--debug` 时还会逐行列出每个被聚合的子模块，便于排查漏发问题。

## 致谢

本项目借鉴了 [SgtSilvio/gradle-maven-central-publishing](https://github.com/SgtSilvio/gradle-maven-central-publishing) 的发布流程与设计思路，
并结合实际场景做了调整与扩展（如根项目聚合发布、SNAPSHOT 直接发布到快照仓库等）。

同时感谢：[JetBrains](https://www.jetbrains.com/?from=https://github.com/TAKETODAY/today-infrastructure) 提供免费 IDE 开源授权

## 📄 开源协议

本项目使用 [Apache License 2.0](LICENSE) 开源。
