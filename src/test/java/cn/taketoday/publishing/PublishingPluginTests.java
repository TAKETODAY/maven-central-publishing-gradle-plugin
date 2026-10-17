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

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Compression;
import org.gradle.api.tasks.bundling.Tar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingPluginTests {

  @TempDir
  Path directory;

  private Project project;

  private MavenCentralExtension extension;

  @BeforeEach
  void setUp() throws Exception {
    directory = directory.toRealPath();
    project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    project.getPluginManager().apply(PublishingPlugin.class);
    extension = project.getExtensions().getByType(MavenCentralExtension.class);
  }

  @Test
  void appliesRequiredPluginsAndCreatesStagingRepository() {
    assertThat(project.getPluginManager().hasPlugin("signing")).isTrue();
    assertThat(project.getPluginManager().hasPlugin("maven-publish")).isTrue();
    var publishing = project.getExtensions().getByType(PublishingExtension.class);
    var repository = (MavenArtifactRepository) publishing.getRepositories().getByName("mavenCentralStaging");
    assertThat(repository.getUrl()).isEqualTo(directory.resolve("build/publishing/staging-repository").toUri());
  }

  @Test
  void registersPublicationTasksAddedAfterPluginApplication() {
    var publishing = project.getExtensions().getByType(PublishingExtension.class);
    publishing.getPublications().create("library", MavenPublication.class);
    publishing.getPublications().create("platform", MavenPublication.class);
    Task upload = task("uploadMavenCentralBundle");
    Task stagingAll = task("publishAllPublicationsToMavenCentralStagingRepository");
    for (String name : new String[] { "Library", "Platform" }) {
      Task staging = task("publish" + name + "PublicationToMavenCentralStagingRepository");
      Task publish = task("publish" + name + "PublicationToMavenCentral");
      assertThat(publish.getTaskDependencies().getDependencies(publish)).extracting(Task::getName)
              .contains(staging.getName(), upload.getName());
      assertThat(staging.getTaskDependencies().getDependencies(staging))
              .extracting(Task::getName).contains("cleanMavenCentralStagingRepository");
      assertThat(stagingAll.getTaskDependencies().getDependencies(stagingAll))
              .extracting(Task::getName).contains(staging.getName());
      for (String archiveName : new String[] { "mavenCentralZipBundle", "mavenCentralTarGzBundle" }) {
        Task archive = task(archiveName);
        assertThat(archive.getTaskDependencies().getDependencies(archive))
                .extracting(Task::getName).doesNotContain(stagingAll.getName());
        assertThat(archive.getMustRunAfter().getDependencies(archive))
                .extracting(Task::getName).contains(staging.getName());
      }
    }
    Task publishAll = task("publishToMavenCentral");
    assertThat(publishAll.getTaskDependencies().getDependencies(publishAll))
            .extracting(Task::getName).contains(upload.getName(), "publishAllPublicationsToMavenCentralStagingRepository");
    Task publish = task("publish");
    assertThat(publish.getTaskDependencies().getDependencies(publish)).extracting(Task::getName).contains(publishAll.getName());
  }

  @ParameterizedTest
  @EnumSource(BundleFormat.class)
  void archiveSelectionAndUploadInputFollowFormatChanges(BundleFormat format) {
    Zip zip = (Zip) task("mavenCentralZipBundle");
    Tar tar = (Tar) task("mavenCentralTarGzBundle");
    var upload = (MavenCentralUploadTask) task("uploadMavenCentralBundle");
    extension.getBundle().getFormat().set(format);

    assertThat(zip.getOnlyIf().isSatisfiedBy(zip)).isEqualTo(format == BundleFormat.ZIP);
    assertThat(tar.getOnlyIf().isSatisfiedBy(tar)).isEqualTo(format == BundleFormat.TAR_GZ);
    assertThat(tar.getCompression()).isEqualTo(Compression.GZIP);
    assertThat(upload.getBundleFile().get().getAsFile())
            .isEqualTo(directory.resolve("build/publishing/" + format.getDefaultFileName()).toFile());
    assertThat(upload.getTaskDependencies().getDependencies(upload))
            .extracting(Task::getName).contains(format == BundleFormat.ZIP ? zip.getName() : tar.getName());
  }

  @Test
  void customArchiveLocationAndNamePropagateToUpload() {
    var upload = (MavenCentralUploadTask) task("uploadMavenCentralBundle");
    extension.bundle(options -> {
      options.getDestinationDirectory().set(directory.resolve("archives").toFile());
      options.getArchiveFileName().set("release.zip");
    });

    assertThat(upload.getBundleFile().get().getAsFile())
            .isEqualTo(directory.resolve("archives/release.zip").toFile());
    extension.getBundle().getFormat().set(BundleFormat.TAR_GZ);
    assertThat(extension.getBundle().getArchiveFileName().get()).isEqualTo("release.zip");
  }

  @Test
  void archivesExcludeRepositoryMetadataButRetainArtifactsAndChecksums() throws Exception {
    Path staging = directory.resolve("build/publishing/staging-repository/example/library");
    Files.createDirectories(staging.resolve("1.0"));
    Files.writeString(staging.resolve("maven-metadata.xml"), "metadata");
    Files.writeString(staging.resolve("maven-metadata.xml.sha256"), "checksum");
    Path artifact = Files.writeString(staging.resolve("1.0/library-1.0.jar"), "artifact");
    Path checksum = Files.writeString(staging.resolve("1.0/library-1.0.jar.sha256"), "checksum");

    for (String name : new String[] { "mavenCentralZipBundle", "mavenCentralTarGzBundle" }) {
      var archive = (AbstractArchiveTask) task(name);
      assertThat(archive.getSource().getFiles()).containsExactlyInAnyOrder(artifact.toFile(), checksum.toFile());
    }
  }

  @Test
  void cleaningStagingRepositoryPreservesExistingBundle() throws Exception {
    Path staging = directory.resolve("build/publishing/staging-repository");
    Files.createDirectories(staging);
    Files.writeString(staging.resolve("stale.pom"), "stale publication");
    Path bundle = Files.writeString(directory.resolve("build/publishing/bundle.zip"), "previous bundle");

    Delete clean = (Delete) task("cleanMavenCentralStagingRepository");
    clean.getActions().forEach(action -> action.execute(clean));

    assertThat(staging).doesNotExist();
    assertThat(bundle).hasContent("previous bundle");
  }

  @Test
  void snapshotVersionRegistersSnapshotsRepositoryAndRoutesPublishing() {
    project.setVersion("1.0-SNAPSHOT");
    extension.credentials(credentials -> {
      credentials.setUsername("test-user");
      credentials.setPassword("test-password");
    });
    var publishing = project.getExtensions().getByType(PublishingExtension.class);
    publishing.getPublications().create("library", MavenPublication.class);
    new PublishingPlugin().routeSnapshotPublishing(project, extension);

    var repository = (MavenArtifactRepository) publishing.getRepositories().getByName("mavenCentralSnapshots");
    assertThat(repository.getUrl()).isEqualTo(URI.create("https://central.sonatype.com/repository/maven-snapshots/"));
    Task upload = task("uploadMavenCentralBundle");
    Task publishAll = task("publishToMavenCentral");
    assertThat(publishAll.getTaskDependencies().getDependencies(publishAll))
            .extracting(Task::getName)
            .contains("publishAllPublicationsToMavenCentralSnapshotsRepository")
            .doesNotContain(upload.getName(), "publishAllPublicationsToMavenCentralStagingRepository");
    Task publish = task("publishLibraryPublicationToMavenCentral");
    assertThat(publish.getTaskDependencies().getDependencies(publish))
            .extracting(Task::getName)
            .contains("publishLibraryPublicationToMavenCentralSnapshotsRepository")
            .doesNotContain(upload.getName(), "publishLibraryPublicationToMavenCentralStagingRepository");
  }

  @Test
  void releaseVersionKeepsBundleUploadPublishing() {
    project.setVersion("1.0");
    var publishing = project.getExtensions().getByType(PublishingExtension.class);
    publishing.getPublications().create("library", MavenPublication.class);
    ((ProjectInternal) project).evaluate();

    assertThat(publishing.getRepositories().findByName("mavenCentralSnapshots")).isNull();
    Task upload = task("uploadMavenCentralBundle");
    Task publishAll = task("publishToMavenCentral");
    assertThat(publishAll.getTaskDependencies().getDependencies(publishAll))
            .extracting(Task::getName)
            .contains(upload.getName(), "publishAllPublicationsToMavenCentralStagingRepository")
            .doesNotContain("publishAllPublicationsToMavenCentralSnapshotsRepository");
  }

  private Task task(String name) {
    return project.getTasks().getByName(name);
  }
}
