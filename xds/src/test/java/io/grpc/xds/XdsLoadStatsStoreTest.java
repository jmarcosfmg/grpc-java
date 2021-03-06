/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.XdsLoadStatsStore.StatsCounter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link XdsLoadStatsStore}. */
@RunWith(JUnit4.class)
public class XdsLoadStatsStoreTest {
  private static final String SERVICE_NAME = "api.google.com";
  private static final Locality LOCALITY1 =
      Locality.newBuilder()
          .setRegion("test_region1")
          .setZone("test_zone")
          .setSubZone("test_subzone")
          .build();
  private static final Locality LOCALITY2 =
      Locality.newBuilder()
          .setRegion("test_region2")
          .setZone("test_zone")
          .setSubZone("test_subzone")
          .build();
  private ConcurrentMap<Locality, StatsCounter> localityLoadCounters;
  private ConcurrentMap<String, AtomicLong> dropCounters;
  private XdsLoadStatsStore loadStore;

  @Before
  public void setUp() {
    localityLoadCounters = new ConcurrentHashMap<>();
    dropCounters = new ConcurrentHashMap<>();
    loadStore = new XdsLoadStatsStore(SERVICE_NAME, localityLoadCounters, dropCounters);
  }

  private static UpstreamLocalityStats buildUpstreamLocalityStats(Locality locality,
      long callsSucceed,
      long callsInProgress,
      long callsFailed,
      long callsIssued,
      @Nullable List<EndpointLoadMetricStats> metrics) {
    UpstreamLocalityStats.Builder builder =
        UpstreamLocalityStats.newBuilder()
            .setLocality(locality)
            .setTotalSuccessfulRequests(callsSucceed)
            .setTotalErrorRequests(callsFailed)
            .setTotalRequestsInProgress(callsInProgress)
            .setTotalIssuedRequests(callsIssued);
    if (metrics != null) {
      builder.addAllLoadMetricStats(metrics);
    }
    return builder.build();
  }

  private static DroppedRequests buildDroppedRequests(String category, long counts) {
    return DroppedRequests.newBuilder()
        .setCategory(category)
        .setDroppedCount(counts)
        .build();
  }

  private static ClusterStats buildClusterStats(
      @Nullable List<UpstreamLocalityStats> upstreamLocalityStatsList,
      @Nullable List<DroppedRequests> droppedRequestsList) {
    ClusterStats.Builder clusterStatsBuilder = ClusterStats.newBuilder()
        .setClusterName(SERVICE_NAME);
    if (upstreamLocalityStatsList != null) {
      clusterStatsBuilder.addAllUpstreamLocalityStats(upstreamLocalityStatsList);
    }
    if (droppedRequestsList != null) {
      long dropCount = 0;
      for (DroppedRequests drop : droppedRequestsList) {
        dropCount += drop.getDroppedCount();
        clusterStatsBuilder.addDroppedRequests(drop);
      }
      clusterStatsBuilder.setTotalDroppedRequests(dropCount);
    }
    return clusterStatsBuilder.build();
  }

  private static void assertClusterStatsEqual(ClusterStats expected, ClusterStats actual) {
    assertThat(actual.getClusterName()).isEqualTo(expected.getClusterName());
    assertThat(actual.getLoadReportInterval()).isEqualTo(expected.getLoadReportInterval());
    assertThat(actual.getDroppedRequestsCount()).isEqualTo(expected.getDroppedRequestsCount());
    assertThat(new HashSet<>(actual.getDroppedRequestsList()))
        .isEqualTo(new HashSet<>(expected.getDroppedRequestsList()));
    assertUpstreamLocalityStatsListsEqual(actual.getUpstreamLocalityStatsList(),
        expected.getUpstreamLocalityStatsList());
  }

  private static void assertUpstreamLocalityStatsListsEqual(List<UpstreamLocalityStats> expected,
      List<UpstreamLocalityStats> actual) {
    assertThat(actual).hasSize(expected.size());
    Map<Locality, UpstreamLocalityStats> expectedLocalityStats = new HashMap<>();
    for (UpstreamLocalityStats stats : expected) {
      expectedLocalityStats.put(stats.getLocality(), stats);
    }
    for (UpstreamLocalityStats stats : actual) {
      UpstreamLocalityStats expectedStats = expectedLocalityStats.get(stats.getLocality());
      assertThat(expectedStats).isNotNull();
      assertUpstreamLocalityStatsEqual(stats, expectedStats);
    }
  }

  private static void assertUpstreamLocalityStatsEqual(UpstreamLocalityStats expected,
      UpstreamLocalityStats actual) {
    assertThat(actual.getLocality()).isEqualTo(expected.getLocality());
    assertThat(actual.getTotalSuccessfulRequests())
        .isEqualTo(expected.getTotalSuccessfulRequests());
    assertThat(actual.getTotalRequestsInProgress())
        .isEqualTo(expected.getTotalRequestsInProgress());
    assertThat(actual.getTotalErrorRequests()).isEqualTo(expected.getTotalErrorRequests());
    assertThat(new HashSet<>(actual.getLoadMetricStatsList()))
        .isEqualTo(new HashSet<>(expected.getLoadMetricStatsList()));
  }

