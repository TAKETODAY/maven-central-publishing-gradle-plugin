/*
 * Copyright 2026 the TODAY authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.taketoday.publishing;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for subproject aggregation with the TestKit.
 */
class MavenCentralPublishingPluginIntegrationTests {

  @TempDir
  Path directory;

  @Test
  void aggregatesSubprojectStagingPublicationsIntoRootPublish() throws IOException {
    write("settings.gradle", """
            rootProject.name = 'aggregate'
            include 'library'
            include 'bom'
            """);
    write("build.gradle", """
            plugins {
              id 'cn.taketoday.maven-central-publishing'
            }
            subprojects {
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              publishing {
                publications {
                  maven(MavenPublication) {
                    from components.java
                  }
                }
              }
            }
            """);
    Files.createDirectories(directory.resolve("library"));
    Files.createDirectories(directory.resolve("bom"));

    BuildResult result = run("publishToMavenCentral", "--dry-run");
    String output = result.getOutput();
    assertThat(output)
            .contains(":library:publishAllPublicationsToMavenCentralStagingRepository")
            .contains(":bom:publishAllPublicationsToMavenCentralStagingRepository")
            .contains(":publishAllPublicationsToMavenCentralStagingRepository")
            .contains(":uploadMavenCentralBundle");
  }

  @Test
  void registersMavenCentralTasksForSubprojectPublications() throws IOException {
    write("settings.gradle", "rootProject.name = 'aggregate'; include 'library'");
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing' }
            project(':library') {
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              group = 'example'
              version = '1.0.0'
              publishing {
                publications {
                  library(MavenPublication) { from components.java }
                }
              }
            }
            """);
    Files.createDirectories(directory.resolve("library"));

    BuildResult result = run(":library:publishLibraryPublicationToMavenCentral", "--dry-run");

    assertThat(result.getOutput())
            .contains(":cleanMavenCentralStagingRepository")
            .contains(":library:publishLibraryPublicationToMavenCentralStagingRepository")
            .contains(":uploadMavenCentralBundle")
            .doesNotContain(":publishAllPublicationsToMavenCentralStagingRepository");
  }

  @Test
  void registersSnapshotPublicationTasksForSubprojects() throws IOException {
    write("settings.gradle", "rootProject.name = 'snapshot-aggregate'; include 'library'");
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing' }
            version = '1.0-SNAPSHOT'
            project(':library') {
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              group = 'example'
              version = '1.0-SNAPSHOT'
              publishing {
                publications {
                  library(MavenPublication) { from components.java }
                }
              }
            }
            """);
    Files.createDirectories(directory.resolve("library"));

    BuildResult result = run(":library:publishLibraryPublicationToMavenCentral", "--dry-run");

