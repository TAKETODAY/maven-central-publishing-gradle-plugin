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
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.util.internal.ConfigureUtil;

import java.net.URI;
import java.time.Duration;

import javax.inject.Inject;

import groovy.lang.Closure;

/**
 * Configures Maven Central publishing for this project.
 *
 * <p>The extension is exposed as {@code mavenCentral} and controls bundle creation,
 * Portal deployment, deployment status polling, and the credentials used by both
 * release and snapshot publishing.</p>
 *
 * @author <a href="https://github.com/TAKETODAY">海子 Yang</a>
 * @since 0.0.4
 */
public abstract class MavenCentralExtension {

  static final URI DEFAULT_PUBLISHER_URL = URI.create("https://central.sonatype.com");

  static final URI DEFAULT_SNAPSHOT_REPOSITORY_URL =
          URI.create("https://central.sonatype.com/repository/maven-snapshots");

  private final BundleOptions bundle;

  private boolean explicitCredentials;

  /**
   * Creates Maven Central publishing options.
   *
   * @param objects the factory used to create configuration properties
   */
  @Inject
  public MavenCentralExtension(ObjectFactory objects) {
    this.bundle = new BundleOptions(objects);
    getPublishingType().convention(PublishingType.AUTOMATIC);
    getWaitUntilFullyPublished().convention(false);
    getStatusRequestInterval().convention(Duration.ofSeconds(1));
    getMaxStatusRetries().convention(3);
    getMaxDeploymentWait().convention(Duration.ofMinutes(60));
    getPublisherUrl().convention(DEFAULT_PUBLISHER_URL);
    getSnapshotRepositoryUrl().convention(DEFAULT_SNAPSHOT_REPOSITORY_URL);
    getBundle().getFormat().convention(BundleFormat.ZIP);
    getBundle().getArchiveFileName().convention(
            getBundle().getFormat().map(BundleFormat::getDefaultFileName));
    bundle.getDestinationDirectory().convention(objects.directoryProperty());
  }

  /**
   * Returns the deployment mode (default: {@link PublishingType#AUTOMATIC}).
   *
   * <p>{@code AUTOMATIC} asks Maven Central to publish the deployment after
   * validation. {@code USER_MANAGED} leaves the deployment awaiting manual
   * publication.</p>
   *
   * @return the publishing type
   */
  public abstract Property<PublishingType> getPublishingType();

  /**
   * Returns the credentials used to authenticate with Maven Central.
   *
   * <p>When not configured explicitly, the value is obtained from the Gradle
   * {@code mavenCentral} credentials.</p>
   *
   * @return the token credentials
   */
  public abstract Property<PasswordCredentials> getCredentials();

  /**
   * Configures explicit Maven Central credentials.
   *
   * @param action the credentials configuration
   */
  public void credentials(Action<? super PasswordCredentials> action) {
    PasswordCredentials credentials = getObjects().newInstance(PasswordCredentials.class);
    action.execute(credentials);
    getCredentials().set(credentials);
    explicitCredentials = true;
  }

  /**
   * Returns whether credentials were set explicitly through {@link #credentials(Action)} instead
   * of the default Gradle {@code mavenCentral} credentials provider.
   *
   * @return {@code true} if explicit credentials were configured
   */
  boolean usesExplicitCredentials() {
    return explicitCredentials;
  }

  /**
   * Returns the factory used to create credentials.
   *
   * @return the object factory
   */
  @Inject
  protected abstract ObjectFactory getObjects();

  /**
   * Returns whether to wait for {@code PUBLISHED} instead of {@code PUBLISHING}
   * (default: {@code false}). This setting is used only for automatic publishing.
   *
   * @return the fully published waiting flag
   */
  public abstract Property<Boolean> getWaitUntilFullyPublished();

  /**
   * Returns the interval between deployment status requests (default: one second).
   * The value must be at least one millisecond.
   *
   * @return the status request interval
   */
  public abstract Property<Duration> getStatusRequestInterval();

  /**
   * Returns the number of retries allowed after consecutive status request failures
   * (default: three). Zero disables retries; a successful request resets the failure
   * count.
   *
   * @return the nonnegative retry limit
   */
  public abstract Property<Integer> getMaxStatusRetries();

  /**
   * Returns the maximum time to wait for a deployment to reach a terminal state
   * (default: 60 minutes). A nonpositive duration disables the timeout, allowing
   * the task to poll indefinitely.
   *
   * @return the deployment wait timeout
   */
  public abstract Property<Duration> getMaxDeploymentWait();

  /**
   * Returns the Maven Central Publisher URL
   * (default: {@code https://central.sonatype.com}).
   *
   * @return the Publisher URL
   */
  public abstract Property<URI> getPublisherUrl();

  /**
   * Returns the Maven Central snapshots repository URL
   * (default: {@code https://central.sonatype.com/repository/maven-snapshots}).
   *
   * @return the snapshots repository URL
   */
  public abstract Property<URI> getSnapshotRepositoryUrl();

  /**
   * Returns the deployment name (default: {@code group:name:version}).
   *
   * @return the deployment name
   */
  public abstract Property<String> getDeploymentName();

  /**
   * Returns the options used to create the Maven Central bundle archive.
   *
   * @return bundle options
   */
  public BundleOptions getBundle() {
    return bundle;
  }

  /**
   * Configures the Maven Central bundle archive.
   *
   * @param action the bundle configuration
   */
  public void bundle(Action<? super BundleOptions> action) {
    action.execute(bundle);
  }

  /**
   * Configures the Maven Central bundle archive from a Groovy closure.
   *
   * @param closure the bundle configuration closure
   */
  public void bundle(Closure<?> closure) {
    ConfigureUtil.configure(closure, bundle);
  }

  /**
   * Options for the Maven Central bundle archive.
   */
  public static class BundleOptions {

    private final Property<BundleFormat> format;

    private final Property<String> archiveFileName;

    private final DirectoryProperty destinationDirectory;

    BundleOptions(ObjectFactory objects) {
      format = objects.property(BundleFormat.class);
      archiveFileName = objects.property(String.class);
      destinationDirectory = objects.directoryProperty();
    }

    /**
     * Returns the archive format used for the bundle.
     *
     * @return the archive format
     */
    public Property<BundleFormat> getFormat() {
      return format;
    }

    /**
     * Returns the name of the generated archive file.
     *
     * @return the archive file name
     */
    public Property<String> getArchiveFileName() {
      return archiveFileName;
    }

    /**
     * Returns the directory in which the generated archive is written.
     *
     * @return the archive destination directory
     */
    public DirectoryProperty getDestinationDirectory() {
      return destinationDirectory;
    }
  }
}
