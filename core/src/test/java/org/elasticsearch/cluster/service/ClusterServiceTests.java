/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster.service;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.NodeConnectionsService;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.OperationRouting;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.DummyTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.MockLogAppender;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.elasticsearch.cluster.service.ClusterServiceUtils.setState;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ClusterServiceTests extends ESTestCase {

    static ThreadPool threadPool;
    TimedClusterService clusterService;

    @BeforeClass
    public static void createThreadPool() {
        threadPool = new ThreadPool(ClusterServiceTests.class.getName());
    }

    @AfterClass
    public static void stopThreadPool() {
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        clusterService = createTimedClusterService(true);
    }

    @After
    public void tearDown() throws Exception {
        clusterService.close();
        super.tearDown();
    }

    TimedClusterService createTimedClusterService(boolean makeMaster) throws InterruptedException {
        TimedClusterService timedClusterService = new TimedClusterService(Settings.EMPTY, null,
                new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
                threadPool, new ClusterName("ClusterServiceTests"));
        timedClusterService.setLocalNode(new DiscoveryNode("node1", DummyTransportAddress.INSTANCE, emptyMap(),
                emptySet(), Version.CURRENT));
        timedClusterService.setNodeConnectionsService(new NodeConnectionsService(Settings.EMPTY, null, null) {
            @Override
            public void connectToAddedNodes(ClusterChangedEvent event) {
                // skip
            }

            @Override
            public void disconnectFromRemovedNodes(ClusterChangedEvent event) {
                // skip
            }
        });
        timedClusterService.setClusterStatePublisher((event, ackListener) -> {
        });
        timedClusterService.start();
        ClusterState state = timedClusterService.state();
        final DiscoveryNodes nodes = state.nodes();
        final DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder(nodes)
                .masterNodeId(makeMaster ? nodes.getLocalNodeId() : null);
        state = ClusterState.builder(state).blocks(ClusterBlocks.EMPTY_CLUSTER_BLOCK)
                .nodes(nodesBuilder).build();
        setState(timedClusterService, state);
        return timedClusterService;
    }

    public void testTimeoutUpdateTask() throws Exception {
        final CountDownLatch block = new CountDownLatch(1);
        clusterService.submitStateUpdateTask("test1", new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return currentState;
            }

            @Override
            public void onFailure(String source, Throwable t) {
                throw new RuntimeException(t);
            }
        });

        final CountDownLatch timedOut = new CountDownLatch(1);
        final AtomicBoolean executeCalled = new AtomicBoolean();
        clusterService.submitStateUpdateTask("test2", new ClusterStateUpdateTask() {
            @Override
            public TimeValue timeout() {
                return TimeValue.timeValueMillis(2);
            }

            @Override
            public void onFailure(String source, Throwable t) {
                timedOut.countDown();
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                executeCalled.set(true);
                return currentState;
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
            }
        });

        timedOut.await();
        block.countDown();
        final CountDownLatch allProcessed = new CountDownLatch(1);
        clusterService.submitStateUpdateTask("test3", new ClusterStateUpdateTask() {
            @Override
            public void onFailure(String source, Throwable t) {
                throw new RuntimeException(t);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                allProcessed.countDown();
                return currentState;
            }

        });
        allProcessed.await(); // executed another task to double check that execute on the timed out update task is not called...
        assertThat(executeCalled.get(), equalTo(false));
    }


    public void testMasterAwareExecution() throws Exception {
        ClusterService nonMaster = createTimedClusterService(false);

        final boolean[] taskFailed = {false};
        final CountDownLatch latch1 = new CountDownLatch(1);
        nonMaster.submitStateUpdateTask("test", new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                latch1.countDown();
                return currentState;
            }

            @Override
            public void onFailure(String source, Throwable t) {
                taskFailed[0] = true;
                latch1.countDown();
            }
        });

        latch1.await();
        assertTrue("cluster state update task was executed on a non-master", taskFailed[0]);

        taskFailed[0] = true;
        final CountDownLatch latch2 = new CountDownLatch(1);
        nonMaster.submitStateUpdateTask("test", new ClusterStateUpdateTask() {
            @Override
            public boolean runOnlyOnMaster() {
                return false;
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                taskFailed[0] = false;
                latch2.countDown();
                return currentState;
            }

            @Override
            public void onFailure(String source, Throwable t) {
                taskFailed[0] = true;
                latch2.countDown();
            }
        });
        latch2.await();
        assertFalse("non-master cluster state update task was not executed", taskFailed[0]);

        nonMaster.close();
    }

    /*
   * test that a listener throwing an exception while handling a
   * notification does not prevent publication notification to the
   * executor
   */
    public void testClusterStateTaskListenerThrowingExceptionIsOkay() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();

        clusterService.submitStateUpdateTask(
                "testClusterStateTaskListenerThrowingExceptionIsOkay",
                new Object(),
                ClusterStateTaskConfig.build(Priority.NORMAL),
                new ClusterStateTaskExecutor<Object>() {
                    @Override
                    public boolean runOnlyOnMaster() {
                        return false;
                    }

                    @Override
                    public BatchResult<Object> execute(ClusterState currentState, List<Object> tasks) throws Exception {
                        ClusterState newClusterState = ClusterState.builder(currentState).build();
                        return BatchResult.builder().successes(tasks).build(newClusterState);
                    }

                    @Override
                    public void clusterStatePublished(ClusterState newClusterState) {
                        published.set(true);
                        latch.countDown();
                    }
                },
                new ClusterStateTaskListener() {
                    @Override
                    public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                        throw new IllegalStateException(source);
                    }

                    @Override
                    public void onFailure(String source, Throwable t) {
                    }
                }
        );

        latch.await();
        assertTrue(published.get());
    }

    // test that for a single thread, tasks are executed in the order
    // that they are submitted
    public void testClusterStateUpdateTasksAreExecutedInOrder() throws BrokenBarrierException, InterruptedException {
        class TaskExecutor implements ClusterStateTaskExecutor<Integer> {
            List<Integer> tasks = new ArrayList<>();

            @Override
            public BatchResult<Integer> execute(ClusterState currentState, List<Integer> tasks) throws Exception {
                this.tasks.addAll(tasks);
                return BatchResult.<Integer>builder().successes(tasks).build(ClusterState.builder(currentState).build());
            }

            @Override
            public boolean runOnlyOnMaster() {
                return false;
            }
        }

        int numberOfThreads = randomIntBetween(2, 8);
        TaskExecutor[] executors = new TaskExecutor[numberOfThreads];
        for (int i = 0; i < numberOfThreads; i++) {
            executors[i] = new TaskExecutor();
        }

        int tasksSubmittedPerThread = randomIntBetween(2, 1024);

        CopyOnWriteArrayList<Tuple<String, Throwable>> failures = new CopyOnWriteArrayList<>();
        CountDownLatch updateLatch = new CountDownLatch(numberOfThreads * tasksSubmittedPerThread);

        ClusterStateTaskListener listener = new ClusterStateTaskListener() {
            @Override
            public void onFailure(String source, Throwable t) {
                logger.error("unexpected failure: [{}]", t, source);
                failures.add(new Tuple<>(source, t));
                updateLatch.countDown();
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                updateLatch.countDown();
            }
        };

        CyclicBarrier barrier = new CyclicBarrier(1 + numberOfThreads);

        for (int i = 0; i < numberOfThreads; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                    for (int j = 0; j < tasksSubmittedPerThread; j++) {
                        clusterService.submitStateUpdateTask("[" + index + "][" + j + "]", j,
                                ClusterStateTaskConfig.build(randomFrom(Priority.values())), executors[index], listener);
                    }
                    barrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    throw new AssertionError(e);
                }
            });
            thread.start();
        }

        // wait for all threads to be ready
        barrier.await();
        // wait for all threads to finish
        barrier.await();

        updateLatch.await();

        assertThat(failures, empty());

        for (int i = 0; i < numberOfThreads; i++) {
            assertEquals(tasksSubmittedPerThread, executors[i].tasks.size());
            for (int j = 0; j < tasksSubmittedPerThread; j++) {
                assertNotNull(executors[i].tasks.get(j));
                assertEquals("cluster state update task executed out of order", j, (int) executors[i].tasks.get(j));
            }
        }
    }

    public void testClusterStateBatchedUpdates() throws BrokenBarrierException, InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        class Task {
            private AtomicBoolean state = new AtomicBoolean();

            public void execute() {
                if (!state.compareAndSet(false, true)) {
                    throw new IllegalStateException();
                } else {
                    counter.incrementAndGet();
                }
            }
        }

        int numberOfThreads = randomIntBetween(2, 8);
        int tasksSubmittedPerThread = randomIntBetween(1, 1024);
        int numberOfExecutors = Math.max(1, numberOfThreads / 4);
        final Semaphore semaphore = new Semaphore(numberOfExecutors);

        class TaskExecutor implements ClusterStateTaskExecutor<Task> {
            private AtomicInteger counter = new AtomicInteger();
            private AtomicInteger batches = new AtomicInteger();
            private AtomicInteger published = new AtomicInteger();

            @Override
            public BatchResult<Task> execute(ClusterState currentState, List<Task> tasks) throws Exception {
                tasks.forEach(task -> task.execute());
                counter.addAndGet(tasks.size());
                ClusterState maybeUpdatedClusterState = currentState;
                if (randomBoolean()) {
                    maybeUpdatedClusterState = ClusterState.builder(currentState).build();
                    batches.incrementAndGet();
                    semaphore.acquire();
                }
                return BatchResult.<Task>builder().successes(tasks).build(maybeUpdatedClusterState);
            }

            @Override
            public boolean runOnlyOnMaster() {
                return false;
            }

            @Override
            public void clusterStatePublished(ClusterState newClusterState) {
                published.incrementAndGet();
                semaphore.release();
            }
        }

        ConcurrentMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        CountDownLatch updateLatch = new CountDownLatch(numberOfThreads * tasksSubmittedPerThread);
        ClusterStateTaskListener listener = new ClusterStateTaskListener() {
            @Override
            public void onFailure(String source, Throwable t) {
                assert false;
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                counters.computeIfAbsent(source, key -> new AtomicInteger()).incrementAndGet();
                updateLatch.countDown();
            }
        };

        List<TaskExecutor> executors = new ArrayList<>();
        for (int i = 0; i < numberOfExecutors; i++) {
            executors.add(new TaskExecutor());
        }

        // randomly assign tasks to executors
        List<TaskExecutor> assignments = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i++) {
            for (int j = 0; j < tasksSubmittedPerThread; j++) {
                assignments.add(randomFrom(executors));
            }
        }

        Map<TaskExecutor, Integer> counts = new HashMap<>();
        for (TaskExecutor executor : assignments) {
            counts.merge(executor, 1, (previous, one) -> previous + one);
        }

        CyclicBarrier barrier = new CyclicBarrier(1 + numberOfThreads);
        for (int i = 0; i < numberOfThreads; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                    for (int j = 0; j < tasksSubmittedPerThread; j++) {
                        ClusterStateTaskExecutor<Task> executor = assignments.get(index * tasksSubmittedPerThread + j);
                        clusterService.submitStateUpdateTask(
                                Thread.currentThread().getName(),
                                new Task(),
                                ClusterStateTaskConfig.build(randomFrom(Priority.values())),
                                executor,
                                listener);
                    }
                    barrier.await();
                } catch (BrokenBarrierException | InterruptedException e) {
                    throw new AssertionError(e);
                }
            });
            thread.start();
        }

        // wait for all threads to be ready
        barrier.await();
        // wait for all threads to finish
        barrier.await();

        // wait until all the cluster state updates have been processed
        updateLatch.await();
        // and until all of the publication callbacks have completed
        semaphore.acquire(numberOfExecutors);

        // assert the number of executed tasks is correct
        assertEquals(numberOfThreads * tasksSubmittedPerThread, counter.get());

        // assert each executor executed the correct number of tasks
        for (TaskExecutor executor : executors) {
            if (counts.containsKey(executor)) {
                assertEquals((int) counts.get(executor), executor.counter.get());
                assertEquals(executor.batches.get(), executor.published.get());
            }
        }

        // assert the correct number of clusterStateProcessed events were triggered
        for (Map.Entry<String, AtomicInteger> entry : counters.entrySet()) {
            assertEquals(entry.getValue().get(), tasksSubmittedPerThread);
        }
    }

    /**
     * Note, this test can only work as long as we have a single thread executor executing the state update tasks!
     */
    public void testPrioritizedTasks() throws Exception {
        Settings settings = Settings.builder()
                .put("discovery.type", "local")
                .build();
        BlockingTask block = new BlockingTask(Priority.IMMEDIATE);
        clusterService.submitStateUpdateTask("test", block);
        int taskCount = randomIntBetween(5, 20);
        Priority[] priorities = Priority.values();

        // will hold all the tasks in the order in which they were executed
        List<PrioritizedTask> tasks = new ArrayList<>(taskCount);
        CountDownLatch latch = new CountDownLatch(taskCount);
        for (int i = 0; i < taskCount; i++) {
            Priority priority = priorities[randomIntBetween(0, priorities.length - 1)];
            clusterService.submitStateUpdateTask("test", new PrioritizedTask(priority, latch, tasks));
        }

        block.release();
        latch.await();

        Priority prevPriority = null;
        for (PrioritizedTask task : tasks) {
            if (prevPriority == null) {
                prevPriority = task.priority();
            } else {
                assertThat(task.priority().sameOrAfter(prevPriority), is(true));
            }
        }
    }

    @TestLogging("cluster:TRACE") // To ensure that we log cluster state events on TRACE level
    public void testClusterStateUpdateLogging() throws Exception {
        MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.addExpectation(new MockLogAppender.SeenEventExpectation("test1", "cluster.service", Level.DEBUG,
                "*processing [test1]: took [1s] no change in cluster_state"));
        mockAppender.addExpectation(new MockLogAppender.SeenEventExpectation("test2", "cluster.service", Level.TRACE,
                "*failed to execute cluster state update in [2s]*"));
        mockAppender.addExpectation(new MockLogAppender.SeenEventExpectation("test3", "cluster.service", Level.DEBUG,
                "*processing [test3]: took [3s] done applying updated cluster_state (version: *, uuid: *)"));

        Logger rootLogger = Logger.getRootLogger();
        rootLogger.addAppender(mockAppender);
        try {
            final CountDownLatch latch = new CountDownLatch(4);
            clusterService.currentTimeOverride = System.nanoTime();
            clusterService.submitStateUpdateTask("test1", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    clusterService.currentTimeOverride += TimeValue.timeValueSeconds(1).nanos();
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    fail();
                }
            });
            clusterService.submitStateUpdateTask("test2", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    clusterService.currentTimeOverride += TimeValue.timeValueSeconds(2).nanos();
                    throw new IllegalArgumentException("Testing handling of exceptions in the cluster state task");
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    fail();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    latch.countDown();
                }
            });
            clusterService.submitStateUpdateTask("test3", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    clusterService.currentTimeOverride += TimeValue.timeValueSeconds(3).nanos();
                    return ClusterState.builder(currentState).incrementVersion().build();
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    fail();
                }
            });
            // Additional update task to make sure all previous logging made it to the logger
            // We don't check logging for this on since there is no guarantee that it will occur before our check
            clusterService.submitStateUpdateTask("test4", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    fail();
                }
            });
            latch.await();
        } finally {
            rootLogger.removeAppender(mockAppender);
        }
        mockAppender.assertAllExpectationsMatched();
    }

    @TestLogging("cluster:WARN") // To ensure that we log cluster state events on WARN level
    public void testLongClusterStateUpdateLogging() throws Exception {
        MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.addExpectation(new MockLogAppender.UnseenEventExpectation("test1 shouldn't see because setting is too low",
                "cluster.service", Level.WARN, "*cluster state update task [test1] took [*] above the warn threshold of *"));
        mockAppender.addExpectation(new MockLogAppender.SeenEventExpectation("test2", "cluster.service", Level.WARN,
                "*cluster state update task [test2] took [32s] above the warn threshold of *"));
        mockAppender.addExpectation(new MockLogAppender.SeenEventExpectation("test3", "cluster.service", Level.WARN,
                "*cluster state update task [test3] took [33s] above the warn threshold of *"));
        mockAppender.addExpectation(new MockLogAppender.SeenEventExpectation("test4", "cluster.service", Level.WARN,
                "*cluster state update task [test4] took [34s] above the warn threshold of *"));

        Logger rootLogger = Logger.getRootLogger();
        rootLogger.addAppender(mockAppender);
        try {
            final CountDownLatch latch = new CountDownLatch(5);
            final CountDownLatch processedFirstTask = new CountDownLatch(1);
            clusterService.currentTimeOverride = System.nanoTime();
            clusterService.submitStateUpdateTask("test1", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    clusterService.currentTimeOverride += TimeValue.timeValueSeconds(1).nanos();
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                    processedFirstTask.countDown();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    fail();
                }
            });

            processedFirstTask.await();
            clusterService.submitStateUpdateTask("test2", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    clusterService.currentTimeOverride += TimeValue.timeValueSeconds(32).nanos();
                    throw new IllegalArgumentException("Testing handling of exceptions in the cluster state task");
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    fail();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    latch.countDown();
                }
            });
            clusterService.submitStateUpdateTask("test3", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    clusterService.currentTimeOverride += TimeValue.timeValueSeconds(33).nanos();
                    return ClusterState.builder(currentState).incrementVersion().build();
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    fail();
                }
            });
            clusterService.submitStateUpdateTask("test4", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    clusterService.currentTimeOverride += TimeValue.timeValueSeconds(34).nanos();
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    fail();
                }
            });
            // Additional update task to make sure all previous logging made it to the logger
            // We don't check logging for this on since there is no guarantee that it will occur before our check
            clusterService.submitStateUpdateTask("test5", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    fail();
                }
            });
            latch.await();
        } finally {
            rootLogger.removeAppender(mockAppender);
        }
        mockAppender.assertAllExpectationsMatched();
    }

    private static class BlockingTask extends ClusterStateUpdateTask {
        private final CountDownLatch latch = new CountDownLatch(1);

        public BlockingTask(Priority priority) {
            super(priority);
        }

        @Override
        public ClusterState execute(ClusterState currentState) throws Exception {
            latch.await();
            return currentState;
        }

        @Override
        public void onFailure(String source, Throwable t) {
        }

        public void release() {
            latch.countDown();
        }

    }

    private static class PrioritizedTask extends ClusterStateUpdateTask {

        private final CountDownLatch latch;
        private final List<PrioritizedTask> tasks;

        private PrioritizedTask(Priority priority, CountDownLatch latch, List<PrioritizedTask> tasks) {
            super(priority);
            this.latch = latch;
            this.tasks = tasks;
        }

        @Override
        public ClusterState execute(ClusterState currentState) throws Exception {
            tasks.add(this);
            latch.countDown();
            return currentState;
        }

        @Override
        public void onFailure(String source, Throwable t) {
            latch.countDown();
        }
    }

    static class TimedClusterService extends ClusterService {

        public volatile Long currentTimeOverride = null;

        public TimedClusterService(Settings settings, OperationRouting operationRouting, ClusterSettings clusterSettings,
                                   ThreadPool threadPool, ClusterName clusterName) {
            super(settings, operationRouting, clusterSettings, threadPool, clusterName);
        }

        @Override
        protected long currentTimeInNanos() {
            if (currentTimeOverride != null) {
                return currentTimeOverride;
            }
            return super.currentTimeInNanos();
        }
    }
}
