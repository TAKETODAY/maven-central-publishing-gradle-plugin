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

import org.gradle.api.credentials.PasswordCredentials;
import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import infra.core.io.FileSystemResource;
import infra.core.io.Resource;
import infra.http.HttpHeaders;
import infra.http.HttpRequest;
import infra.util.Assert;
import infra.util.MultiValueMap;
import infra.web.client.ClientResponse;
import infra.web.client.RestClient;

/**
 * Client for the Maven Central Publisher API.
 *
 * <p>This is an internal implementation detail used by
 * {@link MavenCentralUploadTask}; it is not intended to be used directly by
 * build scripts.</p>
 *
 * @author <a href="https://github.com/TAKETODAY">海子 Yang</a>
 * @since 0.0.1
 */
final class MavenCentralPublisherAPI {

  private final RestClient restClient;

  MavenCentralPublisherAPI(URI baseUrl, PasswordCredentials credentials) {
    this(baseUrl, credentials.getUsername(), credentials.getPassword());
  }

  MavenCentralPublisherAPI(URI baseUrl, String username, String password) {
    this.restClient = RestClient.builder().baseURI(baseUrl)
            .defaultHeaders(headers -> headers.setBearerAuth(
                    HttpHeaders.encodeBasicAuth(username, password, StandardCharsets.UTF_8)))
            .build();
  }

  public String upload(File bundleFile, String deploymentName, PublishingType publishingType) {
    MultiValueMap<String, Resource> files = MultiValueMap.forLinkedHashMap();
    files.add("bundle", new FileSystemResource(bundleFile));

    return restClient
            .post()
            .uri("/api/v1/publisher/upload?name={name}&publishingType={publishingType}", deploymentName, publishingType)
            .body(files)
            .exchange((request, response) -> {
              assertStatus(request, response);
              return response.bodyTo(String.class);
            });
  }

  public JSONObject getStatus(String deploymentId) {
    return restClient
            .post()
            .uri("/api/v1/publisher/status?id={id}", deploymentId)
            .exchange((request, response) -> {
              assertStatus(request, response);
              return new JSONObject(response.bodyTo(String.class));
            });
  }

  private static void assertStatus(HttpRequest request, ClientResponse response) {
    Assert.state(response.getStatusCode().is2xxSuccessful(),
            () -> "unexpected response code " + response.getRawStatusCode() + " for " + request.getURI());
  }

}
