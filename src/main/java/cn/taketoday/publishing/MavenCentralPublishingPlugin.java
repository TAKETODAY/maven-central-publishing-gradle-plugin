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

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Compression;
import org.gradle.api.tasks.bundling.Tar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.plugins.signing.SigningPlugin;

import java.net.URI;

import static infra.util.StringUtils.capitalize;
import static org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME;
import static org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP;

/**
 * Configures Maven Central publishing for a Gradle project.
 *
 * <p>For release versions the plugin creates a local staging repository, bundles its contents
 * into an archive and registers tasks for uploading that bundle to Maven Central. For
 * {@code -SNAPSHOT} versions publishing is routed directly to the Maven Central snapshots
 * repository, bypassing the staging, bundle and upload chain.</p>
 *
 * @author <a href="https://github.com/TAKETODAY">海子 Yang</a>
 * @since 0.0.1
 */
public class MavenCentralPublishingPlugin implements Plugin<Project> {

  private static final Logger log = Logging.getLogger(MavenCentralPublishingPlugin.class);

  /** The name of the local repository used to stage release publications. */
  public static final String STAGING_REPOSITORY_NAME = "mavenCentralStaging";

  /** The name of the task that creates the ZIP bundle. */
  private static final String ZIP_BUNDLE_TASK_NAME = "mavenCentralZipBundle";

  /** The name of the task that creates the TAR.GZ bundle. */
  private static final String TAR_GZ_BUNDLE_TASK_NAME = "mavenCentralTarGzBundle";

  /** The name of the task that creates the configured Maven Central bundle. */
  public static final String BUNDLE_TASK_NAME = "mavenCentralBundle";

  /** The name of the task that uploads the Maven Central bundle. */
  public static final String UPLOAD_BUNDLE_TASK_NAME = "uploadMavenCentralBundle";

  /** The name of the lifecycle task that publishes to Maven Central. */
  public static final String PUBLISH_TO_MAVEN_CENTRAL_TASK_NAME = "publishToMavenCentral";

  /** The name of the repository used to publish SNAPSHOT versions. */
  public static final String SNAPSHOT_REPOSITORY_NAME = "mavenCentral";

  /** The legacy task-name suffix used by the snapshot publishing entry points. */
  private static final String SNAPSHOT_TASK_SUFFIX = "mavenCentralSnapshots";

  /** The suffix that identifies a SNAPSHOT version. */
  public static final String SNAPSHOT_VERSION_SUFFIX = "-SNAPSHOT";

  /** The name of the build directory that contains Maven Central publishing output. */
  public static final String PUBLISHING_DIRECTORY_NAME = "maven-central-publishing";

  /** The name of the directory that contains the local staging repository. */
  public static final String STAGING_REPOSITORY_DIRECTORY_NAME = "staging-repository";

  /** The name of the file that stores the Maven Central deployment identifier. */
  public static final String DEPLOYMENT_ID_FILE_NAME = "deployment-id.txt";

  /** The name of the task that publishes all publications to the staging repository. */
  private static final String publishAllPublicationsToMavenCentralStagingRepository =
          "publishAllPublicationsTo" + capitalize(STAGING_REPOSITORY_NAME) + "Repository";

  /** The name of the task that publishes all publications to the snapshots repository. */
  private static final String publishAllPublicationsToMavenCentralRepository =
          "publishAllPublicationsTo" + capitalize(SNAPSHOT_REPOSITORY_NAME) + "Repository";

  /** The legacy name of the task that publishes all publications to the snapshots repository. */
  private static final String publishAllPublicationsToMavenCentralSnapshotsRepository =
          "publishAllPublicationsTo" + capitalize(SNAPSHOT_TASK_SUFFIX) + "Repository";

  /**
   * Creates a Maven Central publishing plugin.
   */
  public MavenCentralPublishingPlugin() {
  }

  @Override
  public void apply(Project project) {
    if (project != project.getRootProject()) {
      throw new GradleException("Apply 'cn.taketoday.maven-central-publishing' only to the root project; "
              + "apply 'maven-publish' to subprojects for aggregated publishing.");
    }
    log.debug("Applying cn.taketoday.maven-central-publishing to project '{}'", project.getName());
    project.getPluginManager().apply(SigningPlugin.class);
    project.getPluginManager().apply(MavenPublishPlugin.class);

    MavenCentralExtension extension = project.getExtensions().create("mavenCentral", MavenCentralExtension.class);
    extension.getCredentials().convention(project.getProviders().credentials(PasswordCredentials.class, "mavenCentral"));
    extension.getDeploymentName().convention(project.provider(() -> project.getGroup() + ":" + project.getName() + ":" + project.getVersion()));

    project.getGradle().projectsEvaluated(gradle -> {
      TaskProvider<?> publishToMavenCentral = configurePublishing(project, extension);
      project.getTasks().named(PUBLISH_LIFECYCLE_TASK_NAME).configure(task -> task.dependsOn(publishToMavenCentral));
    });
    log.debug("Maven Central publishing tasks registered for project '{}'", project.getName());
  }

