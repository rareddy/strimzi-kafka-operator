/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.cluster.model.Ca;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.common.BackOff;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Integer.parseInt;

/**
 * Helper class for finding the leader of a ZK cluster
 */
public class ZookeeperLeaderFinder {

    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(ZookeeperLeaderFinder.class);

    private static final Pattern LEADER_MODE_PATTERN = Pattern.compile("^Mode: leader$", Pattern.MULTILINE);

    public static final int UNKNOWN_LEADER = -1;

    private final Vertx vertx;
    final SecretOperator secretOperator;
    private final Supplier<BackOff> backOffSupplier;

    public ZookeeperLeaderFinder(Vertx vertx, SecretOperator secretOperator, Supplier<BackOff> backOffSupplier) {
        this.vertx = vertx;
        this.secretOperator = secretOperator;
        this.backOffSupplier = backOffSupplier;
    }

    /*test*/ NetClientOptions clientOptions(Reconciliation reconciliation, Secret coCertKeySecret, Secret clusterCaCertificateSecret) {
        return new NetClientOptions()
                .setConnectTimeout(10_000)
                .setSsl(true)
                .setHostnameVerificationAlgorithm("HTTPS")
                .setPemKeyCertOptions(keyCertOptions(coCertKeySecret))
                .setPemTrustOptions(trustOptions(reconciliation, clusterCaCertificateSecret));
    }

    private CertificateFactory x509Factory() {
        CertificateFactory x509;
        try {
            x509 = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("No security provider supports X.509");
        }
        return x509;
    }

    /**
     * Validate the cluster CA certificate(s) passed in the given Secret
     * and return the PemTrustOptions for trusting them.
     */
    protected PemTrustOptions trustOptions(Reconciliation reconciliation, Secret clusterCaCertificateSecret) {
        Base64.Decoder decoder = Base64.getDecoder();
        CertificateFactory x509 = x509Factory();
        PemTrustOptions pto = new PemTrustOptions();
        for (Map.Entry<String, String> entry : clusterCaCertificateSecret.getData().entrySet()) {
            String entryName = entry.getKey();
            if (entryName.endsWith(".crt")) {
                LOGGER.infoCr(reconciliation, "Trusting certificate {} from Secret {}", entryName, clusterCaCertificateSecret.getMetadata().getName());
                byte[] certBytes = decoder.decode(entry.getValue());
                try {
                    x509.generateCertificate(new ByteArrayInputStream(certBytes));
                } catch (CertificateException e) {
                    throw corruptCertificate(clusterCaCertificateSecret, entryName, e);
                }
                pto.addCertValue(Buffer.buffer(certBytes));
            } else {
                LOGGER.warnCr(reconciliation, "Ignoring non-certificate {} in Secret {}", entryName, clusterCaCertificateSecret.getMetadata().getName());
            }
        }
        return pto;
    }

    private RuntimeException corruptCertificate(Secret secret, String certKey, CertificateException e) {
        return new RuntimeException("Bad/corrupt certificate found in data." + certKey.replace(".", "\\.") + " of Secret "
                + secret.getMetadata().getName() + " in namespace " + secret.getMetadata().getNamespace(), e);
    }

    /**
     * Validate the CO certificate and key passed in the given Secret
     * and return the PemKeyCertOptions for using it for TLS authentication.
     */
    protected PemKeyCertOptions keyCertOptions(Secret coCertKeySecret) {
        CertAndKey coCertKey = Ca.asCertAndKey(coCertKeySecret,
                                            "cluster-operator.key", "cluster-operator.crt",
                                        "cluster-operator.p12", "cluster-operator.password");
        if (coCertKey == null) {
            throw Util.missingSecretException(coCertKeySecret.getMetadata().getNamespace(), coCertKeySecret.getMetadata().getName());
        }
        CertificateFactory x509 = x509Factory();
        try {
            x509.generateCertificate(new ByteArrayInputStream(coCertKey.cert()));
        } catch (CertificateException e) {
            throw corruptCertificate(coCertKeySecret, "cluster-operator.crt", e);
        }
        return new PemKeyCertOptions()
                .setCertValue(Buffer.buffer(coCertKey.cert()))
                .setKeyValue(Buffer.buffer(coCertKey.key()));
    }

