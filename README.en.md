# Maven Central Publishing Gradle Plugin

[中文](./README.md)

A Gradle plugin for publishing to the Maven Central Publisher API. It supports:

- uploading releases to Maven Central Portal and polling deployment status;
- `AUTOMATIC` and `USER_MANAGED` publishing modes;
- `ZIP` and `TAR.GZ` bundles;
- aggregated publishing for multi-module builds;
- direct publishing of `-SNAPSHOT` versions to the Maven Central Snapshots repository.

![Java17](https://img.shields.io/badge/JDK-17+-success.svg)
[![Apache License 2.0](https://img.shields.io/github/license/TAKETODAY/maven-central-publishing-gradle-plugin?color=blue)](./LICENSE)
[![Release](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/release.yaml/badge.svg)](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/release.yaml)
[![CI](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/ci.yaml/badge.svg)](https://github.com/TAKETODAY/maven-central-publishing-gradle-plugin/actions/workflows/ci.yaml)

## Quick Start

### 1. Apply the plugin to the root project

The plugin can only be applied to the root project. Replace `<version>` with the version you want to use:

```groovy
plugins {
  id 'cn.taketoday.maven-central-publishing' version '<version>'
}
```

In subprojects, apply `maven-publish` and declare at least one `MavenPublication`. With the `java` / `java-library`
plugin, publish from `components.java`:

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

When using the `java-platform` plugin to publish constraints, use `components.javaPlatform`:

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

Run the following command from the root project to publish all publications, including subprojects:

```shell
./gradlew publishToMavenCentral
```

Release versions are first written to the root project's `build/maven-central-publishing/staging-repository`, then packaged
and uploaded as a bundle. `-SNAPSHOT` versions automatically use the Maven Central Snapshots repository;
see [Snapshot Publishing](#snapshot-publishing).

## Credentials

<a id="credentials"></a>

Create a user token in [Maven Central Portal](https://central.sonatype.com/account) first. By default, the plugin
reads the Gradle project properties `mavenCentralUsername` and `mavenCentralPassword`.

The recommended approach is to provide credentials through environment variables so that they are not stored in
the project:

```shell
export ORG_GRADLE_PROJECT_mavenCentralUsername=<token-username>
export ORG_GRADLE_PROJECT_mavenCentralPassword=<token-password>
./gradlew publishToMavenCentral
```

You can also provide them on the command line for a one-off build:

```shell
./gradlew publishToMavenCentral \
  -PmavenCentralUsername=<token-username> \
  -PmavenCentralPassword=<token-password>
```

Or place them in the user-level `~/.gradle/gradle.properties` file:

```properties
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>
```

If your project uses different property names, map them in `mavenCentral {}`:

```groovy
mavenCentral {
  credentials {
    username = providers.gradleProperty('myCentralUsername').get()
    password = providers.gradleProperty('myCentralPassword').get()
  }
}
```

> **Security note**: never commit the token into the project or the repository. `-P` command-line arguments may
> leak into shell history and process listings, so use them only for local, throwaway tests. Prefer CI secrets,
> environment variables (`ORG_GRADLE_PROJECT_*`), or the user-level `~/.gradle/gradle.properties`.

## Publishing Configuration

Configure release publishing through the `mavenCentral {}` extension:

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

### Configuration options

| Option                    | Default                                                   | Description                                                                                                                  |
|---------------------------|-----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `publishingType`          | `AUTOMATIC`                                               | `AUTOMATIC` publishes after validation; `USER_MANAGED` waits for manual publication                                          |
| `credentials`             | Gradle `mavenCentral` credentials                         | `PasswordCredentials`; defaults to `mavenCentralUsername` / `mavenCentralPassword`                                           |
| `waitUntilFullyPublished` | `false`                                                   | In automatic mode, wait for `PUBLISHED`; otherwise finish at `PUBLISHING`. Ignored in `USER_MANAGED` mode                    |
| `statusRequestInterval`   | 1 second                                                  | Delay between deployment status requests and retries; must be at least 1 millisecond                                         |
| `maxStatusRetries`        | `3`                                                       | Number of retries after consecutive status request failures; `0` disables retries and a successful response resets the count |
| `maxDeploymentWait`       | 60 minutes                                                | Maximum time to wait for the deployment to reach its target state; a non-positive value disables the timeout                 |
| `publisherUrl`            | `https://central.sonatype.com`                            | Maven Central Publisher API URL                                                                                              |
| `snapshotRepositoryUrl`   | `https://central.sonatype.com/repository/maven-snapshots` | Maven Central Snapshots repository URL                                                                                       |
| `deploymentName`          | `group:name:version`                                      | Name displayed for the deployment in Portal                                                                                  |

`bundle.format` supports `BundleFormat.ZIP` (the default) and `BundleFormat.TAR_GZ`. The `archiveFileName` should
match the selected format, for example `bundle.zip` or `bundle.tar.gz`. The output directory defaults to
`build/maven-central-publishing` and can be customized with `bundle.destinationDirectory`.

#### Publisher and snapshots URLs

`publisherUrl` and `snapshotRepositoryUrl` control the release and snapshots targets independently:

- `publisherUrl`: the Portal Publisher API used when uploading release bundles; defaults to
  `https://central.sonatype.com`;
- `snapshotRepositoryUrl`: the Maven Snapshots repository used when publishing `-SNAPSHOT` versions; defaults to
  `https://central.sonatype.com/repository/maven-snapshots`.

Changing `publisherUrl` does not affect snapshots publishing, and vice versa. Their defaults normally never need to
be changed; configure them only to point at a self-hosted or proxied service, and always use absolute HTTP (S) URLs.

### Publishing modes

`publishingType` is the native publishing mode parameter of the Sonatype Publisher API:

- `AUTOMATIC`: publish automatically after validation succeeds;
- `USER_MANAGED`: leave the deployment in `VALIDATED` so that it can be published manually in Portal or through the publishing API.

The plugin defaults to `AUTOMATIC`, while the Sonatype API defaults to `USER_MANAGED` when the parameter is omitted.
In `USER_MANAGED` mode, the task completes at `VALIDATED` and does not wait for `PUBLISHED`.

For status polling, 1 second with 1–3 retries is suitable for local debugging. For CI or production, 3–5 seconds
with 3–5 retries is recommended. Retries handle request failures only; they do not limit the total time required
for a deployment to complete. For long-running deployments, increase the polling interval rather than adding
an unlimited number of retries.

See the [Maven Central Publisher API documentation](https://central.sonatype.org/publish/publish-portal-api/).

## Release Checklist

Before publishing a release, verify that:

1. the root project uses a `group` and a non-`-SNAPSHOT` version;
2. at least one `MavenPublication` is declared (`java`, `java-library`, or `java-platform`);
3. the namespace is available on the Maven Central Portal [Namespaces](https://central.sonatype.com/publishing/namespaces) page;
4. a Maven Central user token has been created and configured (see [Credentials](#credentials));
5. `publishToMavenCentral --dry-run` succeeds;
6. if signing is required, configure `signingKey`, `signingKeyId`, and `signingPassword` (signing is a prerequisite
   for publishing to the central repository);
7. optionally, generate a bundle locally and inspect its contents first (see [Multi-Module Publishing](#multi-module-publishing)).

<a id="snapshot-publishing"></a>

## Snapshot Publishing

When the root project version ends with `-SNAPSHOT` (for example, `1.0.0-SNAPSHOT`), the plugin does not upload a
Portal bundle. Instead, it publishes directly to Maven Central Snapshots through an interface compatible with
`mvn deploy`:

- registers the reserved `mavenCentralSnapshots` repository for the root project and every subproject that applies `maven-publish`;
- uses `https://central.sonatype.com/repository/maven-snapshots/` by default and reuses the Maven Central credentials;
- points `publishToMavenCentral` and publication publishing tasks directly to the Snapshots repository;
- skips the `mavenCentralStaging`, `mavenCentralBundle`, and `uploadMavenCentralBundle` task chain.

Before publishing, enable SNAPSHOTs for the namespace on the Central Portal [Namespaces](https://central.sonatype.com/publishing/namespaces)
page: open the namespace menu and select **Enable SNAPSHOTs**.

Snapshot artifacts can be overwritten and are cleaned up after a period of time (currently 90 days). This differs
from release versions, which cannot be overwritten. `mavenCentralSnapshots` and `mavenCentralStaging` are reserved
repository names and must not be declared by users.

See the [Portal Snapshots documentation](https://central.sonatype.org/publish/publish-portal-snapshots/).

<a id="multi-module-publishing"></a>

## Multi-Module Publishing

After project evaluation, the plugin scans all subprojects. The root project version decides the publishing mode
for this build: when it ends with `-SNAPSHOT`, the root project and all subprojects publish snapshots directly;
otherwise, every project goes through the release staging and Portal bundle chain. Even if a subproject sets a
different version, it does not change the publishing mode of this build. Any subproject that applies `maven-publish`
and declares at least one `MavenPublication` is included automatically, including `java` and `java-platform` projects:

```text
Subproject publications
          │
          ▼
Shared staging repository: build/maven-central-publishing/staging-repository
          │
          ▼
Root project creates the bundle
          │
          ▼
Maven Central Portal
```

No additional aggregation script is required. The plugin must be applied to the root project; applying it to a
subproject fails during configuration.

### Reserved repositories and task rules

- `mavenCentralStaging` is created and managed by the plugin; neither the root project nor subprojects may declare it;
- release staging tasks share one repository cleanup operation;
- `publishToMavenCentral` stages all publications, including subprojects;
- `publish<Publication>PublicationToMavenCentral` stages only the selected root-project publication;
- multiple single-publication entry points in one build are combined into one bundle and uploaded once;
- only the selected archive format runs, and archive tasks wait for staging tasks in the current task graph;
- subproject builds can run in parallel with `--parallel`;
- `mavenCentralBundle --configuration-cache` can create a local bundle without upload credentials.

The task chain is shown below. Child nodes are `dependsOn` prerequisites of their parent; sibling tasks are not
necessarily executed in the order shown:

```text
publish
└─ publishToMavenCentral
   ├─ publishAllPublicationsToMavenCentralStagingRepository
   │  ├─ Root project publication staging tasks
   │  └─ Subproject publication staging tasks
   └─ uploadMavenCentralBundle
      └─ mavenCentralBundle
         ├─ mavenCentralZipBundle
         └─ mavenCentralTarGzBundle
```

Running `mavenCentralBundle` or `uploadMavenCentralBundle` directly uses the existing staging directory; it does not
clean or republish artifacts. To stage artifacts first and then create a local bundle, run:

```shell
./gradlew publishAllPublicationsToMavenCentralStagingRepository mavenCentralBundle
```

## Common Tasks

| Task                                                    | Purpose                                                                                       |
|---------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `publishToMavenCentral`                                 | Publish all publications; aggregate and upload a release bundle, or deploy snapshots directly |
| `publish<Publication>PublicationToMavenCentral`         | Publish only the selected root-project publication                                            |
| `mavenCentralBundle`                                    | Package the current staging directory as ZIP or TAR.GZ                                        |
| `uploadMavenCentralBundle`                              | Upload the current bundle and poll deployment status                                          |
| `publishAllPublicationsToMavenCentralStagingRepository` | Stage all publications in the local staging repository                                        |

In the task name, `<Publication>` is the publication name with its first letter capitalized. For example, given a
publication named `mavenJava`, the single-publication task is:

```shell
./gradlew publishMavenJavaPublicationToMavenCentral
```

This task publishes only the selected root-project publication; subproject publications are not selected by this
entry point.

## Debug Logging

The plugin uses standard Gradle logging and requires no additional configuration:

| Command                 | Output                                                                                      |
|-------------------------|---------------------------------------------------------------------------------------------|
| Default                 | Regular Gradle output                                                                       |
| `./gradlew --info ...`  | Staging repository location and the number of aggregated subproject publishing tasks        |
| `./gradlew --debug ...` | Aggregation and skip reasons for each subproject, plus plugin and task registration details |

To inspect the publishing task graph without executing it:

```shell
./gradlew publishToMavenCentral --dry-run --info
```

## Acknowledgements

This project was inspired by the publishing workflow and design of
[SgtSilvio/gradle-maven-central-publishing](https://github.com/SgtSilvio/gradle-maven-central-publishing),
with additional capabilities for root-project aggregation and direct SNAPSHOT publishing.

Thanks to [JetBrains](https://www.jetbrains.com/?from=https://github.com/TAKETODAY/today-infrastructure)
for providing free IDE licenses for open-source projects.

## License

This project is released under the [Apache License 2.0](LICENSE).
