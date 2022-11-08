/*
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
package io.openchaos.driver.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openchaos.common.utils.KillProcessUtil;
import io.openchaos.common.utils.PauseProcessUtil;
import io.openchaos.common.utils.SshUtil;
import io.openchaos.driver.queue.QueueNode;
import io.openchaos.driver.rabbitmq.config.RabbitMQBrokerConfig;
import io.openchaos.driver.rabbitmq.config.RabbitMQConfig;
import io.openchaos.driver.rabbitmq.core.ClusterStatus;
import io.openchaos.driver.rabbitmq.core.Sync;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;


public class RabbitMQChaosNode implements QueueNode {
    private static final String BROKER_PROCESS_NAME = "beam.smp";
    private static final Logger log = LoggerFactory.getLogger(RabbitMQChaosNode.class);
    private String node;
    private List<String> nodes;
    private RabbitMQBrokerConfig rmqBrokerConfig;
    private String installDir = "rabbitmq-chaos-test";
    private String rabbitmqVersion = "3.8.35";
    private String configureFilePath = "broker-chaos-test.conf";
    private Sync sync;

    public RabbitMQChaosNode(String node, List<String> nodes, RabbitMQConfig rmqConfig,
                             RabbitMQBrokerConfig rmqBrokerConfig, Sync sync) {
        this.node = node;
        this.nodes = nodes;
        this.rmqBrokerConfig = rmqBrokerConfig;
        if (rmqConfig.installDir != null && !rmqConfig.installDir.isEmpty()) {
            this.installDir = rmqConfig.installDir;
        }
        if (rmqConfig.rabbitmqVersion != null && !rmqConfig.rabbitmqVersion.isEmpty()) {
            this.rabbitmqVersion = rmqConfig.rabbitmqVersion;
        }
        if (rmqConfig.configureFilePath != null && !rmqConfig.configureFilePath.isEmpty()) {
            this.configureFilePath = rmqConfig.configureFilePath;
        }
        this.sync = sync;
    }

    @Override
    public void setup() {
        if (sync.status == Sync.State.START || sync.status == Sync.State.FINISH) {
            return;
        }
        sync.status = Sync.State.START;
        CountDownLatch latch = new CountDownLatch(nodes.size());
        Executor executor = new ForkJoinPool(nodes.size());
        for (String no : nodes) {
            executor.execute(() -> {
                try {
                    setup(no);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(20, TimeUnit.MINUTES);
            sync.addUser("root", "root");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            sync.status = Sync.State.FINISH;
        }
    }


    public void setup(String no) {
        try {
            // install erlang and rabbitmq
            installErlang(no);
            installRabbitmq(no);
            sync.barrier.await(14, TimeUnit.MINUTES);
            // sync cookie
            sync.resetBarrier();
            sync.syncCookie(no);
            sync.barrier.await(5, TimeUnit.MINUTES);
            try {
                SshUtil.execCommand(no, "rabbitmq-server -detached");
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            // join cluster
            sync.resetBarrier();
            if (!Objects.equals(no, sync.getLeader())) {
                try {
                    SshUtil.execCommand(no, "rabbitmqctl stop_app");
                    SshUtil.execCommand(no, "rabbitmqctl reset");
                    SshUtil.execCommand(no, "rabbitmqctl join_cluster rabbit@" + sync.getLeader());
                    SshUtil.execCommand(no, "rabbitmqctl start_app");
                    log.info(no + " join cluster rabbit@" + sync.getLeader() + " finished");
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
            sync.barrier.await(5, TimeUnit.MINUTES);
            ClusterStatus clusterStatus = null;
            sync.resetBarrier();
            while (clusterStatus == null || clusterStatus.getRunning_nodes().size() != nodes.size()) {
                String cmd = "rabbitmqctl cluster_status --formatter json";
                String res = SshUtil.execCommandWithArgsReturnStr(no, cmd);
                ObjectMapper objectMapper = new ObjectMapper();
                clusterStatus = objectMapper.readValue(res, ClusterStatus.class);
            }
            sync.barrier.await(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Node {} setup rabbitmq node failed", no, e);
            throw new RuntimeException(e);
        }
    }

    private void installErlang(String no) throws Exception {
        try {
            String erl = SshUtil.execCommandWithArgsReturnStr(no, "which erl");
            if (!StringUtils.isEmpty(erl)) {
                return;
            }
        } catch (Exception ignored) {
        }
        SshUtil.execCommand(no, "rm -rf /opt/erlang");
        SshUtil.execCommand(no, "mkdir /opt/erlang");
        SshUtil.execCommand(no, "yum -y install vim make libtool libtool-ltdl-devel libevent-devel lua-devel openssl-devel flex mysql-devel gcc.x86_64 gcc-c++.x86_64 ncurses-devel wget lrzsz");
        SshUtil.execCommandInDir(no, "/opt/erlang", "wget https://github.com/rabbitmq/erlang-rpm/releases/download/v23.2.6/erlang-23.2.6-1.el7.x86_64.rpm");
        SshUtil.execCommandInDir(no, "/opt/erlang", "rpm -ivh erlang-23.2.6-1.el7.x86_64.rpm");
    }

    private void installRabbitmq(String no) throws Exception {
        try {
            String rab = SshUtil.execCommandWithArgsReturnStr(no, "which rabbitmq-server");
            if (!StringUtils.isEmpty(rab)) {
                return;
            }
        } catch (Exception ignored) {
        }
        String ls;
        try {
            ls = SshUtil.execCommandWithArgsReturnStr(no, "ls | grep rabbitmq-server-generic-unix-3.8.35.tar");
        } catch (Exception e) {
            ls = e.getLocalizedMessage();
        }
        if (!StringUtils.equals(ls, "rabbitmq-server-generic-unix-3.8.35.tar\n")) {
            log.info(no + " downloading rabbitmq 3.8.35");
            SshUtil.execCommand(no, "wget https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.8.35/rabbitmq-server-generic-unix-3.8.35.tar.xz");
            SshUtil.execCommand(no, "xz -d rabbitmq-server-generic-unix-3.8.35.tar.xz");
        }
        SshUtil.execCommand(no, "tar -xvf rabbitmq-server-generic-unix-3.8.35.tar");
        SshUtil.execCommand(no, "rm -rf /usr/local/rabbitmq-server-3.8.35");
        SshUtil.execCommand(no, "mv rabbitmq_server-3.8.35 /usr/local/rabbitmq-server-3.8.35");
        SshUtil.execCommand(no, "echo 'export PATH=$PATH::/usr/local/rabbitmq-server-3.8.35/sbin' >> /etc/profile");
        SshUtil.execCommand(no, "echo 'export PATH=$PATH::/usr/local/rabbitmq-server-3.8.35/sbin' >> ~/.bashrc");
        SshUtil.execCommand(no, "source /etc/profile");
        SshUtil.execCommand(no, "source ~/.bashrc");
        SshUtil.execCommand(no, "rabbitmq-plugins enable rabbitmq_management");
    }

    @Override
    public void teardown() {
        stop();
    }

    @Override
    public void start() {
        try {
            // start broker
            log.info("Node {} start broker...", node);
            SshUtil.execCommandInDir(node, installDir, "sbin/rabbitmq-server -detached");
        } catch (Exception e) {
            log.error("Node {} start rabbitmq node failed", node, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() {
        try {
            KillProcessUtil.kill(node, BROKER_PROCESS_NAME);
        } catch (Exception e) {
            log.error("Node {} stop rabbitmq processes failed", node, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void kill() {
        try {
            KillProcessUtil.forceKillInErl(node, BROKER_PROCESS_NAME);
        } catch (Exception e) {
            log.error("Node {} kill rabbitmq processes failed", node, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void pause() {
        try {
            PauseProcessUtil.suspend(node, BROKER_PROCESS_NAME);
        } catch (Exception e) {
            log.error("Node {} pause rabbitmq processes failed", node, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void resume() {
        try {
            PauseProcessUtil.resumeInErl(node, BROKER_PROCESS_NAME);
        } catch (Exception e) {
            log.error("Node {} resume rabbitmq processes failed", node, e);
            throw new RuntimeException(e);
        }
    }
}
