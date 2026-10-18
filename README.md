# Maven Central Publishing Gradle Plugin

[English](./README.en.md)

一个面向 Gradle 的 Maven Central Publisher API 发布插件，支持：

- 正式版本上传到 Maven Central Portal，并自动轮询部署状态；
- `AUTOMATIC` 和 `USER_MANAGED` 两种发布模式；
- `ZIP` / `TAR.GZ` bundle；
- 根项目聚合多模块发布；
- `-SNAPSHOT` 版本直接发布到 Maven Central Snapshots 仓库。

![Java17](https://img.shields.io/badge/JDK-17+-success.svg)
[![Apache License 2.0](https://img.shields.io/github/license/TAKETODAY/maven-central-publishing-gradle-plugin?color=blue)](./LICENSE)
[![Release](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/release.yaml/badge.svg)](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/release.yaml)
[![CI](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/ci.yaml/badge.svg)](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/ci.yaml)

## 快速开始

### 1. 在根项目应用插件

插件只允许应用到根项目。将 `<版本>` 替换为需要使用的插件版本：

```groovy
plugins {
  id 'cn.taketoday.maven-central-publishing' version '<版本>'
}
```

子模块只需要应用 `maven-publish` 并声明至少一个 `MavenPublication`。使用 `java` / `java-library`
插件时，从 `components.java` 发布：

```groovy
plugins {
  id 'java-library'
  id 'maven-publish'
}

publishing {
  publications {
    mavenJava(MavenPublication) {
      from components.java
    }
  }
}
```

使用 `java-platform` 插件发布约束清单时：

```groovy
plugins {
  id 'java-platform'
  id 'maven-publish'
}

publishing {
  publications {
    javaPlatform(MavenPublication) {
      from components.javaPlatform
    }
  }
}
```

根项目执行以下任务即可发布全部 publication（包括子模块）：

```shell
./gradlew publishToMavenCentral
```

正式版本会先写入根项目的 `build/maven-central-publishing/staging-repository`，再打包并上传一个 bundle。
`-SNAPSHOT` 版本会自动改用 Maven Central Snapshots 仓库，详见[快照发布](#snapshot-publishing)。

## 凭据配置

<a id="credentials"></a>

请先在 [Maven Central Portal](https://central.sonatype.com/account) 创建用户令牌。插件默认读取
Gradle 的 `mavenCentralUsername` 和 `mavenCentralPassword` 项目属性。

推荐通过环境变量注入，避免将令牌写入项目：

```shell
export ORG_GRADLE_PROJECT_mavenCentralUsername=<令牌用户名>
export ORG_GRADLE_PROJECT_mavenCentralPassword=<令牌密码>
./gradlew publishToMavenCentral
```

也可以临时通过命令行传入：

```shell
./gradlew publishToMavenCentral \
  -PmavenCentralUsername=<令牌用户名> \
  -PmavenCentralPassword=<令牌密码>
```

或者写入用户级 `~/.gradle/gradle.properties`：

```properties
mavenCentralUsername=<令牌用户名>
mavenCentralPassword=<令牌密码>
```

如果项目已有其他属性名，可以在 `mavenCentral {}` 中映射：

```groovy
mavenCentral {
  credentials {
    username = providers.gradleProperty('myCentralUsername').get()
    password = providers.gradleProperty('myCentralPassword').get()
  }
}
```

> **安全提示**：不要把用户令牌提交到项目内或写入版本库。`-P` 命令行参数可能出现在 shell 历史
> 和进程信息中，仅适合本地临时调试。推荐在 CI 环境使用 secret，本地使用环境变量
> `ORG_GRADLE_PROJECT_*` 或用户级 `~/.gradle/gradle.properties`。

## 发布配置

所有正式版本发布选项统一通过 `mavenCentral {}` 配置：

```groovy
import cn.taketoday.publishing.BundleFormat
import cn.taketoday.publishing.PublishingType
import java.time.Duration

mavenCentral {
  bundle {
    format = BundleFormat.ZIP
    archiveFileName = 'bundle.zip'
  }

  credentials {
    username = providers.gradleProperty('mavenCentralUsername').get()
    password = providers.gradleProperty('mavenCentralPassword').get()
  }

  publishingType = PublishingType.AUTOMATIC
  waitUntilFullyPublished = true
  statusRequestInterval = Duration.ofSeconds(3)
  maxStatusRetries = 3
  maxDeploymentWait = Duration.ofMinutes(60)
  publisherUrl = uri('https://central.sonatype.com')
  snapshotRepositoryUrl = uri('https://central.sonatype.com/repository/maven-snapshots')
  deploymentName = "${group}:${project.name}:${version}"
}
```

### 配置项

| 配置项                    | 默认值                                                    | 说明                                                                                             |
|---------------------------|-----------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `publishingType`          | `AUTOMATIC`                                               | `AUTOMATIC` 校验通过后自动发布；`USER_MANAGED` 校验通过后等待手动发布                            |
| `credentials`             | Gradle `mavenCentral` 凭据                                | `PasswordCredentials`，默认读取 `mavenCentralUsername` / `mavenCentralPassword`                  |
| `waitUntilFullyPublished` | `false`                                                   | 自动发布时是否等待 `PUBLISHED`；为 `false` 时到 `PUBLISHING` 即完成。`USER_MANAGED` 模式忽略此项 |
| `statusRequestInterval`   | 1 秒                                                      | 部署状态轮询及失败重试的等待间隔，最小为 1 毫秒                                                  |
| `maxStatusRetries`        | `3`                                                       | 连续状态请求失败后的重试次数；`0` 表示不重试，成功响应后计数清零                                 |
| `maxDeploymentWait`       | 60 分钟                                                   | 等待部署进入目标状态的最长时间；设置为非正数可禁用超时                                           |
| `publisherUrl`            | `https://central.sonatype.com`                            | Maven Central Publisher API 地址                                                                 |
| `snapshotRepositoryUrl`   | `https://central.sonatype.com/repository/maven-snapshots` | Maven Central Snapshots 仓库地址                                                                 |
| `deploymentName`          | `group:name:version`                                      | Portal 中显示的部署名称                                                                          |

`bundle.format` 支持 `BundleFormat.ZIP`（默认）和 `BundleFormat.TAR_GZ`。`archiveFileName` 应与格式匹配，
例如 `bundle.zip` 或 `bundle.tar.gz`。输出目录默认为 `build/maven-central-publishing`，也可以通过 `bundle.destinationDirectory`
进行配置。

#### 发布与快照地址

`publisherUrl` 和 `snapshotRepositoryUrl` 分别控制正式版本与快照版本的发布目标：

- `publisherUrl`：正式版本上传 bundle 时使用的 Portal Publisher API 地址，默认 `https://central.sonatype.com`；
- `snapshotRepositoryUrl`：`-SNAPSHOT` 版本直接发布时使用的 Maven Snapshots 仓库地址，
  默认 `https://central.sonatype.com/repository/maven-snapshots`。

修改 `publisherUrl` 不会影响快照发布，反之亦然。两者默认值通常无需修改，只在需要指向自建或代理服务时
才配置，且必须为绝对 HTTP (S) URL。

### 发布模式

`publishingType` 对应 Sonatype Publisher API 的原生参数：

- `AUTOMATIC`：校验通过后自动发布；
- `USER_MANAGED`：校验通过后进入 `VALIDATED`，由用户在 Portal 或通过发布 API 确认发布。

插件默认使用 `AUTOMATIC`，而 Sonatype API 在省略该参数时默认使用 `USER_MANAGED`。
`USER_MANAGED` 模式下任务在 `VALIDATED` 状态完成，不会等待 `PUBLISHED`。

状态轮询建议：本地调试可使用 1 秒和 1～3 次重试；CI 或生产环境建议使用 3～5 秒和 3～5 次重试。
重试只处理状态请求失败，不限制部署正常处理的总时长；发布耗时较长时优先增大轮询间隔。

参考：[Maven Central Publisher API](https://central.sonatype.org/publish/publish-portal-api/)。

## 发布检查清单

正式版本发布前，建议依次确认：

1. 根项目配置了正确的 `group` 和非 `-SNAPSHOT` 的 `version`；
2. 声明了至少一个 `MavenPublication`（`java`、`java-library` 或 `java-platform`）；
3. 在 Maven Central Portal 的 [Namespaces](https://central.sonatype.com/publishing/namespaces) 页面确认命名空间可用；
4. 已创建并配置 Maven Central 用户令牌（见[凭据配置](#credentials)）；
5. 运行 `publishToMavenCentral --dry-run` 预览任务图；
6. 需要签名时，配置 `signingKey`、`signingKeyId` 和 `signingPassword`（签名是发布到中央仓库的前提）；
7. 可选：先本地生成 bundle 并核对内容（见[多模块聚合发布](#multi-module-publishing)）。

<a id="snapshot-publishing"></a>

## 快照发布（SNAPSHOT）

当根项目版本以 `-SNAPSHOT` 结尾（例如 `1.0.0-SNAPSHOT`）时，插件不会上传 Portal bundle，
而是通过兼容 `mvn deploy` 的接口直接发布到 Maven Central Snapshots：

- 自动为根项目及所有应用 `maven-publish` 的子模块注册保留仓库 `mavenCentralSnapshots`；
- 仓库地址为 `https://central.sonatype.com/repository/maven-snapshots/`，复用 Maven Central 凭据；
- `publishToMavenCentral` 和各 publication 发布任务直接指向快照仓库；
- 不执行 `mavenCentralStaging`、`mavenCentralBundle` 或 `uploadMavenCentralBundle` 任务链。

使用前，必须在 Central Portal 的 [Namespaces](https://central.sonatype.com/publishing/namespaces)
页面启用 SNAPSHOTs（命名空间下拉菜单 → **Enable SNAPSHOTs**）。

快照构件可以覆盖，并会在一段时间后（当前为 90 天）清理；这与正式版本不可覆盖的语义不同。
`mavenCentralSnapshots` 和 `mavenCentralStaging` 都是插件保留名称，不能由用户自行声明。

`publisherUrl` 用于正式版本的 Portal Publisher API，`snapshotRepositoryUrl` 用于快照版本的 Maven
仓库上传接口。两者默认值通常无需修改，并且必须配置为绝对的 HTTP(S) URL。快照发布复用
`mavenCentral` 凭据；凭据在发布任务执行时注入，不会在配置阶段要求提供。由于 `maven-publish`
不会在配置缓存中保留发布仓库，快照发布任务不参与配置缓存：运行快照发布时 Gradle 会提示任务不兼容并
丢弃缓存条目，但普通构建仍可正常复用配置缓存。

参考：[Portal Snapshots](https://central.sonatype.org/publish/publish-portal-snapshots/)。

<a id="multi-module-publishing"></a>

## 多模块聚合发布

插件会在项目评估完成后扫描所有子项目。根项目的版本决定本次构建的发布模式：根项目版本以
`-SNAPSHOT` 结尾时，根项目和所有子项目都直接发布快照；否则所有项目都走正式版本的 staging
和 Portal bundle 链路。即使子项目单独设置了不同版本，也不会改变本次构建的发布模式。只要子模块应用 `maven-publish` 并声明至少一个
`MavenPublication`（包括 `java` 和 `java-platform` 模块），就会自动加入根项目的发布流程：

```text
子模块 publication
        │
        ▼
统一 staging 仓库：build/maven-central-publishing/staging-repository
        │
        ▼
根项目打包 bundle
        │
        ▼
Maven Central Portal
```

因此无需编写额外的聚合脚本。插件只允许应用到根项目，在子模块应用会在配置阶段报错。

### 保留仓库与任务规则

- `mavenCentralStaging` 由插件统一创建，根项目和子模块都不得自行声明；
- 正式版本的 staging 发布任务共享一次清理操作；
- `publishToMavenCentral` 暂存所有 publication（含子模块）；
- `publish<Publication>PublicationToMavenCentral` 只暂存根项目指定的 publication；
- 同一次构建中的多个单 publication 入口会合并到同一个 bundle，并上传一次；
- 归档任务只执行所选格式，并等待当前任务图中的 staging 发布完成；
- 支持使用 `--parallel` 并行执行子模块构建；
- `mavenCentralBundle --configuration-cache` 可在不提供上传凭据的情况下本地打包。

完整任务链如下（子节点表示父节点的 `dependsOn` 前置依赖，不表示同级任务执行顺序）：

```text
publish
└─ publishToMavenCentral
   ├─ publishAllPublicationsToMavenCentralStagingRepository
   │  ├─ 根项目各 publication 暂存任务
   │  └─ 各子项目的 publication 暂存任务
   └─ uploadMavenCentralBundle
      └─ mavenCentralBundle
         ├─ mavenCentralZipBundle
         └─ mavenCentralTarGzBundle
```

直接执行 `mavenCentralBundle` 或 `uploadMavenCentralBundle` 会使用现有 staging 目录，
不会自动清理或重新发布。若要先暂存再本地打包：

```shell
./gradlew publishAllPublicationsToMavenCentralStagingRepository mavenCentralBundle
```

## 常用任务

| 任务                                                    | 用途                                                              |
|---------------------------------------------------------|-------------------------------------------------------------------|
| `publishToMavenCentral`                                 | 发布全部 publication；正式版本聚合后上传 bundle，快照版本直接部署 |
| `publish<Publication>PublicationToMavenCentral`         | 仅发布根项目指定 publication                                      |
| `mavenCentralBundle`                                    | 将当前 staging 目录打包为 ZIP 或 TAR.GZ                           |
| `uploadMavenCentralBundle`                              | 上传当前 bundle 并轮询部署状态                                    |
| `publishAllPublicationsToMavenCentralStagingRepository` | 将全部 publication 暂存到本地 staging 仓库                        |

任务名中的 `<Publication>` 是 publication 名称（首字母大写）。例如 publication 名为 `mavenJava` 时，
单 publication 发布任务为：

```shell
./gradlew publishMavenJavaPublicationToMavenCentral
```

该任务只发布根项目指定的 publication，子模块的 publication 不会被这个入口自动选中。

## 调试日志

插件使用 Gradle 标准日志，无需额外配置：

| 命令                    | 内容                                              |
|-------------------------|---------------------------------------------------|
| 默认                    | Gradle 常规输出                                   |
| `./gradlew --info ...`  | staging 仓库位置、聚合的子模块发布任务数量        |
| `./gradlew --debug ...` | 每个子模块的聚合/跳过原因、插件应用和任务注册详情 |

查看发布任务而不实际执行：

```shell
./gradlew publishToMavenCentral --dry-run --info
```

## 致谢

本项目借鉴了 [SgtSilvio/gradle-maven-central-publishing](https://github.com/SgtSilvio/gradle-maven-central-publishing)
的发布流程与设计思路，并结合实际场景扩展了根项目聚合发布、SNAPSHOT 直接发布等能力。

感谢 [JetBrains](https://www.jetbrains.com/?from=https://github.com/TAKETODAY/today-infrastructure)
提供免费的开源项目 IDE 授权。

## 开源协议

本项目使用 [Apache License 2.0](LICENSE) 开源。
