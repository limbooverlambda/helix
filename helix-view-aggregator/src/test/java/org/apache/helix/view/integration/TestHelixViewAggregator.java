package org.apache.helix.view.integration;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixException;
import org.apache.helix.NotificationContext;
import org.apache.helix.PropertyType;
import org.apache.helix.TestHelper;
import org.apache.helix.api.config.ViewClusterSourceConfig;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.manager.zk.ZKHelixManager;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.Message;
import org.apache.helix.participant.statemachine.StateModelParser;
import org.apache.helix.view.mock.MockViewClusterSpectator;
import org.apache.helix.view.statemodel.DistViewAggregatorStateModel;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestHelixViewAggregator extends ViewAggregatorIntegrationTestBase {
  private static final int numSourceCluster = 3;
  private static final String stateModel = "LeaderStandby";
  private static final int numResourcePerSourceCluster = 3;
  private static final int numResourcePartition = 3;
  private static final int numReplicaPerResourcePartition = 2;
  private static final String resourceNamePrefix = "testResource";
  private static final String viewClusterName = "ViewCluster-TestHelixViewAggregator";
  private static final StateModelParser stateModelParser = new StateModelParser();
  private int _viewClusterRefreshPeriodSec = 5;
  private ConfigAccessor _configAccessor;
  private HelixAdmin _helixAdmin;
  private MockViewClusterSpectator _monitor;
  private final Set<String> _allResources = new HashSet<>();
  // TODO: add test coverage on multiple statemodel instances for different view clusters
  private DistViewAggregatorStateModel _viewAggregatorStateModel;

  @BeforeClass
  public void beforeClass() throws Exception {
    // Set up source clusters
    super.beforeClass();

    // Setup tools
    _configAccessor = new ConfigAccessor(_gZkClient);
    _helixAdmin = new ZKHelixAdmin(_gZkClient);

    // Set up view cluster
    _gSetupTool.addCluster(viewClusterName, true);
    ClusterConfig viewClusterConfig = new ClusterConfig(viewClusterName);
    viewClusterConfig.setViewCluster();
    viewClusterConfig.setViewClusterRefreshPeriod(_viewClusterRefreshPeriodSec);
    List<ViewClusterSourceConfig> sourceConfigs = new ArrayList<>();
    for (String sourceClusterName : _allSourceClusters) {
      // We are going to aggregate all supported properties
      sourceConfigs.add(new ViewClusterSourceConfig(sourceClusterName, ZK_ADDR,
          ViewClusterSourceConfig.getValidPropertyTypes()));
    }
    viewClusterConfig.setViewClusterSourceConfigs(sourceConfigs);
    _configAccessor.setClusterConfig(viewClusterName, viewClusterConfig);

    // Set up view cluster monitor
    _monitor = new MockViewClusterSpectator(viewClusterName, ZK_ADDR);

    _viewAggregatorStateModel = new DistViewAggregatorStateModel(ZK_ADDR);
    triggerViewAggregatorStateTransition("OFFLINE", "STANDBY");
  }

  @AfterClass
  public void afterClass() throws Exception {
    _monitor.shutdown();
    super.afterClass();
  }

  @Test
  public void testHelixViewAggregator() throws Exception {
    initiateViewAggregator();
    createResourceAndTriggerRebalance();
    removeResourceFromCluster();
    modifyViewClusterConfig();
    simulateViewAggregatorServiceCrashRestart();
    stopViewAggregator();
  }

  @Test(dependsOnMethods = "testHelixViewAggregator")
  public void testRemoteDataRemovalAndRefresh() throws Exception {
    HelixDataAccessor accessor = new ZKHelixDataAccessor(viewClusterName, _baseAccessor);
    // Start view aggregator
    triggerViewAggregatorStateTransition("STANDBY", "LEADER");
    // Wait for refresh and verify
    Thread.sleep((_viewClusterRefreshPeriodSec + 2) * 1000);
    // remove live instances from view cluster zk data, wait for next refresh trigger
    Assert.assertTrue(accessor.removeProperty(accessor.keyBuilder().liveInstances()));
    Thread.sleep((_viewClusterRefreshPeriodSec + 2) * 1000);
    Assert.assertTrue(accessor.getChildNames(accessor.keyBuilder().liveInstances()).size() > 0);

    Assert.assertTrue(accessor.removeProperty(accessor.keyBuilder().externalViews()));
    Thread.sleep((_viewClusterRefreshPeriodSec + 2) * 1000);
    Assert.assertTrue(accessor.getChildNames(accessor.keyBuilder().externalViews()).size() > 0);
    // Stop view aggregator
    stopViewAggregator();
  }

  private void stopViewAggregator() throws Exception {
    triggerViewAggregatorStateTransition("LEADER", "STANDBY");
  }

  private void simulateViewAggregatorServiceCrashRestart() throws Exception {
    // Simulate view aggregator service crashed and got reset
    stopViewAggregator();
    _viewAggregatorStateModel.rollbackOnError(
        new Message(Message.MessageType.STATE_TRANSITION, "test"), new NotificationContext(null),
        null);
    _viewAggregatorStateModel.updateState("ERROR");
    triggerViewAggregatorStateTransition("ERROR", "OFFLINE");

    // Change happened during view aggregator down
    List<PropertyType> newProperties =
        new ArrayList<>(ViewClusterSourceConfig.getValidPropertyTypes());
    newProperties.remove(PropertyType.INSTANCES);
    resetViewClusterConfig(_viewClusterRefreshPeriodSec, newProperties);
    MockParticipantManager participant = _allParticipants.get(0);

    participant.syncStop();
    _helixAdmin.enableInstance(participant.getClusterName(), participant.getInstanceName(), false);
    _gSetupTool.dropInstanceFromCluster(participant.getClusterName(),
        participant.getInstanceName());
    rebalanceResources();

    Set<String> allParticipantNames = getParticipantInstanceNames();
    allParticipantNames.remove(participant.getInstanceName());

    // Restart helix view aggregator
    triggerViewAggregatorStateTransition("OFFLINE", "STANDBY");
    triggerViewAggregatorStateTransition("STANDBY", "LEADER");

    // Wait for refresh and verify
    Predicate<MockViewClusterSpectator> checkForAllChanges =
        hasExternalViewChanges().and(hasInstanceConfigChanges()).and(hasLiveInstanceChanges());
    int timeout = (_viewClusterRefreshPeriodSec + 5) * 1000;
    TestHelper.verify(() -> checkForAllChanges.test(_monitor), timeout);

    Assert.assertEquals(
        new HashSet<>(_monitor.getPropertyNamesFromViewCluster(PropertyType.EXTERNALVIEW)),
        _allResources);
    Assert.assertEquals(
        new HashSet<>(_monitor.getPropertyNamesFromViewCluster(PropertyType.LIVEINSTANCES)),
        allParticipantNames);
    Assert.assertEquals(_monitor.getPropertyNamesFromViewCluster(PropertyType.INSTANCES).size(), 0);
  }

  private void modifyViewClusterConfig() throws Exception {
    try {
      // Modify view cluster config
      _viewClusterRefreshPeriodSec = 8;
      List<PropertyType> newProperties = new ArrayList<>(ViewClusterSourceConfig.getValidPropertyTypes());
      newProperties.remove(PropertyType.LIVEINSTANCES);
      resetViewClusterConfig(_viewClusterRefreshPeriodSec, newProperties);

      // Wait for refresh and verify
      Predicate<MockViewClusterSpectator> checkForLiveInstanceChanges =
          hasExternalViewChanges().negate().and(hasInstanceConfigChanges().negate()).and(hasLiveInstanceChanges());
      int timeout = (_viewClusterRefreshPeriodSec + 5) * 1000;
      TestHelper.verify(() -> checkForLiveInstanceChanges.test(_monitor), timeout);
      Assert.assertEquals(_monitor.getPropertyNamesFromViewCluster(PropertyType.LIVEINSTANCES).size(),
          0);
    } finally {
      _monitor.reset();
    }
  }

  private void removeResourceFromCluster() throws Exception {
    try {
      // Remove 1 resource from a cluster, we should get corresponding changes in view cluster
      List<String> resourceNameList = new ArrayList<>(_allResources);
      _gSetupTool.dropResourceFromCluster(_allSourceClusters.get(0), resourceNameList.get(0));
      rebalanceResources();
      Predicate<MockViewClusterSpectator> checkForExternalViewChanges =
          hasExternalViewChanges().and(hasInstanceConfigChanges().negate()).and(hasLiveInstanceChanges().negate());
      // Wait for refresh and verify
      int timeout = (_viewClusterRefreshPeriodSec + 5) * 1000;
      TestHelper.verify(() -> checkForExternalViewChanges.test(_monitor), timeout);
      Assert.assertEquals(new HashSet<>(_monitor.getPropertyNamesFromViewCluster(PropertyType.EXTERNALVIEW)),
          _allResources);
    } finally {
      _monitor.reset();
    }
  }

  private void createResourceAndTriggerRebalance() throws Exception {
    try {
      // Create resource and trigger rebalance
      createResources();
      rebalanceResources();
      // Wait for refresh and verify
      Predicate<MockViewClusterSpectator> checkForExternalViewChanges =
          hasExternalViewChanges().and(hasInstanceConfigChanges().negate())
              .and(hasLiveInstanceChanges().negate());
      int timeout = (_viewClusterRefreshPeriodSec + 5) * 1000;
      TestHelper.verify(() -> checkForExternalViewChanges.test(_monitor), timeout);
      Assert.assertEquals(
          new HashSet<>(_monitor.getPropertyNamesFromViewCluster(PropertyType.EXTERNALVIEW)),
          _allResources);
    } finally {
      _monitor.reset();
    }
  }

  private void initiateViewAggregator() throws Exception {
    try {
      // Clean up initial events
      _monitor.reset();

      // Start view aggregator
      triggerViewAggregatorStateTransition("STANDBY", "LEADER");

      // Wait for refresh and verify
      Predicate<MockViewClusterSpectator> checkForNoExternalViewChanges =
          hasExternalViewChanges().negate().and(hasInstanceConfigChanges())
              .and(hasLiveInstanceChanges());
      int timeout = (_viewClusterRefreshPeriodSec + 5) * 1000;
      TestHelper.verify(() -> checkForNoExternalViewChanges.test(_monitor), timeout);

      Set<String> participantInstanceNames = getParticipantInstanceNames();

      Assert.assertEquals(
          new HashSet<>(_monitor.getPropertyNamesFromViewCluster(PropertyType.LIVEINSTANCES)),
          participantInstanceNames);
      Assert.assertEquals(
          new HashSet<>(_monitor.getPropertyNamesFromViewCluster(PropertyType.INSTANCES)),
          participantInstanceNames);
    } finally {
      _monitor.reset();
    }
  }

  private Set<String> getParticipantInstanceNames() {
    return _allParticipants.stream().map(ZKHelixManager::getInstanceName)
        .collect(Collectors.toSet());
  }

  private void resetViewClusterConfig(int refreshPeriod, List<PropertyType> properties) {
    List<ViewClusterSourceConfig> sourceConfigs = new ArrayList<>();
    for (String sourceCluster : _allSourceClusters) {
      sourceConfigs.add(new ViewClusterSourceConfig(sourceCluster, ZK_ADDR, properties));
    }

    ClusterConfig viewClusterConfig = _configAccessor.getClusterConfig(viewClusterName);
    viewClusterConfig.setViewClusterRefreshPeriod(refreshPeriod);
    viewClusterConfig.setViewClusterSourceConfigs(sourceConfigs);
    _configAccessor.setClusterConfig(viewClusterName, viewClusterConfig);
  }

  private Predicate<MockViewClusterSpectator> hasExternalViewChanges() {
    return clusterSpectator -> clusterSpectator.getExternalViewChangeCount() > 0;
  }

  private Predicate<MockViewClusterSpectator> hasInstanceConfigChanges() {
    return clusterSpectator -> clusterSpectator.getInstanceConfigChangeCount() > 0;
  }

  private Predicate<MockViewClusterSpectator> hasLiveInstanceChanges() {
    return clusterSpectator -> clusterSpectator.getLiveInstanceChangeCount() > 0;
  }

  /**
   * Create same sets of resources for each cluster
   */
  private void createResources() {
    System.out.println("Creating resources ...");
    for (String sourceClusterName : _allSourceClusters) {
      for (int i = 0; i < numResourcePerSourceCluster; i++) {
        String resourceName = resourceNamePrefix + i;
        _gSetupTool.addResourceToCluster(sourceClusterName, resourceName, numResourcePartition,
            stateModel);
        _allResources.add(resourceName);
      }
    }
  }

  /**
   * Rebalance all resources on each cluster
   */
  private void rebalanceResources() {
    System.out.println("Rebalancing resources ...");
    for (String sourceClusterName : _allSourceClusters) {
      for (String resourceName : _allResources) {
        // We always rebalance all resources, even if it would be deleted during test
        // We assume rebalance will be successful
        try {
          _gSetupTool.rebalanceResource(sourceClusterName, resourceName,
              numReplicaPerResourcePartition);
        } catch (HelixException e) {
          // ok
        }
      }
    }
  }

  private void triggerViewAggregatorStateTransition(String fromState, String toState)
      throws Exception {
    if (!_viewAggregatorStateModel.getCurrentState().equalsIgnoreCase(fromState)) {
      throw new IllegalStateException(
          String.format("From state (%s) != current state (%s).", fromState,
              _viewAggregatorStateModel.getCurrentState()));
    } else if (_viewAggregatorStateModel.getCurrentState().equalsIgnoreCase(toState)) {
      return;
    }
    NotificationContext context = new NotificationContext(null);
    Message msg = new Message(Message.MessageType.STATE_TRANSITION, "msgId");
    msg.setPartitionName(viewClusterName);
    msg.setFromState(fromState);
    msg.setToState(toState);
    Method method =
        stateModelParser.getMethodForTransition(_viewAggregatorStateModel.getClass(), fromState,
            toState, new Class[]{Message.class, NotificationContext.class});
    method.invoke(_viewAggregatorStateModel, msg, context);
    _viewAggregatorStateModel.updateState(toState);
  }

  @Override
  protected int getNumSourceCluster() {
    return numSourceCluster;
  }
}
