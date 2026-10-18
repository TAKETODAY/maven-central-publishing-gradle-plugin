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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;

import javax.inject.Inject;

import infra.web.client.ResourceAccessException;

/**
 * Bundle File upload to Maven Central Task
 *
 * @author <a href="https://github.com/TAKETODAY">海子 Yang</a>
 * @since 0.0.1
 */
@DisableCachingByDefault(because = "Uploading files")
public abstract class MavenCentralUploadTask extends DefaultTask {

  private static final String STATE_VALIDATED = "VALIDATED";

  private static final String STATE_PUBLISHING = "PUBLISHING";

  private static final String STATE_PUBLISHED = "PUBLISHED";

  private static final String STATE_FAILED = "FAILED";

  private final MavenCentralExtension configuration;

  /**
   * Returns the bundle archive containing the Maven publications to upload.
   * The archive format is controlled by {@link MavenCentralExtension.BundleOptions}.
   *
   * @return the Maven Central bundle file
   */
  @InputFile
  @PathSensitive(PathSensitivity.NAME_ONLY)
  public abstract RegularFileProperty getBundleFile();

  /**
   * Returns the shared project publishing configuration.
   *
   * @return the Maven Central extension
   */
  @Internal
  public MavenCentralExtension getConfiguration() {
    return this.configuration;
  }

  /**
   * Returns the file in which the Maven Central deployment ID is written after upload.
   *
   * @return the deployment ID output file
   */
  @OutputFile
  public abstract RegularFileProperty getDeploymentIdFile();

  /**
   * Creates an upload task using the shared project configuration.
   *
   * @param configuration the Maven Central extension
   */
  @Inject
  public MavenCentralUploadTask(MavenCentralExtension configuration) {
    this.configuration = configuration;
    getOutputs().upToDateWhen(task -> false);
  }

  /**
   * Uploads the configured bundle and monitors the resulting deployment until it
   * reaches the configured terminal state.
   *
   * @throws Exception if uploading fails or Maven Central reports a failed deployment
   */
  @TaskAction
  public void run() throws Exception {
    File bundleFile = getBundleFile().get().getAsFile();
    String deploymentName = configuration.getDeploymentName().get();
    PublishingType publishingType = configuration.getPublishingType().get();
    boolean isPublish = publishingType == PublishingType.AUTOMATIC;
    boolean waitUntilFullyPublished = configuration.getWaitUntilFullyPublished().get();
    Duration statusRequestInterval = configuration.getStatusRequestInterval().get();
    int maxStatusRetries = configuration.getMaxStatusRetries().get();
    if (maxStatusRetries < 0) {
      throw new IllegalArgumentException("maxStatusRetries must be nonnegative");
    }
    if (statusRequestInterval.isNegative() || statusRequestInterval.toMillis() == 0) {
      throw new IllegalArgumentException("statusRequestInterval must be at least one millisecond");
    }
    Duration maxDeploymentWait = configuration.getMaxDeploymentWait().get();
    long startNanos = System.nanoTime();
    File deploymentIdFile = getDeploymentIdFile().get().getAsFile();

    var publisherApi = new MavenCentralPublisherAPI(configuration.getPublisherUrl().get(),
            configuration.getCredentials().get());
    String deploymentId = publisherApi.upload(bundleFile, deploymentName, publishingType);
    Files.writeString(deploymentIdFile.toPath(), deploymentId);

    String message = "maven central deployment id: " + deploymentId;
    getLogger().quiet(message);
    Set<String> expectedStates = !isPublish ? Collections.singleton(STATE_VALIDATED) : !waitUntilFullyPublished ? Set.of(STATE_PUBLISHING, STATE_PUBLISHED) : Collections.singleton(STATE_PUBLISHED);
    Set<String> unexpectedStates = !isPublish ? Set.of(STATE_PUBLISHING, STATE_PUBLISHED, STATE_FAILED)
            : Collections.singleton(STATE_FAILED);
    String previousState = null;
    int errorCount = 0;
    while (true) {
      if (!maxDeploymentWait.isNegative() && !maxDeploymentWait.isZero()
              && Duration.ofNanos(System.nanoTime() - startNanos).compareTo(maxDeploymentWait) > 0) {
        throw new IllegalStateException(message + ", deployment did not reach "
                + String.join(" or ", expectedStates) + " within " + maxDeploymentWait
                + (previousState == null ? "" : ", last observed state: " + previousState));
      }
      JSONObject status;
      try {
        status = publisherApi.getStatus(deploymentId);
      }
      catch (ResourceAccessException | IllegalStateException e) {
        if (++errorCount > maxStatusRetries) {
          throw e;
        }
        getLogger().lifecycle(message + ", state request failed for " + errorCount + " times, treating as temporary error: " + e.getMessage());
        sleep(statusRequestInterval);
        continue;
      }
      errorCount = 0;
      String state = status.getString("deploymentState");
      String stateMessage = message + ", state: " + state;
      if (expectedStates.contains(state)) {
        getLogger().lifecycle(stateMessage);
        break;
      }
      if (unexpectedStates.contains(state)) {
        String failedMessage = stateMessage + " (expected " + String.join(" or ", expectedStates) + ")";
        getLogger().lifecycle(failedMessage);
        JSONObject errors = status.optJSONObject("errors");
        String errorMessage = errors == null ? failedMessage : failedMessage + ", errors:\n" + errors.toString(2);
        throw new IllegalStateException(errorMessage);
      }
      String waitingMessage = stateMessage + " (waiting for " + String.join(" or ", expectedStates) + ")";
      if (!state.equals(previousState)) {
        getLogger().lifecycle(waitingMessage);
      }
      else {
        getLogger().info(waitingMessage);
      }
      previousState = state;
      sleep(statusRequestInterval);
    }
  }

  private static void sleep(Duration duration) throws InterruptedException {
    try {
      Thread.sleep(duration.toMillis());
    }
    catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw ex;
    }
  }
}