  @Test
  public void addAndGetAndRemoveLocality() {
    loadStore.addLocality(LOCALITY1);
    assertThat(localityLoadCounters).containsKey(LOCALITY1);

    // Adding the same locality counter again causes an exception.
    try {
      loadStore.addLocality(LOCALITY1);
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat()
          .contains("An active counter for locality " + LOCALITY1 + " already exists");
    }

    assertThat(loadStore.getLocalityCounter(LOCALITY1))
        .isSameInstanceAs(localityLoadCounters.get(LOCALITY1));
    assertThat(loadStore.getLocalityCounter(LOCALITY2)).isNull();

    // Removing an non-existing locality counter causes an exception.
    try {
      loadStore.removeLocality(LOCALITY2);
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat()
          .contains("No active counter for locality " + LOCALITY2 + " exists");
    }

    // Removing the locality counter only mark it as inactive, but not throw it away.
    loadStore.removeLocality(LOCALITY1);
    assertThat(localityLoadCounters.get(LOCALITY1).isActive()).isFalse();

    // Removing an inactive locality counter causes an exception.
    try {
      loadStore.removeLocality(LOCALITY1);
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat()
          .contains("No active counter for locality " + LOCALITY1 + " exists");
    }

    // Adding it back simply mark it as active again.
    loadStore.addLocality(LOCALITY1);
    assertThat(localityLoadCounters.get(LOCALITY1).isActive()).isTrue();
  }

  @Test
  public void removeInactiveCountersAfterGeneratingLoadReport() {
    StatsCounter counter1 = mock(StatsCounter.class);
    when(counter1.isActive()).thenReturn(true);
    when(counter1.snapshot()).thenReturn(ClientLoadSnapshot.EMPTY_SNAPSHOT);
    StatsCounter counter2 = mock(StatsCounter.class);
    when(counter2.isActive()).thenReturn(false);
    when(counter2.snapshot()).thenReturn(ClientLoadSnapshot.EMPTY_SNAPSHOT);
    localityLoadCounters.put(LOCALITY1, counter1);
    localityLoadCounters.put(LOCALITY2, counter2);
    loadStore.generateLoadReport();
    assertThat(localityLoadCounters).containsKey(LOCALITY1);
    assertThat(localityLoadCounters).doesNotContainKey(LOCALITY2);
  }

  @Test
  public void loadReportMatchesSnapshots() {
    StatsCounter counter1 = mock(StatsCounter.class);
    when(counter1.isActive()).thenReturn(true);
    when(counter1.snapshot())
        .thenReturn(new ClientLoadSnapshot(4315, 3421, 23, 593),
            new ClientLoadSnapshot(0, 543, 0, 0));
    StatsCounter counter2 = mock(StatsCounter.class);
    when(counter2.snapshot()).thenReturn(new ClientLoadSnapshot(41234, 432, 431, 702),
        new ClientLoadSnapshot(0, 432, 0, 0));
    when(counter2.isActive()).thenReturn(true);
    localityLoadCounters.put(LOCALITY1, counter1);
    localityLoadCounters.put(LOCALITY2, counter2);

    ClusterStats expectedReport =
        buildClusterStats(
            Arrays.asList(
                buildUpstreamLocalityStats(LOCALITY1, 4315, 3421, 23, 593, null),
                buildUpstreamLocalityStats(LOCALITY2, 41234, 432, 431, 702, null)
            ),
            null);

    assertClusterStatsEqual(expectedReport, loadStore.generateLoadReport());
    verify(counter1).snapshot();
    verify(counter2).snapshot();

    expectedReport =
        buildClusterStats(
            Arrays.asList(
                buildUpstreamLocalityStats(LOCALITY1, 0, 543, 0, 0, null),
                buildUpstreamLocalityStats(LOCALITY2, 0, 432, 0, 0, null)
            ),
            null);
    assertClusterStatsEqual(expectedReport, loadStore.generateLoadReport());
    verify(counter1, times(2)).snapshot();
    verify(counter2, times(2)).snapshot();
  }

  @Test
  public void recordingDroppedRequests() {
    int numLbDrop = 123;
    int numThrottleDrop = 456;
    for (int i = 0; i < numLbDrop; i++) {
      loadStore.recordDroppedRequest("lb");
    }
    for (int i = 0; i < numThrottleDrop; i++) {
      loadStore.recordDroppedRequest("throttle");
    }
    assertThat(dropCounters.get("lb").get()).isEqualTo(numLbDrop);
    assertThat(dropCounters.get("throttle").get()).isEqualTo(numThrottleDrop);
    ClusterStats expectedLoadReport =
        buildClusterStats(null,
            Arrays.asList(buildDroppedRequests("lb", numLbDrop),
                buildDroppedRequests("throttle", numThrottleDrop)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport());
    assertThat(dropCounters.get("lb").get()).isEqualTo(0);
    assertThat(dropCounters.get("throttle").get()).isEqualTo(0);
  }
}