  TaskProvider<?> configurePublishing(Project rootProject, MavenCentralExtension extension) {
    return isSnapshotVersion(rootProject)
            ? registerSnapshotPublishing(rootProject, extension)
            : registerReleasePublishing(rootProject, extension);
  }

  private TaskProvider<?> registerReleasePublishing(Project rootProject, MavenCentralExtension extension) {
    validateUrl(extension.getPublisherUrl().get(), "publisherUrl");
    Provider<Directory> outputDirectory = rootProject.getLayout().getBuildDirectory().dir(PUBLISHING_DIRECTORY_NAME);
    Provider<Directory> stagingRepositoryDirectory = outputDirectory.map(it -> it.dir(STAGING_REPOSITORY_DIRECTORY_NAME));
    extension.getBundle().getDestinationDirectory().convention(outputDirectory);

    registerStagingRepository(rootProject, stagingRepositoryDirectory);
    if (log.isInfoEnabled()) {
      log.info("Maven Central publishing configured for project '{}', staging at '{}'",
              rootProject.getName(), stagingRepositoryDirectory.get());
    }
    var bundleTask = registerBundleTask(rootProject, stagingRepositoryDirectory, extension);
    var uploadBundleTask = registerUploadBundleTask(rootProject, bundleTask, outputDirectory, extension);
    var cleanStagingRepositoryTask = registerCleanStagingRepositoryTask(rootProject, stagingRepositoryDirectory);

    configureRootPublications(rootProject, uploadBundleTask, cleanStagingRepositoryTask);
    aggregateSubprojectsReleasePublishing(rootProject, stagingRepositoryDirectory, uploadBundleTask, cleanStagingRepositoryTask);
    return registerPublishToMavenCentralTask(rootProject, uploadBundleTask);
  }

  private void configureRootPublications(Project project, TaskProvider<MavenCentralUploadTask> uploadBundleTask,
          TaskProvider<Delete> cleanStagingRepositoryTask) {
    PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
    configurePublications(project, publishing, uploadBundleTask, cleanStagingRepositoryTask);
  }

  private void configurePublications(Project publicationProject, PublishingExtension publishing,
          TaskProvider<MavenCentralUploadTask> uploadBundleTask, TaskProvider<Delete> cleanStagingRepositoryTask) {
    publishing.getPublications().withType(MavenPublication.class).configureEach(publication -> {
      String publicationName = publication.getName();
      var publishToStagingRepositoryTask = getPublishToStagingRepositoryTask(publicationProject, publicationName);
      publishToStagingRepositoryTask.configure(task -> task.dependsOn(cleanStagingRepositoryTask));
      configureArchiveOrdering(publicationProject, publishToStagingRepositoryTask);
      registerMavenCentralPublicationTask(publicationProject, publicationName, task -> {
        task.dependsOn(publishToStagingRepositoryTask);
        task.dependsOn(uploadBundleTask);
      });
    });
  }

  private void registerStagingRepository(Project project, Provider<Directory> stagingRepositoryDirectory) {
    PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
    if (publishing.getRepositories().findByName(STAGING_REPOSITORY_NAME) != null) {
      throw new GradleException("Repository '" + STAGING_REPOSITORY_NAME + "' in project '"
              + project.getName() + "' is reserved for 'cn.taketoday.maven-central-publishing' and must not be configured by users.");
    }
    var stagingRepository = publishing.getRepositories().maven(repository -> {
      repository.setName(STAGING_REPOSITORY_NAME);
      repository.setUrl(project.uri(stagingRepositoryDirectory));
    });
    project.getGradle().projectsEvaluated(gradle -> {
      if (!stagingRepository.getUrl().equals(project.uri(stagingRepositoryDirectory))) {
        throw new GradleException("Repository '" + STAGING_REPOSITORY_NAME
                + "' is managed by 'cn.taketoday.maven-central-publishing'; its URL must not be changed.");
      }
    });
  }