    assertThat(result.getOutput())
            .contains(":library:publishLibraryPublicationToMavenCentral")
            .contains(":library:publishLibraryPublicationToMavenCentralSnapshotsRepository")
            .doesNotContain(":uploadMavenCentralBundle")
            .doesNotContain(":mavenCentralBundle")
            .doesNotContain(":publishAllPublicationsToMavenCentralStagingRepository");
  }

  @Test
  void snapshotPublicationTaskUsesSnapshotsRepositoryForRootPublication() throws IOException {
    write("settings.gradle", "rootProject.name = 'snapshot-root'");
    write("build.gradle", """
            plugins {
              id 'cn.taketoday.maven-central-publishing'
              id 'java'
              id 'maven-publish'
            }
            version = '1.0-SNAPSHOT'
            group = 'example'
            publishing {
              publications {
                library(MavenPublication) { from components.java }
              }
            }
            """);

    BuildResult result = run("publishLibraryPublicationToMavenCentral", "--dry-run");

    assertThat(result.getOutput())
            .contains(":publishLibraryPublicationToMavenCentral")
            .contains(":publishLibraryPublicationToMavenCentralSnapshotsRepository")
            .doesNotContain(":uploadMavenCentralBundle")
            .doesNotContain(":mavenCentralBundle")
            .doesNotContain(":publishAllPublicationsToMavenCentralStagingRepository");
  }

  @Test
  void skipsSubprojectsWithoutPublishingAndBundlesStagedArtifacts() throws IOException {
    write("settings.gradle", """
            rootProject.name = 'aggregate'
            include 'library'
            include 'plain'
            """);
    write("build.gradle", """
            plugins {
              id 'cn.taketoday.maven-central-publishing'
            }
            project(':library') {
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              group = 'com.example'
              version = '1.0.0'
              publishing {
                publications {
                  maven(MavenPublication) {
                    from components.java
                  }
                }
              }
            }
            project(':plain') {
              apply plugin: 'java'
            }
            """);
    Files.createDirectories(directory.resolve("library/src/main/java/example"));
    Files.createDirectories(directory.resolve("plain/src/main/java/example"));
    Path stagingRepository = directory.resolve("build/publishing/staging-repository");
    Files.createDirectories(stagingRepository);
    Files.writeString(stagingRepository.resolve("stale.txt"), "previous publication");

    BuildResult result = run("publishAllPublicationsToMavenCentralStagingRepository", "mavenCentralBundle");

    assertThat(stagingRepository.resolve("stale.txt")).doesNotExist();
    assertThat(stagingRepository.resolve("com/example/library/1.0.0/library-1.0.0.jar")).exists();
    assertThat(stagingRepository.resolve("com/example/plain")).doesNotExist();
    assertThat(result.getOutput())
            .doesNotContain(":plain:publishAllPublicationsToMavenCentralStagingRepository");
    assertThat(directory.resolve("build/publishing/bundle.zip")).exists();
  }

  @Test
  void publishesSingleProjectWhenNoSubprojectsPublishToStaging() throws IOException {
    write("settings.gradle", """
            rootProject.name = 'standalone'
            """);
    write("build.gradle", """
            plugins {
              id 'cn.taketoday.maven-central-publishing'
            }
            apply plugin: 'java'
            apply plugin: 'maven-publish'
            publishing {
              publications {
                maven(MavenPublication) {
                  from components.java
                }
              }
            }
            """);

    BuildResult result = run("publishToMavenCentral", "--dry-run");
    assertThat(result.getOutput())
            .contains(":publishAllPublicationsToMavenCentralStagingRepository")
            .contains(":uploadMavenCentralBundle");
  }

  @Test
  void snapshotVersionPublishesToSnapshotsRepositoryInsteadOfBundleUpload() throws IOException {
    write("settings.gradle", """
            rootProject.name = 'snapshots'
            include 'library'
            """);
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing' }
            version = '1.0-SNAPSHOT'
            project(':library') {
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              group = 'com.example'
              version = '1.0-SNAPSHOT'
              publishing {
                publications { maven(MavenPublication) { from components.java } }
              }
            }
            """);
    Files.createDirectories(directory.resolve("library"));

    BuildResult result = run("publishToMavenCentral", "--dry-run");
    String output = result.getOutput();
    assertThat(output)
            .contains(":library:publishAllPublicationsToMavenCentralSnapshotsRepository")
            .contains(":publishAllPublicationsToMavenCentralSnapshotsRepository")
            .doesNotContain(":uploadMavenCentralBundle")
            .doesNotContain(":mavenCentralBundle")
            .doesNotContain(":publishAllPublicationsToMavenCentralStagingRepository");

    BuildResult tasks = run("tasks", "--all");
    assertThat(tasks.getOutput())
            .doesNotContain("mavenCentralBundle")
            .doesNotContain("uploadMavenCentralBundle")
            .doesNotContain("mavenCentralStaging");

  }

  @Test
  void snapshotDryRunSupportsConfigurationCache() throws IOException {
    write("settings.gradle", "rootProject.name = 'snapshot-cache'");
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing'; id 'java' }
            version = '1.0-SNAPSHOT'
            publishing {
              publications { maven(MavenPublication) { from components.java } }
            }
            """);

    BuildResult first = run("publishToMavenCentral", "--configuration-cache", "--dry-run");
    assertThat(first.getOutput()).contains("Configuration cache entry stored");
    BuildResult second = run("publishToMavenCentral", "--configuration-cache", "--dry-run");
    assertThat(second.getOutput()).contains("Reusing configuration cache");
  }

  @Test
  void rejectsUserDefinedSnapshotRepository() throws IOException {
    write("settings.gradle", "rootProject.name = 'reserved-snapshots'");
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing' }
            version = '1.0-SNAPSHOT'
            publishing {
              repositories {
                maven {
                  name = 'mavenCentralSnapshots'
                  url = 'https://central.sonatype.com/repository/maven-snapshots/'
                }
              }
            }
            """);

    BuildResult result = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail();

    assertThat(result.getOutput()).contains("mavenCentralSnapshots", "is reserved for");
  }

  private BuildResult run(String... arguments) {
    return GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withPluginClasspath()
            .withArguments(arguments)
            .build();
  }

  @Test
  void cleansBeforeParallelPublicationsAndBundlesAllArtifacts() throws IOException {
    write("settings.gradle", """
            rootProject.name = 'aggregate'
            include 'library', 'other'
            """);
    write("build.gradle", """
            plugins {
              id 'cn.taketoday.maven-central-publishing'
              id 'java'
            }
            allprojects {
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              group = 'com.example'
              version = '1.0.0'
              publishing {
                publications {
                  maven(MavenPublication) { from components.java }
                }
              }
            }
            // Exercise the real upload dependency graph without contacting Central.
            tasks.matching { it.name == 'uploadMavenCentralBundle' }.configureEach { enabled = false }
            """);
    Files.createDirectories(directory.resolve("library"));
    Files.createDirectories(directory.resolve("other"));
    Path staging = directory.resolve("build/publishing/staging-repository");
    Files.createDirectories(staging);
    Files.writeString(staging.resolve("stale.txt"), "previous publication");

    BuildResult result = run("publishToMavenCentral", "--parallel", "--max-workers=4");

    assertThat(result.task(":cleanMavenCentralStagingRepository")).isNotNull();
    assertThat(staging.resolve("stale.txt")).doesNotExist();
    try (ZipFile zip = new ZipFile(directory.resolve("build/publishing/bundle.zip").toFile())) {
      for (String module : new String[] { "aggregate", "library", "other" }) {
        String artifact = "com/example/" + module + "/1.0.0/" + module + "-1.0.0.jar";
        assertThat(staging.resolve(artifact)).exists();
        assertThat(zip.getEntry(artifact)).isNotNull();
      }
      assertThat(zip.getEntry("stale.txt")).isNull();
    }
    String output = result.getOutput();
    for (String module : new String[] { "library", "other" }) {
      assertThat(output.indexOf("> Task :cleanMavenCentralStagingRepository"))
              .isNotNegative()
              .isLessThan(output.indexOf("> Task :" + module + ":publishMavenPublicationToMavenCentralStagingRepository"));
    }

    BuildResult tarResult = run("publishAllPublicationsToMavenCentralStagingRepository", "mavenCentralTarGzBundle", "--dry-run", "--parallel");
    for (String module : new String[] { "library", "other" }) {
      assertThat(tarResult.getOutput().indexOf(":" + module + ":publishAllPublicationsToMavenCentralStagingRepository SKIPPED"))
              .isNotNegative()
              .isLessThan(tarResult.getOutput().indexOf(":mavenCentralTarGzBundle SKIPPED"));
    }
  }

  @Test
  void rejectsPluginAppliedToSubproject() throws IOException {
    write("settings.gradle", "include 'library'");
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing' }
            project(':library') { apply plugin: 'cn.taketoday.maven-central-publishing' }
            """);
    Files.createDirectories(directory.resolve("library"));

    BuildResult result = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail();

    assertThat(result.getOutput()).contains("Apply 'cn.taketoday.maven-central-publishing' only to the root project");
  }

  @Test
  void rejectsUserDefinedSubprojectStagingRepositoryEvenWithSharedDirectory() throws IOException {
    write("settings.gradle", "include 'library'");
    Files.createDirectories(directory.resolve("library"));
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing' }
            project(':library') {
              apply plugin: 'maven-publish'
              publishing {
                repositories {
                  maven {
                    name = 'mavenCentralStaging'
                    url = rootProject.layout.buildDirectory.dir('publishing/staging-repository')
                  }
                }
                publications { maven(MavenPublication) { groupId = 'example'; artifactId = 'library'; version = '1.0' } }
              }
            }
            """);

    BuildResult result = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withPluginClasspath()
            .withArguments("mavenCentralBundle", "--no-configuration-cache")
            .buildAndFail();

    assertThat(result.getOutput()).contains("mavenCentralStaging", ":library", "is reserved for");
  }

  @Test
  void rejectsUserDefinedRootStagingRepository() throws IOException {
    write("settings.gradle", "rootProject.name = 'reserved-repository'");
    write("build.gradle", """
            plugins {
              id 'maven-publish'
              id 'cn.taketoday.maven-central-publishing' apply false
            }
            publishing {
              repositories {
                maven {
                  name = 'mavenCentralStaging'
                  url = layout.buildDirectory.dir('custom-staging')
                }
              }
            }
            apply plugin: 'cn.taketoday.maven-central-publishing'
            """);

    BuildResult result = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail();

    assertThat(result.getOutput()).contains("mavenCentralStaging", "is reserved for");
  }

  @Test
  void bundlesWithoutCredentialsAndReusesConfigurationCache() throws IOException {
    write("settings.gradle", "rootProject.name = 'cached-bundle'; include 'library'");
    Files.createDirectories(directory.resolve("library"));
    for (String format : new String[] { "ZIP", "TAR_GZ" }) {
      write("build.gradle", """
              plugins { id 'cn.taketoday.maven-central-publishing' }
              mavenCentral {
                bundle { format = cn.taketoday.publishing.BundleFormat.%s }
                credentials = providers.provider { throw new GradleException('Credentials must not be resolved for bundling') }
              }
              project(':library') {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                group = 'example'
                version = '1.0'
                publishing {
                  publications { maven(MavenPublication) { from components.java } }
                }
              }
              """.formatted(format));

      BuildResult first = run("publishAllPublicationsToMavenCentralStagingRepository", "mavenCentralBundle", "--configuration-cache", "--rerun-tasks");
      assertThat(first.getOutput()).contains("Configuration cache entry stored");
      BuildResult second = run("publishAllPublicationsToMavenCentralStagingRepository", "mavenCentralBundle", "--configuration-cache", "--rerun-tasks");
      assertThat(second.getOutput()).contains("Reusing configuration cache");
      assertThat(directory.resolve("build/publishing/" + (format.equals("ZIP") ? "bundle.zip" : "bundle.tar.gz"))).exists();
      if (format.equals("ZIP")) {
        try (ZipFile zip = new ZipFile(directory.resolve("build/publishing/bundle.zip").toFile())) {
          assertThat(zip.getEntry("example/library/1.0/library-1.0.jar")).isNotNull();
        }
      }
    }
  }

  @Test
  void publicationEntrypointsSelectSharedBundleContents() throws IOException {
    write("settings.gradle", "rootProject.name = 'selective'; include 'library'");
    Files.createDirectories(directory.resolve("library"));
    write("build.gradle", """
            plugins { id 'cn.taketoday.maven-central-publishing'; id 'java' }
            group = 'example'
            version = '1.0'
            publishing {
              publications {
                first(MavenPublication) { artifactId = 'first'; from components.java }
                second(MavenPublication) { artifactId = 'second'; from components.java }
              }
            }
            project(':library') {
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              group = 'example'
              version = '1.0'
              publishing { publications { maven(MavenPublication) { from components.java } } }
            }
            tasks.matching { it.name == 'uploadMavenCentralBundle' }.configureEach { enabled = false }
            """);

    BuildResult single = run("publishFirstPublicationToMavenCentral", "--parallel");
    assertThat(single.task(":publishSecondPublicationToMavenCentralStagingRepository")).isNull();
    assertThat(single.task(":library:publishMavenPublicationToMavenCentralStagingRepository")).isNull();
    assertThat(single.task(":mavenCentralBundle")).isNotNull();
    assertSharedBundle("first");

    run("publishFirstPublicationToMavenCentral", "publishSecondPublicationToMavenCentral", "--parallel");
    assertSharedBundle("first", "second");

    run("publishToMavenCentral", "--parallel");
    assertSharedBundle("first", "second", "library");

    BuildResult upload = run("uploadMavenCentralBundle");
    assertThat(upload.task(":cleanMavenCentralStagingRepository")).isNull();
    assertThat(upload.task(":publishFirstPublicationToMavenCentralStagingRepository")).isNull();
    assertSharedBundle("first", "second", "library");
  }

  private void assertSharedBundle(String... modules) throws IOException {
    try (ZipFile zip = new ZipFile(directory.resolve("build/publishing/bundle.zip").toFile())) {
      assertThat(zip.stream().map(entry -> entry.getName()).filter(name -> name.endsWith(".jar")))
              .containsExactlyInAnyOrder(java.util.Arrays.stream(modules)
                      .map(module -> "example/" + module + "/1.0/" + module + "-1.0.jar").toArray(String[]::new));
    }
  }

  private void write(String name, String content) throws IOException {
    Files.writeString(directory.resolve(name), content);
  }

}
