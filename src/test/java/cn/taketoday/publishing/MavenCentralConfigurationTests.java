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

import com.sun.net.httpserver.HttpServer;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MavenCentralConfigurationTests {

  @TempDir
  Path directory;

  @Test
  void taskUsesSharedExtensionAsOnlyConfiguration() {
    Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    project.getPluginManager().apply(MavenCentralPublishingPlugin.class);
    project.setVersion("1.2.3");
    var extension = project.getExtensions().getByType(MavenCentralExtension.class);
    ((ProjectInternal) project).evaluate();
    new MavenCentralPublishingPlugin().configurePublishing(project, extension);
    var task = (MavenCentralUploadTask) project.getTasks().getByName("uploadMavenCentralBundle");
    project.setGroup("example");

    assertThat(task.getConfiguration()).isSameAs(extension);
    assertThat(extension.getDeploymentName().get()).isEqualTo("example:" + project.getName() + ":1.2.3");
    assertThat(extension.getPublishingType().get()).isEqualTo(PublishingType.AUTOMATIC);
    assertThat(extension.getWaitUntilFullyPublished().get()).isFalse();
    assertThat(extension.getStatusRequestInterval().get()).isEqualTo(Duration.ofSeconds(1));
    assertThat(extension.getMaxStatusRetries().get()).isEqualTo(3);
    assertThat(extension.getMaxDeploymentWait().get()).isEqualTo(Duration.ofMinutes(60));
    assertThat(extension.getBaseUrl().get()).isEqualTo(URI.create("https://central.sonatype.com"));
    assertThat(extension.getSnapshotRepositoryUrl().get())
            .isEqualTo(URI.create("https://central.sonatype.com/repository/maven-snapshots"));

    extension.credentials(credentials -> {
      credentials.setUsername("custom-user");
      credentials.setPassword("custom-password");
    });
    assertThat(task.getConfiguration().getCredentials().get().getUsername()).isEqualTo("custom-user");
    assertThat(task.getConfiguration().getCredentials().get().getPassword()).isEqualTo("custom-password");
    extension.getPublishingType().set(PublishingType.USER_MANAGED);
    assertThat(task.getConfiguration().getPublishingType().get()).isEqualTo(PublishingType.USER_MANAGED);
    extension.getWaitUntilFullyPublished().set(true);
    extension.getStatusRequestInterval().set(Duration.ofSeconds(5));
    extension.getMaxStatusRetries().set(7);
    extension.getBaseUrl().set(URI.create("https://example.test/"));
    extension.getDeploymentName().set("custom-deployment");

    assertThat(task.getConfiguration().getWaitUntilFullyPublished().get()).isTrue();
    assertThat(task.getConfiguration().getStatusRequestInterval().get()).isEqualTo(Duration.ofSeconds(5));
    assertThat(task.getConfiguration().getMaxStatusRetries().get()).isEqualTo(7);
    assertThat(task.getConfiguration().getBaseUrl().get()).isEqualTo(URI.create("https://example.test/"));
    assertThat(task.getConfiguration().getDeploymentName().get()).isEqualTo("custom-deployment");
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 2 })
  void stopsAfterConfiguredConsecutiveFailures(int retries) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = server(requests, false);
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getMaxStatusRetries().set(retries);

      assertThatIllegalStateException().isThrownBy(task::run).withMessageContaining("503");
      assertThat(requests.get()).isEqualTo(retries + 1);
    }
    finally {
      server.stop(0);
    }
  }

  @Test
  void successfulStatusResetsRetryCountAndWaitsUntilPublished() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = server(requests, true);
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getMaxStatusRetries().set(1);
      task.getConfiguration().getWaitUntilFullyPublished().set(true);

      task.run();

      assertThat(requests.get()).isEqualTo(4);
      assertThat(Files.readString(task.getDeploymentIdFile().get().getAsFile().toPath())).isEqualTo("deployment-id");
    }
    finally {
      server.stop(0);
    }
  }

  @Test
  void invalidPollingOptionsFailBeforeUploading() throws Exception {
    MavenCentralUploadTask task = uploadTask(null);
    task.getConfiguration().getMaxStatusRetries().set(-1);
    assertThatIllegalArgumentException().isThrownBy(task::run).withMessageContaining("maxStatusRetries");

    task.getConfiguration().getMaxStatusRetries().set(0);
    task.getConfiguration().getStatusRequestInterval().set(Duration.ZERO);
    assertThatIllegalArgumentException().isThrownBy(task::run).withMessageContaining("statusRequestInterval");
  }

  @ParameterizedTest
  @CsvSource({ "AUTOMATIC,false,PUBLISHING", "AUTOMATIC,false,PUBLISHED",
          "AUTOMATIC,true,PUBLISHED", "USER_MANAGED,false,VALIDATED", "USER_MANAGED,true,VALIDATED" })
  @Timeout(10)
  void completesAtExpectedState(PublishingType type, boolean waitUntilPublished, String state) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> uploadQuery = new AtomicReference<>();
    HttpServer server = stateServer(requests, uploadQuery, "{\"deploymentState\":\"" + state + "\"}");
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getPublishingType().set(type);
      task.getConfiguration().getWaitUntilFullyPublished().set(waitUntilPublished);

      task.run();

      assertThat(requests.get()).isEqualTo(1);
      assertThat(uploadQuery.get()).contains("publishingType=" + type);
      assertThat(task.getDeploymentIdFile().get().getAsFile()).hasContent("deployment-id");
    }
    finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "PUBLISHING", "PUBLISHED", "FAILED" })
  void manualPublishingRejectsUnexpectedTerminalStates(String state) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = stateServer(requests, new AtomicReference<>(), "{\"deploymentState\":\"" + state + "\"}");
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getPublishingType().set(PublishingType.USER_MANAGED);

      assertThatIllegalStateException().isThrownBy(task::run)
              .withMessageContaining(state).withMessageContaining("expected VALIDATED");
      assertThat(requests.get()).isEqualTo(1);
    }
    finally {
      server.stop(0);
    }
  }

  @Test
  void failedDeploymentReportsValidationErrorsAndPreservesDeploymentId() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = stateServer(requests, new AtomicReference<>(),
            "{\"deploymentState\":\"FAILED\",\"errors\":{\"example:library:1.0\":[\"Missing signature\"]}}");
    try {
      MavenCentralUploadTask task = uploadTask(server);

      assertThatIllegalStateException().isThrownBy(task::run)
              .withMessageContaining("FAILED").withMessageContaining("Missing signature")
              .withMessageContaining("example:library:1.0");
      assertThat(requests.get()).isEqualTo(1);
      assertThat(task.getDeploymentIdFile().get().getAsFile()).hasContent("deployment-id");
    }
    finally {
      server.stop(0);
    }
  }

  @Test
  @Timeout(10)
  void timesOutWhileWaitingForTerminalState() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    String[] pending = Collections.nCopies(2000, "{\"deploymentState\":\"PENDING\"}").toArray(new String[0]);
    HttpServer server = stateServer(requests, new AtomicReference<>(), pending);
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getMaxDeploymentWait().set(Duration.ofMillis(500));

      assertThatIllegalStateException().isThrownBy(task::run)
              .withMessageContaining("within").withMessageContaining("last observed state: PENDING");
    }
    finally {
      server.stop(0);
    }
  }

  @Test
  @Timeout(10)
  void pendingStatesDoNotConsumeFailureRetries() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = stateServer(requests, new AtomicReference<>(),
            "{\"deploymentState\":\"PENDING\"}", "{\"deploymentState\":\"VALIDATING\"}",
            "{\"deploymentState\":\"VALIDATING\"}", "{\"deploymentState\":\"PUBLISHING\"}",
            "{\"deploymentState\":\"PUBLISHED\"}");
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getMaxStatusRetries().set(0);
      task.getConfiguration().getWaitUntilFullyPublished().set(true);

      task.run();

      assertThat(requests.get()).isEqualTo(5);
    }
    finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = { -1, 0, 999999 })
  void rejectsIntervalsShorterThanOneMillisecondBeforeNetworkRequest(long nanos) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> uploadQuery = new AtomicReference<>();
    HttpServer server = stateServer(requests, uploadQuery, "{\"deploymentState\":\"PUBLISHED\"}");
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getStatusRequestInterval().set(Duration.ofNanos(nanos));

      assertThatIllegalArgumentException().isThrownBy(task::run).withMessageContaining("statusRequestInterval");
      assertThat(uploadQuery.get()).isNull();
      assertThat(requests.get()).isZero();
      assertThat(task.getDeploymentIdFile().get().getAsFile()).doesNotExist();
    }
    finally {
      server.stop(0);
    }
  }

  @Test
  @Timeout(5)
  void preservesInterruptWhenPollingIsCancelled() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = stateServer(requests, new AtomicReference<>(), "{\"deploymentState\":\"PENDING\"}");
    try {
      MavenCentralUploadTask task = uploadTask(server);
      task.getConfiguration().getStatusRequestInterval().set(Duration.ofSeconds(30));
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicReference<Boolean> interrupted = new AtomicReference<>();
      Thread runner = new Thread(() -> {
        try {
          task.run();
        }
        catch (Throwable ex) {
          failure.set(ex);
          interrupted.set(Thread.currentThread().isInterrupted());
        }
      });
      runner.start();
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (requests.get() == 0 && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      runner.interrupt();
      runner.join(2000);

      assertThat(runner.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(InterruptedException.class);
      assertThat(interrupted.get()).isTrue();
    }
    finally {
      server.stop(0);
    }
  }

  private HttpServer stateServer(AtomicInteger requests, AtomicReference<String> uploadQuery,
          String... statuses) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/publisher/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      boolean upload = exchange.getRequestURI().getPath().endsWith("/upload");
      String response;
      if (upload) {
        uploadQuery.set(exchange.getRequestURI().getQuery());
        response = "deployment-id";
      }
      else {
        int index = requests.getAndIncrement();
        response = index < statuses.length ? statuses[index] : "{\"deploymentState\":\"FAILED\"}";
      }
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(upload ? 201 : 200, bytes.length);
      try (var body = exchange.getResponseBody()) {
        body.write(bytes);
      }
    });
    server.start();
    return server;
  }

  private MavenCentralUploadTask uploadTask(HttpServer server) throws Exception {
    Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    var configuration = project.getObjects().newInstance(MavenCentralExtension.class);
    var task = project.getTasks().register("upload", MavenCentralUploadTask.class, configuration).get();
    configuration.credentials(credentials -> {
      credentials.setUsername("test-user");
      credentials.setPassword("test-password");
    });
    task.getBundleFile().set(Files.write(directory.resolve("bundle.zip"), new byte[] { 1 }).toFile());
    configuration.getDeploymentName().set("test-deployment");
    task.getDeploymentIdFile().set(directory.resolve("deployment-id.txt").toFile());
    configuration.getStatusRequestInterval().set(Duration.ofMillis(1));
    if (server != null) {
      configuration.getBaseUrl().set(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }
    return task;
  }

  private HttpServer server(AtomicInteger requests, boolean recover) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/publisher/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      boolean upload = exchange.getRequestURI().getPath().endsWith("/upload");
      int request = upload ? 0 : requests.incrementAndGet();
      int status = upload ? 201 : recover && request % 2 == 0 ? 200 : 503;
      String state = request == 4 ? "PUBLISHED" : "PUBLISHING";
      byte[] response = (upload ? "deployment-id" : "{\"deploymentState\":\"" + state + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      try (var body = exchange.getResponseBody()) {
        body.write(response);
      }
    });
    server.start();
    return server;
  }
}
