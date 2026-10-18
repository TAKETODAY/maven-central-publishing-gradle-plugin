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
import org.gradle.api.tasks.TaskProvider;
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

import static cn.taketoday.publishing.MavenCentralPublishingPlugin.PUBLISHING_DIRECTORY_NAME;
import static cn.taketoday.publishing.MavenCentralPublishingPlugin.STAGING_REPOSITORY_DIRECTORY_NAME;
import static org.assertj.core.api.Assertions.assertThat;

class MavenCentralPublishingPluginTests {

  @TempDir
  Path directory;

  private Project project;

  private MavenCentralExtension extension;

  @BeforeEach
  void setUp() throws Exception {
    directory = directory.toRealPath();
    project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    project.getPluginManager().apply(MavenCentralPublishingPlugin.class);
    extension = project.getExtensions().getByType(MavenCentralExtension.class);
    ((ProjectInternal) project).evaluate();
    TaskProvider<?> publishToMavenCentral = new MavenCentralPublishingPlugin().configurePublishing(project, extension);
    project.getTasks().named("publish").configure(task -> task.dependsOn(publishToMavenCentral));
  }

  @Test
  void appliesRequiredPluginsAndCreatesStagingRepository() {
    assertThat(project.getPluginManager().hasPlugin("signing")).isTrue();
    assertThat(project.getPluginManager().hasPlugin("maven-publish")).isTrue();
    var publishing = project.getExtensions().getByType(PublishingExtension.class);
    var repository = (MavenArtifactRepository) publishing.getRepositories().getByName("mavenCentralStaging");
    assertThat(repository.getUrl()).isEqualTo(directory.resolve("build").resolve(PUBLISHING_DIRECTORY_NAME).resolve(STAGING_REPOSITORY_DIRECTORY_NAME).toUri());
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
            .isEqualTo(directory.resolve("build").resolve(PUBLISHING_DIRECTORY_NAME).resolve(format.getDefaultFileName()).toFile());
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
    Path staging = directory.resolve("build").resolve(PUBLISHING_DIRECTORY_NAME)
            .resolve(STAGING_REPOSITORY_DIRECTORY_NAME).resolve("example/library");
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
    Path staging = directory.resolve("build").resolve(PUBLISHING_DIRECTORY_NAME).resolve(STAGING_REPOSITORY_DIRECTORY_NAME);
    Files.createDirectories(staging);
    Files.writeString(staging.resolve("stale.pom"), "stale publication");
    Path bundle = Files.writeString(directory.resolve("build").resolve(PUBLISHING_DIRECTORY_NAME).resolve("bundle.zip"), "previous bundle");

    Delete clean = (Delete) task("cleanMavenCentralStagingRepository");
    clean.getActions().forEach(action -> action.execute(clean));

    assertThat(staging).doesNotExist();
    assertThat(bundle).hasContent("previous bundle");
  }

  @Test
  void snapshotVersionRegistersSnapshotsRepositoryAndRoutesPublishing() {
    Project snapshotProject = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    snapshotProject.setVersion("1.0-SNAPSHOT");
    snapshotProject.getPluginManager().apply(MavenCentralPublishingPlugin.class);
    MavenCentralExtension snapshotExtension = snapshotProject.getExtensions().getByType(MavenCentralExtension.class);
    snapshotExtension.credentials(credentials -> {
      credentials.setUsername("test-user");
      credentials.setPassword("test-password");
    });
    URI customSnapshotRepositoryUrl = URI.create("https://repository.example.com/snapshots/");
    snapshotExtension.getSnapshotRepositoryUrl().set(customSnapshotRepositoryUrl);
    var publishing = snapshotProject.getExtensions().getByType(PublishingExtension.class);
    publishing.getPublications().create("library", MavenPublication.class);
    ((ProjectInternal) snapshotProject).evaluate();
    new MavenCentralPublishingPlugin().configurePublishing(snapshotProject, snapshotExtension);

    var repository = (MavenArtifactRepository) publishing.getRepositories().getByName("mavenCentralSnapshots");
    assertThat(repository.getUrl()).isEqualTo(customSnapshotRepositoryUrl);
    assertThat(snapshotProject.getTasks().findByName("uploadMavenCentralBundle")).isNull();
    assertThat(snapshotProject.getTasks().findByName("mavenCentralBundle")).isNull();
    assertThat(snapshotProject.getTasks().findByName("mavenCentralStaging")).isNull();
    Task publishLibrary = snapshotProject.getTasks().getByName("publishLibraryPublicationToMavenCentral");
    assertThat(publishLibrary.getTaskDependencies().getDependencies(publishLibrary))
            .extracting(Task::getName)
            .contains("publishLibraryPublicationToMavenCentralSnapshotsRepository");
    Task publishAll = snapshotProject.getTasks().getByName("publishToMavenCentral");
    assertThat(publishAll.getTaskDependencies().getDependencies(publishAll))
            .extracting(Task::getName)
            .contains("publishAllPublicationsToMavenCentralSnapshotsRepository")
            .doesNotContain("uploadMavenCentralBundle", "publishAllPublicationsToMavenCentralStagingRepository");
  }