  /**
   * Aggregates subproject staging publications into the shared Maven Central bundle.
   *
   * <p>The plugin owns the {@code mavenCentralStaging} repository and configures
   * it automatically for every subproject applying {@code maven-publish}.</p>
   */
  private void aggregateSubprojectsReleasePublishing(Project rootProject, Provider<Directory> stagingRepositoryDirectory,
          TaskProvider<MavenCentralUploadTask> uploadBundleTask, TaskProvider<Delete> cleanStagingRepositoryTask) {
    if (rootProject.getSubprojects().isEmpty()) {
      return;
    }
    TaskProvider<?> publishAllStagingTask = rootProject.getTasks().named(publishAllPublicationsToMavenCentralStagingRepository);
    int aggregatedPublishingTasks = 0;
    for (Project subproject : rootProject.getSubprojects()) {
      PublishingExtension publishingExtension = subproject.getExtensions().findByType(PublishingExtension.class);
      if (publishingExtension == null) {
        log.debug("Subproject '{}' does not apply 'maven-publish', skipped from aggregation",
                subproject.getPath());
        continue;
      }
      registerStagingRepository(subproject, stagingRepositoryDirectory);
      log.debug("Auto-configured '{}' repository for subproject '{}'", STAGING_REPOSITORY_NAME, subproject.getPath());
      TaskProvider<?> stagingTask = subproject.getTasks()
              .named(publishAllPublicationsToMavenCentralStagingRepository);
      configurePublications(subproject, publishingExtension, uploadBundleTask, cleanStagingRepositoryTask);
      publishAllStagingTask.configure(task -> task.dependsOn(stagingTask));
      aggregatedPublishingTasks++;
      log.debug("Aggregated subproject '{}' publishing task '{}' into project '{}' publishing",
              subproject.getPath(), stagingTask.getName(), rootProject.getPath());
    }
    log.info("Aggregated {} subproject publishing task(s) into root project '{}' Maven Central publishing",
            aggregatedPublishingTasks, rootProject.getName());
  }