    /**
     * Returns a Future which completes with the the id of the Zookeeper leader.
     * An exponential backoff is used if no ZK node is leader on the attempt to find it.
     * If there is no leader after 3 attempts then the returned Future completes with {@link #UNKNOWN_LEADER}.
     */
    Future<Integer> findZookeeperLeader(Reconciliation reconciliation, String cluster, String namespace, List<Pod> pods, Secret coKeySecret) {
        if (pods.size() <= 1) {
            return Future.succeededFuture(pods.size() - 1);
        }
        String clusterCaSecretName = KafkaResources.clusterCaCertificateSecretName(cluster);
        Future<Secret> clusterCaKeySecretFuture = secretOperator.getAsync(namespace, clusterCaSecretName);
        return clusterCaKeySecretFuture.compose(clusterCaCertificateSecret -> {
            if (clusterCaCertificateSecret  == null) {
                return Future.failedFuture(Util.missingSecretException(namespace, clusterCaSecretName));
            }
            try {
                NetClientOptions netClientOptions = clientOptions(reconciliation, coKeySecret, clusterCaCertificateSecret);
                return zookeeperLeader(reconciliation, cluster, namespace, pods, netClientOptions);
            } catch (Throwable e) {
                return Future.failedFuture(e);
            }
        });

    }
    private Future<Integer> zookeeperLeader(Reconciliation reconciliation, String cluster, String namespace, List<Pod> pods,
                                            NetClientOptions netClientOptions) {
        Promise<Integer> result = Promise.promise();
        BackOff backOff = backOffSupplier.get();
        Handler<Long> handler = new Handler<Long>() {
            @Override
            public void handle(Long tid) {
                zookeeperLeader(reconciliation, pods, netClientOptions).onComplete(leader -> {
                    if (leader.succeeded()) {
                        if (leader.result() != UNKNOWN_LEADER) {
                            result.complete(leader.result());
                        } else {
                            rescheduleOrComplete(reconciliation, tid);
                        }
                    } else {
                        LOGGER.debugOp("Ignoring error", leader.cause());
                        if (backOff.done()) {
                            result.complete(UNKNOWN_LEADER);
                        } else {
                            rescheduleOrComplete(reconciliation, tid);
                        }
                    }
                });
            }

            void rescheduleOrComplete(Reconciliation reconciliation, Long tid) {
                if (backOff.done()) {
                    LOGGER.warnCr(reconciliation, "Giving up trying to find the leader of {}/{} after {} attempts taking {}ms",
                            namespace, cluster, backOff.maxAttempts(), backOff.totalDelayMs());
                    result.complete(UNKNOWN_LEADER);
                } else {
                    // Schedule ourselves to run again
                    long delay = backOff.delayMs();
                    LOGGER.infoCr(reconciliation, "No leader found for cluster {} in namespace {}; " +
                                    "backing off for {}ms (cumulative {}ms)",
                            cluster, namespace, delay, backOff.cumulativeDelayMs());
                    if (delay < 1) {
                        this.handle(tid);
                    } else {
                        vertx.setTimer(delay, this);
                    }
                }
            }
        };
        handler.handle(null);
        return result.future();
    }