  @Test
  void snapshotVersionRegistersPublicationTasksForSubprojects() {
    Project rootProject = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    rootProject.setVersion("1.0-SNAPSHOT");
    rootProject.getPluginManager().apply(MavenCentralPublishingPlugin.class);
    MavenCentralExtension rootExtension = rootProject.getExtensions().getByType(MavenCentralExtension.class);

    Project subproject = ProjectBuilder.builder()
            .withName("library")
            .withParent(rootProject)
            .build();
    subproject.getPluginManager().apply("maven-publish");
    PublishingExtension publishing = subproject.getExtensions().getByType(PublishingExtension.class);
    publishing.getPublications().create("library", MavenPublication.class);

    ((ProjectInternal) rootProject).evaluate();
    new MavenCentralPublishingPlugin().configurePublishing(rootProject, rootExtension);

    Task publish = subproject.getTasks().getByName("publishLibraryPublicationToMavenCentral");
    assertThat(publish.getTaskDependencies().getDependencies(publish))
            .extracting(Task::getName)
            .containsExactly("publishLibraryPublicationToMavenCentralSnapshotsRepository");
    assertThat(subproject.getTasks().getByName("publishLibraryPublicationToMavenCentralSnapshotsRepository"))
            .isNotNull();
    assertThat(subproject.getTasks().findByName("uploadMavenCentralBundle")).isNull();
    assertThat(subproject.getTasks().findByName("mavenCentralBundle")).isNull();
  }

  @Test
  void releaseVersionRegistersPublicationTasksForSubprojects() {
    Project rootProject = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    rootProject.setVersion("1.0");
    rootProject.getPluginManager().apply(MavenCentralPublishingPlugin.class);
    MavenCentralExtension rootExtension = rootProject.getExtensions().getByType(MavenCentralExtension.class);

    Project subproject = ProjectBuilder.builder()
            .withName("library")
            .withParent(rootProject)
            .build();
    subproject.getPluginManager().apply("maven-publish");
    PublishingExtension publishing = subproject.getExtensions().getByType(PublishingExtension.class);
    publishing.getPublications().create("library", MavenPublication.class);

    ((ProjectInternal) rootProject).evaluate();
    new MavenCentralPublishingPlugin().configurePublishing(rootProject, rootExtension);

    Task publish = subproject.getTasks().getByName("publishLibraryPublicationToMavenCentral");
    assertThat(publish.getTaskDependencies().getDependencies(publish))
            .extracting(Task::getName)
            .contains("publishLibraryPublicationToMavenCentralStagingRepository", "uploadMavenCentralBundle");
    Task staging = subproject.getTasks().getByName("publishLibraryPublicationToMavenCentralStagingRepository");
    assertThat(staging.getTaskDependencies().getDependencies(staging))
            .extracting(Task::getName)
            .contains("cleanMavenCentralStagingRepository");
    assertThat(rootProject.getTasks().getByName("uploadMavenCentralBundle")).isNotNull();
    assertThat(subproject.getTasks().findByName("mavenCentralBundle")).isNull();
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
