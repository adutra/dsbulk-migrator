/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.migrator.utils;

import com.datastax.migrator.settings.ClusterInfo;
import com.datastax.migrator.settings.Credentials;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(SessionUtils.class);

  public static CqlSession createSession(ClusterInfo clusterInfo, Credentials credentials) {
    String clusterName = clusterInfo.isOrigin() ? "origin" : "target";
    try {
      LOGGER.info("Contacting {} cluster...", clusterName);
      CqlSession session = createSessionBuilder(clusterInfo, credentials).build();
      LOGGER.info("Successfully contacted {} cluster", clusterName);
      return session;
    } catch (Exception e) {
      throw new IllegalStateException("Could not contact " + clusterName + " cluster", e);
    }
  }

  private static CqlSessionBuilder createSessionBuilder(
      ClusterInfo clusterInfo, Credentials credentials) {
    DriverConfigLoader loader =
        DriverConfigLoader.programmaticBuilder()
            .withString(
                DefaultDriverOption.SESSION_NAME, clusterInfo.isOrigin() ? "origin" : "target")
            .withString(
                DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS, "DcInferringLoadBalancingPolicy")
            .build();
    CqlSessionBuilder builder = CqlSession.builder().withConfigLoader(loader);
    if (clusterInfo.isAstra()) {
      builder.withCloudSecureConnectBundle(clusterInfo.getBundle());
    } else {
      List<InetSocketAddress> contactPoints = clusterInfo.getContactPoints();
      builder.addContactPoints(contactPoints);
      // limit connectivity to just the contact points to limit network I/O
      builder.withNodeFilter(
          node -> {
            SocketAddress address = node.getEndPoint().resolve();
            return address instanceof InetSocketAddress && contactPoints.contains(address);
          });
    }
    if (credentials != null) {
      builder.withAuthCredentials(
          credentials.getUsername(), String.valueOf(credentials.getPassword()));
    }
    return builder;
  }
}