    /**
     * Synchronously find the leader by testing each pod in the given list
     * using {@link #isLeader(Reconciliation, Pod, NetClientOptions)}.
     */
    private Future<Integer> zookeeperLeader(Reconciliation reconciliation, List<Pod> pods, NetClientOptions netClientOptions) {
        try {
            Future<Integer> f = Future.succeededFuture(UNKNOWN_LEADER);
            for (int i = 0; i < pods.size(); i++) {
                final int podNum = i;
                Pod pod = pods.get(i);
                String podName = pod.getMetadata().getName();
                f = f.compose(leader -> {
                    if (leader == UNKNOWN_LEADER) {
                        LOGGER.debugCr(reconciliation, "Checker whether {} is leader", podName);
                        return isLeader(reconciliation, pod, netClientOptions).map(isLeader -> {
                            if (isLeader != null && isLeader) {
                                LOGGER.infoCr(reconciliation, "Pod {} is leader", podName);
                                return podNum;
                            } else {
                                LOGGER.infoCr(reconciliation, "Pod {} is not a leader", podName);
                                return UNKNOWN_LEADER;
                            }
                        });
                    } else {
                        return Future.succeededFuture(leader);
                    }
                });
            }
            return f;
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * Returns whether the given pod is the zookeeper leader.
     */
    protected Future<Boolean> isLeader(Reconciliation reconciliation, Pod pod, NetClientOptions netClientOptions) {

        Promise<Boolean> promise = Promise.promise();
        String host = host(pod);
        int port = port(pod);
        LOGGER.debugCr(reconciliation, "Connecting to zookeeper on {}:{}", host, port);
        vertx.createNetClient(netClientOptions)
            .connect(port, host, ar -> {
                if (ar.failed()) {
                    LOGGER.warnCr(reconciliation, "ZK {}:{}: failed to connect to zookeeper:", host, port, ar.cause().getMessage());
                    promise.fail(ar.cause());
                } else {
                    LOGGER.debugCr(reconciliation, "ZK {}:{}: connected", host, port);
                    NetSocket socket = ar.result();
                    socket.exceptionHandler(ex -> {
                        if (!promise.tryFail(ex)) {
                            LOGGER.debugCr(reconciliation, "ZK {}:{}: Ignoring error, since leader status of pod {} is already known: {}",
                                    host, port, pod.getMetadata().getName(), ex);
                        }
                    });
                    StringBuilder sb = new StringBuilder();
                    // We could use socket idle timeout, but this times out even if the server just responds
                    // very slowly
                    long timerId = vertx.setTimer(10_000, tid -> {
                        LOGGER.debugCr(reconciliation, "ZK {}:{}: Timeout waiting for Zookeeper {} to close socket",
                                host, port, socket.remoteAddress());
                        socket.close();
                    });
                    socket.closeHandler(v -> {
                        vertx.cancelTimer(timerId);
                        Matcher matcher = LEADER_MODE_PATTERN.matcher(sb);
                        boolean isLeader = matcher.find();
                        LOGGER.debugCr(reconciliation, "ZK {}:{}: {} leader", host, port, isLeader ? "is" : "is not");
                        if (!promise.tryComplete(isLeader)) {
                            LOGGER.debugCr(reconciliation, "ZK {}:{}: Ignoring leader result: Future is already complete",
                                    host, port);
                        }
                    });
                    LOGGER.debugCr(reconciliation, "ZK {}:{}: upgrading to TLS", host, port);
                    socket.handler(buffer -> {
                        LOGGER.traceCr(reconciliation, "buffer: {}", buffer);
                        sb.append(buffer.toString());
                    });
                    LOGGER.debugCr(reconciliation, "ZK {}:{}: sending stat", host, port);
                    socket.write("stat");
                }

            });
        return promise.future().recover(error -> {
            LOGGER.debugOp("ZK {}:{}: Error trying to determine whether leader ({}) => not leader", host, port, error);
            return Future.succeededFuture(Boolean.FALSE);
        });
    }

    /** The hostname for connecting to zookeeper in the given pod. */
    protected String host(Pod pod) {
        String cluster = Labels.cluster(pod);
        String podName = pod.getMetadata().getName();
        int index = podName.lastIndexOf('-');
        if (index == -1 || index >= podName.length()) {
            // This should be impossible if the pod name conforms to the names used for STS pods
            throw new RuntimeException();
        }
        int podId = parseInt(podName.substring(index + 1));
        return ZookeeperCluster.podDnsName(pod.getMetadata().getNamespace(), cluster, podId);
    }

    /** The port number for connecting to zookeeper in the given pod. */
    protected int port(Pod pod) {
        return ZookeeperCluster.CLIENT_TLS_PORT;
    }
}