  private TaskProvider<Delete> registerCleanStagingRepositoryTask(Project project, Provider<Directory> stagingRepositoryDirectory) {
    return project.getTasks().register("clean" + capitalize(STAGING_REPOSITORY_NAME) + "Repository", Delete.class, task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.setDescription("Cleans the local '" + STAGING_REPOSITORY_NAME + "' repository directory.");
      task.delete(stagingRepositoryDirectory);
    });
  }

  private void configureArchiveOrdering(Project project, Object stagingTask) {
    Project rootProject = project.getRootProject();
    rootProject.getTasks().named(ZIP_BUNDLE_TASK_NAME).configure(task -> task.mustRunAfter(stagingTask));
    rootProject.getTasks().named(TAR_GZ_BUNDLE_TASK_NAME).configure(task -> task.mustRunAfter(stagingTask));
  }

  private TaskProvider<DefaultTask> registerBundleTask(Project project, Provider<Directory> stagingRepositoryDirectory, MavenCentralExtension extension) {
    var zipTask = registerArchiveTask(project, ZIP_BUNDLE_TASK_NAME, Zip.class, stagingRepositoryDirectory, extension, BundleFormat.ZIP);
    var tarTask = registerArchiveTask(project, TAR_GZ_BUNDLE_TASK_NAME, Tar.class, stagingRepositoryDirectory, extension, BundleFormat.TAR_GZ);
    return project.getTasks().register(BUNDLE_TASK_NAME, DefaultTask.class, task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.setDescription("Bundles the local '" + STAGING_REPOSITORY_NAME + "' repository content.");
      task.dependsOn(zipTask, tarTask);
    });
  }

  private <T extends AbstractArchiveTask> TaskProvider<T> registerArchiveTask(Project project, String name, Class<T> type,
          Provider<Directory> stagingRepositoryDirectory, MavenCentralExtension extension, BundleFormat format) {
    return project.getTasks().register(name, type, task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.from(stagingRepositoryDirectory);
      task.exclude("**/maven-metadata.xml*");
      task.getDestinationDirectory().set(extension.getBundle().getDestinationDirectory());
      task.getArchiveFileName().set(extension.getBundle().getArchiveFileName());
      Provider<BundleFormat> bundleFormat = extension.getBundle().getFormat();
      task.onlyIf(ignored -> bundleFormat.get() == format);
      if (task instanceof Tar tar) {
        tar.setCompression(Compression.GZIP);
      }
    });
  }

  private TaskProvider<MavenCentralUploadTask> registerUploadBundleTask(Project project, TaskProvider<DefaultTask> bundleTask,
          Provider<Directory> outputDirectory, MavenCentralExtension extension) {
    var uploadTask = project.getTasks().register(UPLOAD_BUNDLE_TASK_NAME, MavenCentralUploadTask.class, extension);
    uploadTask.configure(task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.setDescription("Uploads, validates and publishes the '" + bundleTask.getName() + "' to Maven Central.");
      task.dependsOn(bundleTask);
      Provider<RegularFile> zipFile = project.getTasks().named(ZIP_BUNDLE_TASK_NAME, Zip.class).flatMap(Zip::getArchiveFile);
      Provider<RegularFile> tarFile = project.getTasks().named(TAR_GZ_BUNDLE_TASK_NAME, Tar.class).flatMap(Tar::getArchiveFile);
      task.getBundleFile().set(extension.getBundle().getFormat()
              .flatMap(format -> format == BundleFormat.ZIP ? zipFile : tarFile));
      task.getDeploymentIdFile().set(outputDirectory.map(it -> it.file(DEPLOYMENT_ID_FILE_NAME)));
    });
    return uploadTask;
  }

  private TaskProvider<?> getPublishToStagingRepositoryTask(Project project, String publicationName) {
    return project.getTasks().named("publish" + capitalize(publicationName) + "PublicationTo" + capitalize(STAGING_REPOSITORY_NAME) + "Repository");
  }

  private TaskProvider<?> getPublishToSnapshotsRepositoryTask(Project project, String publicationName) {
    return project.getTasks().named("publish" + capitalize(publicationName) + "PublicationTo"
            + capitalize(SNAPSHOT_REPOSITORY_NAME) + "Repository");
  }

  private void registerMavenCentralPublicationTask(Project project, String publicationName, Action<Task> dependencyConfig) {
    project.getTasks().register("publish" + capitalize(publicationName) + "PublicationToMavenCentral", task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.setDescription("Publishes Maven publication '" + publicationName + "' to Maven Central.");
      dependencyConfig.execute(task);
    });
  }

  private TaskProvider<?> registerPublishToMavenCentralTask(Project project, TaskProvider<MavenCentralUploadTask> uploadBundleTask) {
    return project.getTasks().register(PUBLISH_TO_MAVEN_CENTRAL_TASK_NAME, task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.setDescription("Publishes all Maven publications produced by this project (and its subprojects) to Maven Central.");
      task.dependsOn(project.getTasks().named(publishAllPublicationsToMavenCentralStagingRepository));
      task.dependsOn(uploadBundleTask);
    });
  }

  /**
   * Returns whether the project uses a SNAPSHOT version.
   *
   * <p>A version is treated as a snapshot when it ends with the
   * {@value #SNAPSHOT_VERSION_SUFFIX} suffix.</p>
   *
   * @param project the project whose version is inspected
   * @return {@code true} if the project version ends with {@code -SNAPSHOT}
   */
  private boolean isSnapshotVersion(Project project) {
    return project.getVersion().toString().endsWith(SNAPSHOT_VERSION_SUFFIX);
  }

  /**
   * Routes Maven Central publishing to the snapshots repository for SNAPSHOT versions.
   *
   * <p>Registers the reserved {@code mavenCentralSnapshots} repository on the root project and
   * every publishing subproject, and points {@code publishToMavenCentral} and the
   * per-publication publish entry points at it, bypassing the staging, bundle and upload chain
   * used for release versions.</p>
   *
   * @param rootProject the root project evaluating and aggregating publications
   * @param extension the shared Maven Central publishing configuration
   */
  private TaskProvider<?> registerSnapshotPublishing(Project rootProject, MavenCentralExtension extension) {
    log.info("Project '{}' version '{}' is a SNAPSHOT, publishing directly to the Maven Central snapshots repository",
            rootProject.getName(), rootProject.getVersion());
    registerSnapshotsRepository(rootProject, extension);
    configureSnapshotPublications(rootProject, rootProject.getExtensions().getByType(PublishingExtension.class));
    TaskProvider<?> publishAllMavenCentralSnapshots = rootProject.getTasks().named(publishAllPublicationsToMavenCentralRepository);
    for (Project subproject : rootProject.getSubprojects()) {
      PublishingExtension publishingExtension = subproject.getExtensions().findByType(PublishingExtension.class);
      if (publishingExtension == null) {
        log.debug("Subproject '{}' does not apply 'maven-publish', skipped from snapshot publishing",
                subproject.getPath());
        continue;
      }
      registerSnapshotsRepository(subproject, extension);
      configureSnapshotPublications(subproject, publishingExtension);
      TaskProvider<?> subprojectAllTask = subproject.getTasks()
              .named(publishAllPublicationsToMavenCentralSnapshotsRepository);
      publishAllMavenCentralSnapshots.configure(task -> task.dependsOn(subprojectAllTask));
    }
    return registerSnapshotPublishToMavenCentralTask(rootProject);
  }

  private void configureSnapshotPublications(Project publicationProject, PublishingExtension publishing) {
    publishing.getPublications().withType(MavenPublication.class).configureEach(publication -> {
      String publicationName = publication.getName();
      var publishToSnapshotsRepositoryTask = getPublishToSnapshotsRepositoryTask(publicationProject, publicationName);
      var legacyPublishToSnapshotsRepositoryTask = publicationProject.getTasks().register(
              "publish" + capitalize(publicationName) + "PublicationTo" + capitalize(SNAPSHOT_TASK_SUFFIX) + "Repository",
              task -> {
                task.setGroup(PUBLISH_TASK_GROUP);
                task.setDescription("Publishes Maven publication '" + publicationName + "' to Maven Central snapshots.");
                task.dependsOn(publishToSnapshotsRepositoryTask);
              });
      registerMavenCentralPublicationTask(publicationProject, publicationName,
              task -> task.dependsOn(legacyPublishToSnapshotsRepositoryTask));
    });
  }

  /**
   * Registers the reserved Maven Central snapshots repository for SNAPSHOT publishing.
   *
   * <p>The repository points at the {@code mvn deploy}-compatible snapshots endpoint and reuses
   * the shared {@code mavenCentral} credentials with basic authentication. Its name matches the
   * {@code mavenCentral} credential identity, so Gradle can resolve the credentials at execution
   * time and configuration caching only stores that identity. Explicitly configured credentials
   * are applied directly, which lets Gradle opt the publishing tasks out of configuration caching
   * as it does for any explicit repository credentials. The repository name is reserved;
   * user-declared repositories with the same name are rejected.</p>
   */
  private void registerSnapshotsRepository(Project project, MavenCentralExtension extension) {
    validateUrl(extension.getSnapshotRepositoryUrl().get(), "snapshotRepositoryUrl");
    PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
    rejectReservedRepository(publishing, project, SNAPSHOT_REPOSITORY_NAME);
    rejectReservedRepository(publishing, project, SNAPSHOT_TASK_SUFFIX);

    publishing.getRepositories().maven(repository -> {
      repository.setName(SNAPSHOT_REPOSITORY_NAME);
      repository.setUrl(extension.getSnapshotRepositoryUrl());
      if (project.getGradle().getStartParameter().isDryRun()) {
        // A dry run never uploads, so do not require publishing credentials for it.
        return;
      }
      if (extension.usesExplicitCredentials()) {
        PasswordCredentials provided = extension.getCredentials().get();
        repository.credentials(credentials -> {
          credentials.setUsername(provided.getUsername());
          credentials.setPassword(provided.getPassword());
        });
      }
      else {
        repository.credentials(PasswordCredentials.class);
      }
    });

    project.getTasks().register(publishAllPublicationsToMavenCentralSnapshotsRepository, task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.setDescription("Publishes all Maven publications produced by this project to Maven Central snapshots.");
      task.dependsOn(project.getTasks().named(publishAllPublicationsToMavenCentralRepository));
    });
  }

  private void rejectReservedRepository(PublishingExtension publishing, Project project, String name) {
    if (publishing.getRepositories().findByName(name) != null) {
      throw new GradleException("Repository '" + name + "' in project '" + project.getName()
              + "' is reserved for 'cn.taketoday.maven-central-publishing' and must not be configured by users.");
    }
  }

  private void validateUrl(URI url, String propertyName) {
    String scheme = url.getScheme();
    if (!url.isAbsolute() || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
      throw new GradleException("Property 'mavenCentral." + propertyName
              + "' must be an absolute HTTP(S) URL: " + url);
    }
  }

  private TaskProvider<?> registerSnapshotPublishToMavenCentralTask(Project project) {
    return project.getTasks().register(PUBLISH_TO_MAVEN_CENTRAL_TASK_NAME, task -> {
      task.setGroup(PUBLISH_TASK_GROUP);
      task.setDescription("Publishes all Maven publications produced by this project (and its subprojects) to Maven Central.");
      task.dependsOn(project.getTasks().named(publishAllPublicationsToMavenCentralSnapshotsRepository));
    });
  }

}
