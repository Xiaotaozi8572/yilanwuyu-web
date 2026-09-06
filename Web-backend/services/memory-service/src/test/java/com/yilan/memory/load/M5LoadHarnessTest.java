package com.yilan.memory.load;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Empty;
import com.yilan.memory.adapter.grpc.SignedSessionInterceptor;
import com.yilan.memory.adapter.postgres.JdbcAuthorizedSourceReader;
import com.yilan.memory.adapter.postgres.JdbcCandidateRepository;
import com.yilan.memory.adapter.postgres.JdbcConsentRepository;
import com.yilan.memory.adapter.postgres.JdbcMemoryHistoryRepository;
import com.yilan.memory.adapter.postgres.KeywordMemoryQuery;
import com.yilan.memory.adapter.postgres.RecentEpisodeQuery;
import com.yilan.memory.adapter.postgres.StructuredMemoryQuery;
import com.yilan.memory.adapter.redis.MemoryEventConsumer;
import com.yilan.memory.adapter.redis.RedisOutboxPublisher;
import com.yilan.memory.adapter.redis.RedisStreamNames;
import com.yilan.memory.adapter.worker.CandidateWorkerClient;
import com.yilan.memory.adapter.worker.WorkerProposalValidator;
import com.yilan.memory.application.async.CandidateProcessingService;
import com.yilan.memory.application.async.CheckpointService;
import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.application.event.SubmitMemoryEventsUseCase;
import com.yilan.memory.application.governance.GovernCandidateUseCase;
import com.yilan.memory.application.privacy.DataKeyProvider;
import com.yilan.memory.application.privacy.InteractionPayloadKeyEnvelopeStore;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.contract.v1.EventReceipt;
import com.yilan.memory.contract.v1.MemoryApplicationStatus;
import com.yilan.memory.contract.v1.MemoryContextServiceGrpc;
import com.yilan.memory.contract.v1.MemoryEventServiceGrpc;
import com.yilan.memory.contract.v1.ResolveMemoryContextRequest;
import com.yilan.memory.contract.v1.ResolveMemoryContextResponse;
import com.yilan.memory.contract.v1.SubmitMemoryEventsRequest;
import com.yilan.memory.contract.v1.SubmitMemoryEventsResponse;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.governance.PromotionRule;
import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.observability.MemoryMetrics;
import com.yilan.memory.security.SubjectAuthenticationConverter;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.web-application-type=none")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M5LoadHarnessTest {

    private static final String PROTOCOL_VERSION = "m5-load-loopback/v1";
    private static final String FIXED_BEARER = "Bearer m5-load-test-only";
    private static final String HARNESS_MARKER = "M5LoadHarnessTest/v1";
    private static final String APPLICATION_READINESS_SERVICE =
            "m5.load.ApplicationReadiness";
    private static final String APPLICATION_READINESS_METHOD =
            MethodDescriptor.generateFullMethodName(
                    APPLICATION_READINESS_SERVICE, "Ping");

    private static final String READY_FILE = "ready.json";
    private static final String CLIENT_DONE_FILE = "client-done.json";
    private static final String CORRELATION_FAILURE_FILE = "correlation-failure.json";
    private static final String WORKER_READY_FILE = "worker-ready.json";
    private static final String WORKER_ACTIVE_FILE = "worker-active.json";
    private static final String DONE_FILE = "done.json";
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final Pattern RUN_DIRECTORY_NAME = Pattern.compile("run-[A-Za-z0-9_-]{3,96}");
    private static final Pattern SESSION = Pattern.compile("session-load-(?:[0-4][0-9])");
    private static final Pattern CORRELATION_DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> RAW_FIELDS = Set.of(
            "query", "query_text", "subject", "session", "session_id", "token", "payload",
            "traceparent", "source", "source_key", "memory_key", "port", "pid", "tmp_path",
            "run_directory", "endpoint");
    private static final String SIGNED_IDENTITY_INGRESS = "signed_identity_ingress";
    private static final String LOAD_IDENTITY_INGRESS = "load_identity_ingress";
    private static final String GRPC_LISTENER_ON_MESSAGE = "grpc_listener_on_message";
    private static final String GRPC_LISTENER_ON_HALF_CLOSE = "grpc_listener_on_half_close";
    private static final String GRPC_LISTENER_ON_CANCEL = "grpc_listener_on_cancel";
    private static final String RESOLVE_SERVICE_METHOD_ENTRY = "resolve_service_method_entry";
    private static final String RESOLVE_USE_CASE = "resolve_use_case";
    private static final String SUBMIT_EVENT_USE_CASE = "submit_event_use_case";
    private static final String RESOLVE_AUTHORITY_ADAPTER = "resolve_authority_adapter";
    private static final String SUBMIT_EVENT_TRANSACTION_AUTHORITY_ADAPTER =
            "submit_event_transaction_authority_adapter";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final String SUBJECT_KEY = "m5-load-subject-hmac-key-32-bytes-minimum";
    private static final String SOURCE_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17")
            .asCompatibleSubstituteFor("postgres");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4.2-alpine");

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("memory_authority_test")
            .withUsername("memory_test")
            .withPassword("memory_test");

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureTemporaryAuthority(DynamicPropertyRegistry registry) {
        ensureContainersStarted();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 32);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 16);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("memory.security.subject-hmac-key", () -> SUBJECT_KEY);
        registry.add("memory.source-material-security.mode", () -> "test");
        registry.add("memory.source-material-security.master-key", () -> SOURCE_KEY);
        registry.add("memory.context-cache.hmac-key", () -> "m5-load-cache-hmac-key-32-bytes-minimum");
        registry.add("memory.authority-cutover.hmac-key", () -> "m5-load-cutover-hmac-key-32-bytes-minimum");
    }

    private static synchronized void ensureContainersStarted() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }

    private static ServerServiceDefinition applicationReadinessService() {
        MethodDescriptor<Empty, Empty> method =
                MethodDescriptor.<Empty, Empty>newBuilder()
                        .setType(MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(APPLICATION_READINESS_METHOD)
                        .setRequestMarshaller(
                                ProtoUtils.marshaller(Empty.getDefaultInstance()))
                        .setResponseMarshaller(
                                ProtoUtils.marshaller(Empty.getDefaultInstance()))
                        .build();
        return ServerServiceDefinition.builder(APPLICATION_READINESS_SERVICE)
                .addMethod(method, ServerCalls.asyncUnaryCall(
                        (request, observer) -> {
                            observer.onNext(Empty.getDefaultInstance());
                            observer.onCompleted();
                        }))
                .build();
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcConsentRepository consentRepository;

    @Autowired
    private PayloadProtector payloadProtector;

    @Autowired
    private DataKeyProvider dataKeyProvider;

    @Autowired
    private SubmitMemoryEventsUseCase submitEvents;

    @Autowired
    private SubmitMemoryEventsUseCase.InteractionEventRepository interactionEvents;

    @Autowired
    private SubmitMemoryEventsUseCase.TransactionalOutboxRepository transactionalOutbox;

    @Autowired
    private InteractionPayloadKeyEnvelopeStore sourceEnvelopes;

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    @Autowired
    private RedisOutboxPublisher publisher;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MemoryMetrics metrics;

    private final AtomicBoolean harnessExecuted = new AtomicBoolean();
    private final AtomicBoolean diagnosticTestExecuted = new AtomicBoolean();
    private final AtomicBoolean fullProfileCompleted = new AtomicBoolean();
    private final SingleUseGate fullPathWorkerGate = new SingleUseGate("worker");
    private final SingleUseGate fullPathConsumerGate = new SingleUseGate("consumer");
    private final java.util.concurrent.atomic.AtomicInteger signedIngress =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger identityIngress =
            new java.util.concurrent.atomic.AtomicInteger();

    @Test
    @Order(1)
    void protocolGuardsRejectUnsafeOrAmbiguousState() throws Exception {
        var safe = JSON.createObjectNode()
                .put("schema_version", PROTOCOL_VERSION)
                .put("status", "active")
                .put("nonce", "a".repeat(32))
                .put("profile", "full");
        validateWorkerActive(safe, "a".repeat(32), "full");

        assertThatThrownBy(() -> requireLoopback("localhost:1234")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateWorkerActive(
                safe.deepCopy().put("nonce", "b".repeat(32)), "a".repeat(32), "full"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateWorkerActive(
                safe.deepCopy().put("payload", "forbidden"), "a".repeat(32), "full"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rejectRawFields(
                JSON.createObjectNode().put("session_id", "forbidden")))
                .isInstanceOf(IllegalArgumentException.class);

        var gate = new SingleUseGate("consumer");
        gate.claim();
        assertThatThrownBy(gate::claim).isInstanceOf(IllegalStateException.class);
        var workerGate = new SingleUseGate("worker");
        workerGate.claim();
        assertThatThrownBy(workerGate::claim).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireClientCompletionOrder(false, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireStopped(new LeakedProcess()))
                .isInstanceOf(IllegalStateException.class);

        Path ownershipScratch = Files.createTempDirectory("m5-run-ownership-");
        Path link = null;
        try {
            Path root = ownershipScratch.resolve("repository/tmp/memory-system/m5");
            Files.createDirectories(root);
            Path fresh = root.resolve("run-fresh");
            Files.createDirectory(fresh);
            validateFreshRunDirectory(root, fresh);
            Path external = ownershipScratch.resolve("external-run");
            Files.createDirectory(external);
            Path nested = fresh.resolve("run-nested");
            Files.createDirectory(nested);
            Path malformed = root.resolve("not-a-run");
            Files.createDirectory(malformed);
            Path stale = root.resolve("run-stale");
            Files.createDirectory(stale);
            Files.writeString(stale.resolve(READY_FILE), "{}", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> validateFreshRunDirectory(root, root))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> validateRunDirectory(
                    root, fresh, true, candidate -> candidate.equals(root)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> validateFreshRunDirectory(root, nested))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> validateFreshRunDirectory(root, external))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> validateFreshRunDirectory(root, malformed))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> validateFreshRunDirectory(root, stale))
                    .isInstanceOf(IllegalArgumentException.class);
            Path createdLink = root.resolve("run-link");
            link = createdLink;
            try {
                Files.createSymbolicLink(createdLink, fresh);
                assertThatThrownBy(() -> validateFreshRunDirectory(root, createdLink))
                        .isInstanceOf(IllegalArgumentException.class);
            } catch (java.nio.file.FileSystemException linkPrivilegeUnavailable) {
                Path simulatedLink = fresh;
                assertThatThrownBy(() -> validateRunDirectory(
                        root, simulatedLink, true, candidate -> candidate.equals(simulatedLink)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            Path selfOwned = createSelfOwnedRunDirectory(root);
            assertThat(selfOwned.getParent()).isEqualTo(root.toAbsolutePath().normalize());
            try (var children = Files.list(selfOwned)) {
                assertThat(children.findAny()).isEmpty();
            }
            assertThat(Files.exists(external.resolve(READY_FILE))).isFalse();
            assertThat(Files.exists(external.resolve(DONE_FILE))).isFalse();
        } finally {
            if (link != null) {
                Files.deleteIfExists(link);
            }
            deleteOwnedTree(ownershipScratch);
        }

        var diagnosticProbe = new M5R3BoundaryDiagnostics(new Object());
        assertThat(diagnosticProbe.measure(SIGNED_IDENTITY_INGRESS, () -> "complete"))
                .isEqualTo("complete");
        assertThat(diagnosticProbe.diagnostic())
                .contains(
                        "diagnostic_stage=" + SIGNED_IDENTITY_INGRESS,
                        "diagnostic_status=complete",
                        "diagnostic_entry_count=1",
                        "diagnostic_completion_count=1",
                        "diagnostic_duration_ms=")
                .doesNotContain("NaN", "Infinity");
    }

    @Test
    @Order(2)
    void runsBoundedLoopbackProfileAndWritesAuthorityOwnedAggregate() throws Exception {
        harnessExecuted.set(true);
        RunConfig config = RunConfig.fromSystemProperties();
        Server server = null;
        Process python = null;
        ManagedChannel workerChannel = null;
        ExecutorService consumerExecutor = null;
        ExecutorService workerExecutor = null;
        Path ownedRun = config.selfOwned() ? config.runDirectory() : null;
        Throwable failure = null;
        try {
            resetAuthority();
            Map<String, LearnerIdentity> identities = seedSyntheticAuthority();
            MemoryProperties properties = loadProperties();
            var eventDeadlineDiagnosticLock = new Object();
            var boundaryDiagnostics =
                    new M5R3BoundaryDiagnostics(eventDeadlineDiagnosticLock);
            ConsentQuery.PolicyReader instrumentedPolicyReader = subjectHash ->
                    measureResolvePhase(
                            ResolveCallPhase.CONSENT_ACTIVE,
                            () -> boundaryDiagnostics.measure(
                                    RESOLVE_AUTHORITY_ADAPTER,
                                    () -> consentRepository.findForSubject(subjectHash)));
            List<RetrievalChannel> retrievalChannels = List.of(
                    instrumentedRetrievalChannel(
                            new StructuredMemoryQuery(jdbc, payloadProtector),
                            ResolveCallPhase.STRUCTURED_ACTIVE,
                            boundaryDiagnostics),
                    instrumentedRetrievalChannel(
                            new KeywordMemoryQuery(jdbc, payloadProtector),
                            ResolveCallPhase.KEYWORD_ACTIVE,
                            boundaryDiagnostics),
                    instrumentedRetrievalChannel(
                            new RecentEpisodeQuery(jdbc, payloadProtector),
                            ResolveCallPhase.RECENT_EPISODE_ACTIVE,
                            boundaryDiagnostics));
            var resolver = new ResolveMemoryContextUseCase(
                    properties,
                    instrumentedPolicyReader,
                    Clock.systemUTC(),
                    retrievalChannels);
            var instrumentedSubmitEvents = instrumentedSubmitEvents(boundaryDiagnostics);
            var timing = new ResolveTimingInterceptor(
                    eventDeadlineDiagnosticLock,
                    config.scheduledEventAttempts());
            var loadIdentity = new LoadIdentityInterceptor(
                    identities, identityIngress, boundaryDiagnostics);
            var signed = instrumentedServerInterceptor(
                    SIGNED_IDENTITY_INGRESS,
                    signedSessionInterceptor(),
                    boundaryDiagnostics);
            warmAuthorityReads(resolver, identities);
            boundaryDiagnostics.clear();
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                    .addStreamTracerFactory(
                            new EventTransportTerminalTracerFactory(timing))
                    .addService(applicationReadinessService())
                    .addService(ServerInterceptors.intercept(
                            new LoadContextService(resolver, boundaryDiagnostics),
                            timing,
                            loadIdentity,
                            signed))
                    .addService(ServerInterceptors.intercept(
                            new LoadEventService(instrumentedSubmitEvents, boundaryDiagnostics),
                            timing,
                            loadIdentity,
                            signed))
                    .build()
                    .start();
            String endpoint = requireLoopback("127.0.0.1:" + server.getPort());
            Double workerOffP95Ms = null;

            if ("full".equals(config.profile())) {
                seedCandidateEvent(identities.get("session-load-00"));
                RunConfig workerOff = config.withProfile("java-postgres-only");
                writeReady(workerOff, endpoint);
                python = startPrivatePython(workerOff);
                requireSuccessfulPython(
                        waitForPython(python, workerOff.durationSeconds() + 90L),
                        workerOff,
                        boundaryDiagnostics,
                        timing);
                JsonNode baselineDone = readPlainJson(
                        workerOff.runDirectory().resolve(CLIENT_DONE_FILE));
                JsonNode baselineResult = validateClientDone(baselineDone, workerOff);
                workerOffP95Ms = quantile(
                        longSamples(baselineResult.path("test_pipeline_answer_samples_ns")),
                        0.95d);
                requireStopped(python);
                python = null;
                requireAbsent(workerOff.runDirectory().resolve(WORKER_READY_FILE));
                requireAbsent(workerOff.runDirectory().resolve(WORKER_ACTIVE_FILE));
                clearWorkerOffArtifacts(workerOff.runDirectory());
                timing.clear();
                boundaryDiagnostics.clear();
            }
            writeReady(config, endpoint);
            python = startPrivatePython(config);
            Process privateClient = python;

            Future<Integer> consumed = null;
            if ("full".equals(config.profile())) {
                JsonNode workerReady = waitForRecord(
                        config.runDirectory().resolve(WORKER_READY_FILE),
                        Duration.ofSeconds(20),
                        () -> pythonAlive(privateClient));
                String workerEndpoint = validateWorkerReady(workerReady, config);
                HostAndPort worker = HostAndPort.parse(workerEndpoint);
                fullPathWorkerGate.claim();
                workerChannel = NettyChannelBuilder.forAddress(worker.host(), worker.port())
                        .usePlaintext()
                        .build();
                workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "m5-load-worker-client");
                    thread.setDaemon(false);
                    return thread;
                });
                var governance = new GovernCandidateUseCase(
                        new PromotionRule(properties),
                        new JdbcCandidateRepository(jdbc, payloadProtector),
                        new JdbcMemoryHistoryRepository(jdbc, payloadProtector),
                        consentRepository,
                        new TransactionTemplate(transactionManager),
                        Clock.systemUTC());
                var processor = new CandidateProcessingService(
                        new JdbcAuthorizedSourceReader(jdbc, payloadProtector, properties),
                        CandidateWorkerClient.grpc(
                                com.yilan.memory.contract.v1.CandidateProposalServiceGrpc
                                        .newBlockingStub(workerChannel),
                                properties.worker().maxResponseBytes(),
                                metrics),
                        new WorkerProposalValidator(),
                        governance,
                        properties,
                        Clock.systemUTC(),
                        workerExecutor);
                fullPathConsumerGate.claim();
                var consumer = new MemoryEventConsumer(
                        jdbc,
                        redis,
                        new CheckpointService(jdbc, transactionManager),
                        processor,
                        "m5-load-single-group",
                        "m5-load-single-consumer",
                        1,
                        Duration.ofSeconds(1),
                        Clock.systemUTC(),
                        metrics);
                assertThat(publisher.publishBatch(1)).isOne();
                consumerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "m5-load-consumer");
                    thread.setDaemon(false);
                    return thread;
                });
                consumed = consumerExecutor.submit(consumer::drainOnce);
                JsonNode active = waitForRecordBeforeClientDone(config, python);
                validateWorkerActive(active, config.nonce(), config.profile());
            }

            int exit = waitForPython(python, config.durationSeconds() + 90L);
            requireSuccessfulPython(exit, config, boundaryDiagnostics, timing);
            JsonNode clientDone = readPlainJson(config.runDirectory().resolve(CLIENT_DONE_FILE));
            if ("full".equals(config.profile())) {
                requireClientCompletionOrder(true, Files.exists(
                        config.runDirectory().resolve(CLIENT_DONE_FILE), LinkOption.NOFOLLOW_LINKS));
            }
            JsonNode clientResult = validateClientDone(clientDone, config);
            if (consumed != null) {
                assertThat(consumed.get(30, TimeUnit.SECONDS)).isOne();
            }
            if ("full".equals(config.profile())) {
                fullProfileCompleted.set(true);
            }
            ObjectNode authorityCriticalCounters = authorityCriticalCounters(config);
            ObjectNode report = buildReport(config, clientResult, timing, authorityCriticalCounters);
            double javaP100Ms = timing.snapshot().stream()
                    .mapToLong(Long::longValue)
                    .max()
                    .orElseThrow() / 1_000_000.0d;
            System.out.println("REAL_JAVA_RESOLVE_SAMPLES=" + timing.snapshot().size()
                    + " REAL_JAVA_RESOLVE_P100_MS=" + javaP100Ms);
            ObjectNode done = JSON.createObjectNode()
                    .put("schema_version", PROTOCOL_VERSION)
                    .put("status", "passed")
                    .put("nonce", config.nonce())
                    .put("profile", config.profile());
            if ("full".equals(config.profile())) {
                done.put("worker_off_p95_ms", Objects.requireNonNull(workerOffP95Ms));
            } else {
                done.putNull("worker_off_p95_ms");
            }
            done.set("report", report);
            rejectRawFields(done);
            revalidateRunDirectory(config, false);
            atomicWriteJson(config.runDirectory().resolve(DONE_FILE), done);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            try {
                if (python != null && python.isAlive()) {
                    python.destroy();
                    if (!python.waitFor(5, TimeUnit.SECONDS)) {
                        python.destroyForcibly();
                        python.waitFor(5, TimeUnit.SECONDS);
                    }
                } else if (python != null) {
                    python.waitFor(5, TimeUnit.SECONDS);
                }
                shutdown(consumerExecutor);
                shutdown(workerExecutor);
                if (workerChannel != null) {
                    workerChannel.shutdownNow();
                    workerChannel.awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                if (server != null) {
                    server.shutdownNow();
                    server.awaitTermination(5, TimeUnit.SECONDS);
                }
            }
            if (python != null) {
                try {
                    requireStopped(python);
                } catch (RuntimeException leak) {
                    if (failure == null) {
                        throw leak;
                    }
                    failure.addSuppressed(leak);
                }
            }
            if (ownedRun != null) {
                deleteOwnedTree(ownedRun);
            }
            REDIS.stop();
            POSTGRES.stop();
        }
    }

    @AfterAll
    void requireHarnessExecution() {
        assertThat(harnessExecuted.get() || diagnosticTestExecuted.get()).isTrue();
    }

    @Test
    @Order(3)
    void fullPathSingletonGatesRemainClaimedAfterFullProfile() {
        if (!fullProfileCompleted.get()) {
            return;
        }
        assertThatThrownBy(fullPathWorkerGate::claim).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(fullPathConsumerGate::claim).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Order(4)
    @Tag("diagnostic")
    void r33_diagnostic_validation_on_synthetic_slow_commit() throws Exception {
        diagnosticTestExecuted.set(true);
        EventCallPhaseState diagnosticState = new EventCallPhaseState();
        diagnosticState.advance(
                EventCallPhase.SERVER_ENTERED,
                EventCallPhase.SERVICE_METHOD_ENTERED);
        diagnosticState.advance(
                EventCallPhase.SERVICE_METHOD_ENTERED,
                EventCallPhase.USE_CASE_ENTERED);
        Deadline commitDeadline = Deadline.after(50L, TimeUnit.MILLISECONDS);
        diagnosticState.recordGrpcDeadlineAtTransactionBeforeCommit(commitDeadline);
        diagnosticState.recordGrpcDeadlineAtTransactionBeforeCompletion(commitDeadline);
        Thread.sleep(120L);
        diagnosticState.recordGrpcDeadlineAtTransactionFirstAfterCommit(commitDeadline);
        diagnosticState.recordGrpcDeadlineAtTransactionAfterCommit(commitDeadline);
        EventCallSnapshot diagnosticSnapshot = diagnosticState.snapshot();
        assertThat(diagnosticSnapshot.grpcDeadlineAtTransactionBeforeCommit())
                .isEqualTo(EventGrpcDeadlineAtTransactionBeforeCommitState.ACTIVE);
        assertThat(diagnosticSnapshot.grpcDeadlineAtTransactionBeforeCompletion())
                .isEqualTo(EventGrpcDeadlineAtTransactionBeforeCompletionState.ACTIVE);
        assertThat(diagnosticSnapshot.grpcDeadlineAtTransactionFirstAfterCommit())
                .isEqualTo(EventGrpcDeadlineAtTransactionFirstAfterCommitState.EXPIRED);
        assertThat(diagnosticSnapshot.grpcDeadlineAtTransactionAfterCommit())
                .isEqualTo(EventGrpcDeadlineAtTransactionAfterCommitState.EXPIRED);
        assertThat(exactEventGrpcDeadlineAtTransactionBeforeCommitLabel(diagnosticSnapshot))
                .endsWith("_active");
        assertThat(exactEventGrpcDeadlineAtTransactionBeforeCompletionLabel(diagnosticSnapshot))
                .endsWith("_active");
        assertThat(exactEventGrpcDeadlineAtTransactionFirstAfterCommitLabel(diagnosticSnapshot))
                .endsWith("_expired");
        assertThat(exactEventGrpcDeadlineAtTransactionAfterCommitLabel(diagnosticSnapshot))
                .endsWith("_expired");
    }

    @Test
    @Order(5)
    @Tag("diagnostic")
    void r37_diagnostic_validation_on_synthetic_slow_resolve() throws Exception {
        diagnosticTestExecuted.set(true);
        ResolveCallPhaseState diagnosticState = new ResolveCallPhaseState();
        diagnosticState.advance(
                ResolveCallPhase.SERVER_ENTERED,
                ResolveCallPhase.SERVICE_METHOD_ENTERED);
        diagnosticState.advance(
                ResolveCallPhase.SERVICE_METHOD_ENTERED,
                ResolveCallPhase.USE_CASE_ACTIVE);
        Deadline resolveDeadline = Deadline.after(50L, TimeUnit.MILLISECONDS);
        diagnosticState.recordGrpcDeadlineAtUseCaseStart(resolveDeadline);
        Thread.sleep(120L);
        diagnosticState.recordGrpcDeadlineAtUseCaseCompletion(resolveDeadline);
        diagnosticState.advance(
                ResolveCallPhase.USE_CASE_ACTIVE,
                ResolveCallPhase.USE_CASE_COMPLETED);
        diagnosticState.recordGrpcDeadlineAtResponseSent(resolveDeadline);
        diagnosticState.advance(
                ResolveCallPhase.USE_CASE_COMPLETED,
                ResolveCallPhase.ON_COMPLETED_ENTERED);
        diagnosticState.advance(
                ResolveCallPhase.ON_COMPLETED_ENTERED,
                ResolveCallPhase.SERVER_CLOSED);
        diagnosticState.advance(
                ResolveCallPhase.SERVER_CLOSED,
                ResolveCallPhase.ON_COMPLETED_RETURNED);
        diagnosticState.recordGrpcDeadlineAtOnCompletedReturn(resolveDeadline);
        ResolveCallSnapshot diagnosticSnapshot = diagnosticState.snapshot();
        assertThat(diagnosticSnapshot.grpcDeadlineAtUseCaseStart())
                .isEqualTo(ResolveGrpcDeadlineAtUseCaseStartState.ACTIVE);
        assertThat(diagnosticSnapshot.grpcDeadlineAtUseCaseCompletion())
                .isEqualTo(ResolveGrpcDeadlineAtUseCaseCompletionState.EXPIRED);
        assertThat(diagnosticSnapshot.grpcDeadlineAtResponseSent())
                .isEqualTo(ResolveGrpcDeadlineAtResponseSentState.EXPIRED);
        assertThat(diagnosticSnapshot.grpcDeadlineAtOnCompletedReturn())
                .isEqualTo(ResolveGrpcDeadlineAtOnCompletedReturnState.EXPIRED);
        assertThat(exactResolveGrpcDeadlineAtUseCaseStartLabel(diagnosticSnapshot))
                .endsWith("_active");
        assertThat(resolveGrpcDeadlineAtUseCaseCompletionLabel(diagnosticSnapshot))
                .endsWith("_expired");
        assertThat(exactResolveGrpcDeadlineAtResponseSentLabel(diagnosticSnapshot))
                .endsWith("_expired");
        assertThat(exactResolveGrpcDeadlineAtOnCompletedReturnLabel(diagnosticSnapshot))
                .endsWith("_expired");
    }

    private SignedSessionInterceptor signedSessionInterceptor() {
        var converter = new SubjectAuthenticationConverter(SUBJECT_KEY);
        return new SignedSessionInterceptor(token -> {
            signedIngress.incrementAndGet();
            if (!FIXED_BEARER.equals(token)) {
                throw new SignedSessionInterceptor.InvalidSessionException();
            }
            return new SignedSessionInterceptor.VerifiedSession(
                    "m5-load-signed-root", "session-load-00", 0, List.of("LEARNER"));
        }, converter);
    }

    private String protocolDiagnostic(
            ResolveTimingInterceptor timing,
            M5R3BoundaryDiagnostics boundaryDiagnostics) {
        return "protocol{signed_ingress=" + signedIngress.get()
                + ",identity_ingress=" + identityIngress.get()
                + "," + timing.diagnostic()
                + "," + boundaryDiagnostics.diagnostic() + "}";
    }

    private void resetAuthority() {
        redis.delete(RedisStreamNames.events());
        redis.delete(RedisStreamNames.deadLetter());
        jdbc.sql("UPDATE memory_authority_cutover_state SET authority_mode = 'REMOTE', authority_epoch = 1").update();
    }

    private Map<String, LearnerIdentity> seedSyntheticAuthority() {
        var converter = new SubjectAuthenticationConverter(SUBJECT_KEY);
        var identities = new java.util.LinkedHashMap<String, LearnerIdentity>();
        for (int index = 0; index < 50; index++) {
            String session = "session-load-" + String.format("%02d", index);
            String immutable = "m5-load-learner-" + String.format("%02d", index);
            String subjectHash = converter.fromVerifiedClaims(immutable, List.of("LEARNER")).subjectHash();
            UUID learnerId = stableUuid("learner|" + session);
            UUID policyId = stableUuid("policy|" + session);
            identities.put(session, new LearnerIdentity(subjectHash, session, 0));
            jdbc.sql("""
                            INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                            VALUES (:id, :subject, 'ACTIVE', CURRENT_TIMESTAMP)
                            """)
                    .param("id", learnerId)
                    .param("subject", subjectHash)
                    .update();
            jdbc.sql("""
                            INSERT INTO consent_policy_version (
                                consent_policy_version_id, learner_subject_id, revision, status,
                                allowed_categories, valid_from, valid_until, created_at,
                                purpose_text_version, source_capture_enabled, retention_days, actor_role)
                            VALUES (
                                :policy, :learner, 0, 'ACTIVE',
                                CAST(:categories AS jsonb), CURRENT_TIMESTAMP - INTERVAL '1 minute',
                                CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP,
                                'm5-load-test-v1', true, 1, 'SYSTEM')
                            """)
                    .param("policy", policyId)
                    .param("learner", learnerId)
                    .param("categories", "[\"PREFERENCE\",\"REFLECTION\",\"MASTERY\",\"MISCONCEPTION\"]")
                    .update();
            jdbc.sql("""
                            INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at)
                            VALUES (:learner, 1, 0, CURRENT_TIMESTAMP)
                            """)
                    .param("learner", learnerId)
                    .update();
            seedMinimalGovernedPreference(learnerId, subjectHash, session);
        }
        assertThat(identities).hasSize(50);
        assertThat(jdbc.sql("SELECT count(*) FROM learner_subject").query(Long.class).single()).isEqualTo(50);
        assertThat(jdbc.sql("SELECT count(*) FROM memory_head_projection").query(Long.class).single()).isEqualTo(50);
        return Map.copyOf(identities);
    }

    private static void warmAuthorityReads(
            ResolveMemoryContextUseCase resolver,
            Map<String, LearnerIdentity> identities) {
        for (LearnerIdentity identity : identities.values()) {
            var resolution = resolver.resolve(
                    identity,
                    loadMemoryQuery("synthetic-load-warmup", "load-warmup", 1));
            assertThat(resolution.status().name()).isNotEqualTo("DEGRADED");
        }
    }

    private void seedMinimalGovernedPreference(UUID learnerId, String subjectHash, String session) {
        UUID eventId = stableUuid("seed-event|" + session);
        UUID candidateId = stableUuid("seed-candidate|" + session);
        UUID assertionId = stableUuid("seed-assertion|" + session);
        UUID versionId = stableUuid("seed-version|" + session);
        byte[] source = "{\"synthetic\":\"preference\"}".getBytes(StandardCharsets.UTF_8);
        jdbc.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type, source_kind,
                            occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext,
                            payload_digest, trace_id)
                        VALUES (
                            :event, 'v1', :learner, :session, 'PREFERENCE', 'EXPLICIT_DECLARATION',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'STANDARD', 0, :source, :digest, 'm5-load-seed')
                        """)
                .param("event", eventId)
                .param("learner", learnerId)
                .param("session", session)
                .param("source", source)
                .param("digest", sha256(source))
                .update();
        jdbc.sql("""
                        INSERT INTO memory_candidate (
                            candidate_id, learner_subject_id, event_id, event_schema_version, memory_type,
                            status, privacy_level, candidate_ciphertext, candidate_digest, source_count, created_at)
                        VALUES (
                            :candidate, :learner, :event, 'v1', 'PREFERENCE',
                            'GOVERNED', 'STANDARD', :value, :digest, 1, CURRENT_TIMESTAMP)
                        """)
                .param("candidate", candidateId)
                .param("learner", learnerId)
                .param("event", eventId)
                .param("value", "{\"answer_style\":\"concise\"}".getBytes(StandardCharsets.UTF_8))
                .param("digest", sha256("{\"answer_style\":\"concise\"}".getBytes(StandardCharsets.UTF_8)))
                .update();
        jdbc.sql("""
                        INSERT INTO governance_decision (
                            governance_decision_id, candidate_id, learner_subject_id, decision,
                            rule_set_version, reason_codes, decided_at)
                        VALUES (:decision, :candidate, :learner, 'ACCEPTED', 'm5-load-v1', '[]'::jsonb, CURRENT_TIMESTAMP)
                        """)
                .param("decision", stableUuid("seed-decision|" + session))
                .param("candidate", candidateId)
                .param("learner", learnerId)
                .update();
        jdbc.sql("""
                        INSERT INTO memory_assertion (
                            memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at)
                        VALUES (:assertion, :learner, :key, 'PREFERENCE', CURRENT_TIMESTAMP)
                        """)
                .param("assertion", assertionId)
                .param("learner", learnerId)
                .param("key", "answer_style-" + session)
                .update();
        jdbc.sql("""
                        INSERT INTO memory_version (
                            memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                            status, value_json, valid_from, valid_until, recorded_at, confidence,
                            stability_score, privacy_level, consent_revision, created_at)
                        VALUES (
                            :version, :assertion, :learner, 1, 'ACTIVE',
                            CAST(:value AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day',
                            CURRENT_TIMESTAMP, 0.9000, 0.9000, 'STANDARD', 0, CURRENT_TIMESTAMP)
                        """)
                .param("version", versionId)
                .param("assertion", assertionId)
                .param("learner", learnerId)
                .param("value", "{\"answer_style\":\"concise\"}")
                .update();
        jdbc.sql("""
                        INSERT INTO memory_transition (
                            memory_transition_id, memory_assertion_id, learner_subject_id,
                            to_memory_version_id, transition_type, reason_code, transitioned_at)
                        VALUES (:transition, :assertion, :learner, :version, 'ACTIVATE', 'M5_LOAD_SEED', CURRENT_TIMESTAMP)
                        """)
                .param("transition", stableUuid("seed-transition|" + session))
                .param("assertion", assertionId)
                .param("learner", learnerId)
                .param("version", versionId)
                .update();
        jdbc.sql("""
                        INSERT INTO memory_head_projection (
                            memory_assertion_id, learner_subject_id, memory_version_id, status, memory_epoch, updated_at)
                        VALUES (:assertion, :learner, :version, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """)
                .param("assertion", assertionId)
                .param("learner", learnerId)
                .param("version", versionId)
                .update();
        jdbc.sql("""
                        INSERT INTO memory_source_link (
                            memory_source_link_id, memory_version_id, learner_subject_id,
                            source_event_id, source_schema_version, source_kind, created_at)
                        VALUES (:link, :version, :learner, :event, 'v1', 'INTERACTION_EVENT', CURRENT_TIMESTAMP)
                        """)
                .param("link", stableUuid("seed-link|" + session))
                .param("version", versionId)
                .param("learner", learnerId)
                .param("event", eventId)
                .update();
    }

    private void seedCandidateEvent(LearnerIdentity identity) {
        assertThat(identity).isNotNull();
        UUID eventId = stableUuid("candidate-authority-path");
        byte[] source = "{\"text\":\"请以后回答简短一点\"}".getBytes(StandardCharsets.UTF_8);
        var command = new SubmitMemoryEventsUseCase.InteractionEventCommand(
                eventId,
                "v1",
                "PREFERENCE",
                MemoryCategory.PREFERENCE,
                SourceKind.EXPLICIT_DECLARATION,
                identity.subjectHash(),
                identity.consentRevision(),
                Instant.now(),
                PrivacyLevel.SENSITIVE,
                source,
                "m5-load-worker-path");
        var receipt = submitEvents.submit(identity, List.of(command)).getFirst();
        assertThat(receipt.result()).isEqualTo(SubmitMemoryEventsUseCase.EventResult.ACCEPTED);
    }

    private ObjectNode authorityCriticalCounters(RunConfig config) {
        long expectedEvents = Math.multiplyExact(50L, config.durationSeconds());
        long acceptedLoadEvents = jdbc.sql("""
                        SELECT count(*) FROM interaction_event
                        WHERE session_id LIKE 'session-load-%'
                          AND event_type = 'REFLECTION'
                          AND trace_id LIKE 'load-%'
                        """)
                .query(Long.class)
                .single();
        long duplicateActive = jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT memory_assertion_id
                            FROM memory_version
                            WHERE status = 'ACTIVE'
                            GROUP BY memory_assertion_id
                            HAVING count(*) > 1
                        ) duplicates
                        """)
                .query(Long.class)
                .single();
        long crossUserLeakage = jdbc.sql("""
                        SELECT count(*)
                        FROM memory_source_link link
                        JOIN memory_version version ON version.memory_version_id = link.memory_version_id
                        JOIN interaction_event event
                          ON event.event_id = link.source_event_id
                         AND event.schema_version = link.source_schema_version
                        WHERE link.learner_subject_id <> version.learner_subject_id
                           OR link.learner_subject_id <> event.learner_subject_id
                        """)
                .query(Long.class)
                .single();
        if ("full".equals(config.profile())) {
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM async_processing_checkpoint
                            WHERE stage_name = 'CANDIDATE_EXTRACTION' AND status = 'SUCCEEDED'
                            """).query(Long.class).single()).isOne();
        }
        return JSON.createObjectNode()
                .put("acknowledged_loss", Math.max(0L, expectedEvents - acceptedLoadEvents))
                .put("duplicate_active", duplicateActive)
                .put("cross_user_leakage", crossUserLeakage);
    }

    private ObjectNode buildReport(
            RunConfig config,
            JsonNode clientResult,
            ResolveTimingInterceptor timing,
            ObjectNode authorityCriticalCounters) {
        ObjectNode counts = requireObject(clientResult, "counts");
        Set<String> clientFields = fieldNames(clientResult);
        if (!clientFields.equals(Set.of(
                "counts", "python_loopback_wait_samples_ns", "test_pipeline_answer_samples_ns"))) {
            throw new IllegalArgumentException("client result has unsupported fields");
        }
        ObjectNode report = JSON.createObjectNode()
                .put("profile", config.profile());
        report.set("counts", counts.deepCopy());
        report.set("java_resolve_service_ms", latencySummary(timing.snapshot(), false));
        report.set("python_loopback_grpc_wait_ms", latencySummary(
                longSamples(clientResult.path("python_loopback_wait_samples_ns")), true));
        report.put("test_pipeline_answer_p95_ms",
                quantile(longSamples(clientResult.path("test_pipeline_answer_samples_ns")), 0.95d));
        report.set("critical_counters", authorityCriticalCounters);
        ObjectNode resources = JSON.createObjectNode();
        ProcessHandle.current().info().totalCpuDuration()
                .ifPresentOrElse(
                        value -> resources.put("cpu_time_ms", value.toMillis()),
                        () -> resources.putNull("cpu_time_ms"));
        resources.putNull("java_working_set_bytes");
        resources.putNull("gpu_vram_bytes");
        resources.put("gpu_reason", "unavailable");
        report.set("resources", resources);
        return report;
    }

    private static ObjectNode latencySummary(List<Long> samples, boolean maximum) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("latency samples are required");
        }
        ObjectNode result = JSON.createObjectNode()
                .put("p50", quantile(samples, 0.50d))
                .put("p95", quantile(samples, 0.95d))
                .put("p99", quantile(samples, 0.99d));
        if (maximum) {
            result.put("max", samples.stream().mapToLong(Long::longValue).max().orElseThrow() / 1_000_000.0d);
        }
        return result;
    }

    private static double quantile(List<Long> source, double quantile) {
        List<Long> values = source.stream().sorted().toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException("latency samples are required");
        }
        if (values.size() == 1) {
            return values.getFirst() / 1_000_000.0d;
        }
        double position = (values.size() - 1) * quantile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double interpolated = values.get(lower) + (position - lower) * (values.get(upper) - values.get(lower));
        if (!Double.isFinite(interpolated) || interpolated < 0) {
            throw new IllegalArgumentException("latency quantile must be finite");
        }
        return interpolated / 1_000_000.0d;
    }

    private static List<Long> longSamples(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException("finite aggregate samples are required");
        }
        var result = new ArrayList<Long>();
        value.forEach(sample -> {
            if (!sample.canConvertToLong() || sample.longValue() < 0) {
                throw new IllegalArgumentException("aggregate sample must be non-negative integer");
            }
            result.add(sample.longValue());
        });
        return List.copyOf(result);
    }

    private static Process startPrivatePython(RunConfig config) throws IOException {
        var builder = new ProcessBuilder(
                config.python().toString(),
                "-m",
                "scripts.run_memory_load",
                "--loopback-client",
                "--run-directory", config.runDirectory().toString(),
                "--nonce", config.nonce(),
                "--profile", config.profile());
        builder.directory(config.repositoryRoot().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(config.runDirectory().resolve("python-child.log").toFile());
        Map<String, String> environment = builder.environment();
        String systemRoot = environment.get("SystemRoot");
        String windir = environment.get("WINDIR");
        String temp = environment.get("TEMP");
        String tmp = environment.get("TMP");
        environment.clear();
        putIfPresent(environment, "SystemRoot", systemRoot);
        putIfPresent(environment, "WINDIR", windir);
        putIfPresent(environment, "TEMP", temp);
        putIfPresent(environment, "TMP", tmp);
        environment.put("PYTHONDONTWRITEBYTECODE", "1");
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.put("YILAN_M5_LOAD_HARNESS", HARNESS_MARKER);
        return builder.start();
    }

    private static void putIfPresent(Map<String, String> environment, String key, String value) {
        if (value != null && !value.isBlank()) {
            environment.put(key, value);
        }
    }

    private static int waitForPython(Process process, long timeoutSeconds) throws InterruptedException {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("private Python client timed out");
        }
        return process.exitValue();
    }

    private static void requireAbsent(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("worker-off profile created forbidden worker activity");
        }
    }

    private static void clearWorkerOffArtifacts(Path runDirectory) throws IOException {
        for (String name : List.of(
                READY_FILE,
                CLIENT_DONE_FILE,
                "python-child.log",
                "pipeline-checkpoints.sqlite3",
                "pipeline-checkpoints.sqlite3-shm",
                "pipeline-checkpoints.sqlite3-wal",
                "pipeline-memory.sqlite3",
                "pipeline-memory.sqlite3-shm",
                "pipeline-memory.sqlite3-wal")) {
            Path path = runDirectory.resolve(name);
            if (Files.isSymbolicLink(path)) {
                throw new IOException("worker-off artifact became a link");
            }
            Files.deleteIfExists(path);
        }
    }

    private static String privatePythonFailureLabel(int exit) {
        return switch (exit) {
            case 41 -> "grpc_deadline_exceeded";
            case 42 -> "scheduler_bucket_miss";
            case 43 -> "client_validation_failure";
            case 44 -> "client_unclassified_failure";
            case 45 -> "scheduled_resolve_deadline_exceeded";
            case 46 -> "scheduled_event_deadline_exceeded";
            case 47 -> "scheduler_dispatcher_saturated";
            case 48 -> "scheduler_dispatcher_bucket_crossed";
            case 49 -> "scheduler_bucket_crossed_pending_saturated";
            case 50 -> "scheduler_bucket_crossed_pending_available";
            case 51 -> "scheduled_event_deadline_before_dispatch_complete";
            case 52 -> "scheduled_event_deadline_after_dispatch_complete";
            case 53 -> "scheduled_event_deadline_before_dispatch_complete_response_header_observed";
            case 54 -> "scheduled_event_deadline_before_dispatch_complete_response_header_not_observed";
            default -> "client_unknown_failure";
        };
    }

    private static String aggregateResolveFailureLabel(M5R3BoundaryDiagnostics diagnostics) {
        if (!diagnostics.resolveServiceMethodEntered()) {
            return "aggregate_resolve_no_service_entry";
        }
        if (!diagnostics.resolveUseCaseEntered()) {
            return "aggregate_resolve_service_without_use_case";
        }
        if (!diagnostics.resolveAuthorityAdapterEntered()) {
            return "aggregate_resolve_use_case_without_authority_adapter";
        }
        if (!diagnostics.resolveAuthorityAdapterComplete()) {
            return "aggregate_resolve_authority_adapter_incomplete";
        }
        return "aggregate_resolve_authority_adapter_complete";
    }

    private static String aggregateEventFailureLabel(
            M5R3BoundaryDiagnostics diagnostics) {
        if (!diagnostics.submitEventUseCaseEntered()) {
            return "aggregate_event_no_use_case_entry";
        }
        if (!diagnostics.submitEventAuthorityAdapterEntered()) {
            return "aggregate_event_use_case_without_authority_adapter";
        }
        if (diagnostics.submitEventAuthorityAdapterEntered()
                && !diagnostics.submitEventAuthorityAdapterComplete()) {
            return "aggregate_event_authority_adapter_incomplete";
        }
        if (!diagnostics.submitEventUseCaseComplete()) {
            return "aggregate_event_authority_adapter_complete_use_case_incomplete";
        }
        return "aggregate_event_use_case_complete";
    }

    private record EventDeadlineDiagnosticSnapshot(
            long submitEventUseCaseMaximumDurationNanos,
            long submitEventAuthorityAdapterMaximumDurationNanos,
            long submitEventConsentPolicyMaximumDurationNanos,
            long submitEventSourceKeyMaximumDurationNanos,
            long submitEventSourceEnvelopeMaximumDurationNanos,
            long submitEventMeasuredComponentSumMaximumDurationNanos,
            long submitEventUnmeasuredResidualMaximumDurationNanos,
            long submitEventApplicationResidualMaximumDurationNanos,
            long submitEventTransactionEnvelopeResidualMaximumDurationNanos,
            long eventServiceEntryCount,
            long eventServiceCloseCount,
            long eventServiceMaximumDurationNanos,
            long eventServiceCloseReturnCount,
            long eventServiceCloseReturnMaximumDurationNanos) {
        private boolean submitEventUseCaseDeadlineCrossed() {
            return submitEventUseCaseMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventAuthorityAdapterCallDeadlineCrossed() {
            return submitEventAuthorityAdapterMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventConsentPolicyCallDeadlineCrossed() {
            return submitEventConsentPolicyMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventSourceKeyCallDeadlineCrossed() {
            return submitEventSourceKeyMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventSourceEnvelopeCallDeadlineCrossed() {
            return submitEventSourceEnvelopeMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventMeasuredComponentSumDeadlineCrossed() {
            return submitEventMeasuredComponentSumMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventUnmeasuredResidualDeadlineCrossed() {
            return submitEventUnmeasuredResidualMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventApplicationResidualDeadlineCrossed() {
            return submitEventApplicationResidualMaximumDurationNanos > 150_000_000L;
        }

        private boolean submitEventTransactionEnvelopeResidualDeadlineCrossed() {
            return submitEventTransactionEnvelopeResidualMaximumDurationNanos > 150_000_000L;
        }

        private boolean eventServiceCallNotEntered() {
            return eventServiceEntryCount == 0L;
        }

        private boolean eventServiceCallIncomplete() {
            return eventServiceEntryCount > eventServiceCloseCount;
        }

        private boolean eventServiceCallDeadlineCrossed() {
            return !eventServiceCallNotEntered()
                    && eventServiceEntryCount == eventServiceCloseCount
                    && eventServiceMaximumDurationNanos > 150_000_000L;
        }

        private boolean eventServiceCallWithinDeadline() {
            return !eventServiceCallNotEntered()
                    && eventServiceEntryCount == eventServiceCloseCount
                    && eventServiceMaximumDurationNanos <= 150_000_000L;
        }

        private boolean eventServiceCallWithinLowerReferenceBand() {
            return eventServiceCallWithinDeadline()
                    && eventServiceMaximumDurationNanos <= 120_000_000L;
        }

        private boolean eventServiceCallWithinUpperReferenceBand() {
            return eventServiceCallWithinDeadline()
                    && eventServiceMaximumDurationNanos > 120_000_000L;
        }

        private boolean eventServiceCloseReturnObservationIncomplete() {
            return eventServiceCloseCount > eventServiceCloseReturnCount;
        }

        private boolean eventServiceCloseReturnDeadlineCrossed() {
            return eventServiceCloseCount > 0L
                    && eventServiceCloseCount == eventServiceCloseReturnCount
                    && eventServiceCloseReturnMaximumDurationNanos > 150_000_000L;
        }

        private boolean eventServiceCloseReturnedWithinDeadline() {
            return eventServiceCloseCount > 0L
                    && eventServiceCloseCount == eventServiceCloseReturnCount
                    && eventServiceCloseReturnMaximumDurationNanos <= 150_000_000L;
        }
    }

    private static String eventDeadlineLocalizationLabel(EventDeadlineDiagnosticSnapshot snapshot) {
        if (snapshot.submitEventUseCaseDeadlineCrossed()) {
            return "aggregate_event_use_case_deadline_crossed";
        }
        if (snapshot.eventServiceCallIncomplete()) {
            return "aggregate_event_service_call_incomplete";
        }
        if (snapshot.eventServiceCallDeadlineCrossed()) {
            return "aggregate_event_post_use_case_deadline_crossed";
        }
        if (snapshot.eventServiceCallWithinDeadline()) {
            return "aggregate_event_server_within_deadline";
        }
        throw new IllegalStateException("bounded event timing state is invalid");
    }

    private static String eventCloseStartReferenceBandLabel(
            EventDeadlineDiagnosticSnapshot snapshot) {
        if (snapshot.eventServiceCallWithinLowerReferenceBand()) {
            return "aggregate_event_close_start_lower_reference_band";
        }
        if (snapshot.eventServiceCallWithinUpperReferenceBand()) {
            return "aggregate_event_close_start_upper_reference_band";
        }
        throw new IllegalStateException("bounded event close-start reference band is invalid");
    }

    private static String aggregateEventCloseReturnLabel(
            EventDeadlineDiagnosticSnapshot snapshot) {
        if (snapshot.eventServiceCloseReturnObservationIncomplete()) {
            return "aggregate_event_server_close_return_observation_incomplete";
        }
        if (snapshot.eventServiceCloseReturnDeadlineCrossed()) {
            return "aggregate_event_server_close_return_deadline_crossed";
        }
        if (snapshot.eventServiceCloseReturnedWithinDeadline()) {
            return "aggregate_event_server_close_returned_within_deadline";
        }
        throw new IllegalStateException("bounded aggregate event close-return state is invalid");
    }

    private static String eventAuthorityAdapterDurationLabel(EventDeadlineDiagnosticSnapshot snapshot) {
        if (!snapshot.submitEventUseCaseDeadlineCrossed()) {
            throw new IllegalStateException("bounded event use-case timing state is invalid");
        }
        if (snapshot.submitEventAuthorityAdapterCallDeadlineCrossed()) {
            return "aggregate_event_authority_adapter_call_deadline_crossed";
        }
        return "aggregate_event_authority_adapter_calls_individually_within_deadline";
    }

    private static String eventConsentPolicyDurationLabel(EventDeadlineDiagnosticSnapshot snapshot) {
        return snapshot.submitEventConsentPolicyCallDeadlineCrossed()
                ? "aggregate_event_consent_call_deadline_crossed"
                : "aggregate_event_consent_calls_individually_within_deadline";
    }

    private static String eventSourceKeyDurationLabel(EventDeadlineDiagnosticSnapshot snapshot) {
        return snapshot.submitEventSourceKeyCallDeadlineCrossed()
                ? "aggregate_event_source_key_call_deadline_crossed"
                : "aggregate_event_source_key_calls_individually_within_deadline";
    }

    private static String eventSourceEnvelopeDurationLabel(EventDeadlineDiagnosticSnapshot snapshot) {
        return snapshot.submitEventSourceEnvelopeCallDeadlineCrossed()
                ? "aggregate_event_source_envelope_call_deadline_crossed"
                : "aggregate_event_source_envelope_calls_individually_within_deadline";
    }

    private static String eventMeasuredComponentSumDurationLabel(
            EventDeadlineDiagnosticSnapshot snapshot) {
        return snapshot.submitEventMeasuredComponentSumDeadlineCrossed()
                ? "aggregate_event_measured_component_sum_deadline_crossed"
                : "aggregate_event_measured_component_sums_within_deadline";
    }

    private static String eventUnmeasuredResidualDurationLabel(
            EventDeadlineDiagnosticSnapshot snapshot) {
        return snapshot.submitEventUnmeasuredResidualDeadlineCrossed()
                ? "aggregate_event_unmeasured_residual_deadline_crossed"
                : "aggregate_event_unmeasured_residuals_within_deadline";
    }

    private static String eventApplicationResidualDurationLabel(
            EventDeadlineDiagnosticSnapshot snapshot) {
        return snapshot.submitEventApplicationResidualDeadlineCrossed()
                ? "aggregate_event_application_residual_deadline_crossed"
                : "aggregate_event_application_residuals_within_deadline";
    }

    private static String eventTransactionEnvelopeResidualDurationLabel(
            EventDeadlineDiagnosticSnapshot snapshot) {
        return snapshot.submitEventTransactionEnvelopeResidualDeadlineCrossed()
                ? "aggregate_event_transaction_envelope_residual_deadline_crossed"
                : "aggregate_event_transaction_envelope_residuals_within_deadline";
    }

    private static String resolveCancellationPhaseLabel(ResolveCallPhase phase) {
        if (phase == null) {
            throw new IllegalStateException("bounded resolve cancellation phase is invalid");
        }
        return switch (phase) {
            case SERVER_ENTERED -> "resolve_cancel_before_service_method";
            case SERVICE_METHOD_ENTERED -> "resolve_cancel_service_before_use_case";
            case USE_CASE_ACTIVE -> "resolve_cancel_use_case_non_adapter";
            case CONSENT_ACTIVE -> "resolve_cancel_consent_inflight";
            case STRUCTURED_ACTIVE -> "resolve_cancel_structured_inflight";
            case KEYWORD_ACTIVE -> "resolve_cancel_keyword_inflight";
            case RECENT_EPISODE_ACTIVE -> "resolve_cancel_recent_episode_inflight";
            case USE_CASE_COMPLETED -> "resolve_cancel_post_use_case";
            case ON_COMPLETED_ENTERED -> "resolve_cancel_during_on_completed";
            case SERVER_CLOSED -> "resolve_cancel_after_server_close";
            case ON_COMPLETED_RETURNED -> "resolve_cancel_after_on_completed_return";
        };
    }

    private static String resolveCancellationPhaseLabel(ResolveCallSnapshot snapshot) {
        return snapshot == null
                ? "resolve_cancel_phase_unobserved"
                : resolveCancellationPhaseLabel(snapshot.phase());
    }

    private static String resolveCloseStartLabel(ResolveCloseStartState state) {
        if (state == null) {
            throw new IllegalStateException("bounded resolve close-start state is invalid");
        }
        return switch (state) {
            case NOT_STARTED -> "resolve_server_close_not_started";
            case WITHIN_DEADLINE -> "resolve_server_close_started_within_deadline";
            case DEADLINE_CROSSED -> "resolve_server_close_start_deadline_crossed";
        };
    }

    private static String resolveCloseStartLabel(ResolveCallSnapshot snapshot) {
        return snapshot == null
                ? "resolve_server_close_not_started"
                : resolveCloseStartLabel(snapshot.closeStart());
    }

    private static String resolveGrpcDeadlineAtUseCaseCompletionLabel(
            ResolveGrpcDeadlineAtUseCaseCompletionState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded resolve gRPC deadline at use-case completion state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "resolve_grpc_deadline_at_use_case_completion_not_recorded";
            case UNAVAILABLE ->
                    "resolve_grpc_deadline_at_use_case_completion_unavailable";
            case ACTIVE ->
                    "resolve_grpc_deadline_at_use_case_completion_active";
            case EXPIRED ->
                    "resolve_grpc_deadline_at_use_case_completion_expired";
        };
    }

    private static String resolveGrpcDeadlineAtUseCaseCompletionLabel(
            ResolveCallSnapshot snapshot) {
        return snapshot == null
                ? "resolve_grpc_deadline_at_use_case_completion_not_recorded"
                : resolveGrpcDeadlineAtUseCaseCompletionLabel(
                        snapshot.grpcDeadlineAtUseCaseCompletion());
    }

    private static String exactResolveGrpcDeadlineAtUseCaseStartLabel(
            ResolveGrpcDeadlineAtUseCaseStartState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded resolve gRPC deadline at use-case start state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "exact_resolve_grpc_deadline_at_use_case_start_not_recorded";
            case UNAVAILABLE ->
                    "exact_resolve_grpc_deadline_at_use_case_start_unavailable";
            case ACTIVE ->
                    "exact_resolve_grpc_deadline_at_use_case_start_active";
            case EXPIRED ->
                    "exact_resolve_grpc_deadline_at_use_case_start_expired";
        };
    }

    private static String exactResolveGrpcDeadlineAtUseCaseStartLabel(
            ResolveCallSnapshot snapshot) {
        return snapshot == null
                ? "exact_resolve_grpc_deadline_at_use_case_start_not_recorded"
                : exactResolveGrpcDeadlineAtUseCaseStartLabel(
                        snapshot.grpcDeadlineAtUseCaseStart());
    }

    private static String exactResolveGrpcDeadlineAtResponseSentLabel(
            ResolveGrpcDeadlineAtResponseSentState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded resolve gRPC deadline at response sent state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "exact_resolve_grpc_deadline_at_response_sent_not_recorded";
            case UNAVAILABLE ->
                    "exact_resolve_grpc_deadline_at_response_sent_unavailable";
            case ACTIVE ->
                    "exact_resolve_grpc_deadline_at_response_sent_active";
            case EXPIRED ->
                    "exact_resolve_grpc_deadline_at_response_sent_expired";
        };
    }

    private static String exactResolveGrpcDeadlineAtResponseSentLabel(
            ResolveCallSnapshot snapshot) {
        return snapshot == null
                ? "exact_resolve_grpc_deadline_at_response_sent_not_recorded"
                : exactResolveGrpcDeadlineAtResponseSentLabel(
                        snapshot.grpcDeadlineAtResponseSent());
    }

    private static String exactResolveGrpcDeadlineAtOnCompletedReturnLabel(
            ResolveGrpcDeadlineAtOnCompletedReturnState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded resolve gRPC deadline at on completed return state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "exact_resolve_grpc_deadline_at_on_completed_return_not_recorded";
            case UNAVAILABLE ->
                    "exact_resolve_grpc_deadline_at_on_completed_return_unavailable";
            case ACTIVE ->
                    "exact_resolve_grpc_deadline_at_on_completed_return_active";
            case EXPIRED ->
                    "exact_resolve_grpc_deadline_at_on_completed_return_expired";
        };
    }

    private static String exactResolveGrpcDeadlineAtOnCompletedReturnLabel(
            ResolveCallSnapshot snapshot) {
        return snapshot == null
                ? "exact_resolve_grpc_deadline_at_on_completed_return_not_recorded"
                : exactResolveGrpcDeadlineAtOnCompletedReturnLabel(
                        snapshot.grpcDeadlineAtOnCompletedReturn());
    }

    private static String eventCancellationPhaseLabel(EventCallPhase phase) {
        if (phase == null) {
            throw new IllegalStateException("bounded event cancellation phase is invalid");
        }
        return switch (phase) {
            case SERVER_ENTERED -> "event_cancel_before_service_method";
            case SERVICE_METHOD_ENTERED -> "event_cancel_service_before_use_case";
            case USE_CASE_ENTERED -> "event_cancel_use_case_inflight";
            case USE_CASE_COMPLETED -> "event_cancel_post_use_case";
            case ON_COMPLETED_ENTERED -> "event_cancel_during_on_completed";
            case SERVER_CLOSED -> "event_cancel_after_server_close";
            case ON_COMPLETED_RETURNED -> "event_cancel_after_on_completed_return";
        };
    }

    private static String eventCancellationPhaseLabel(EventCallSnapshot snapshot) {
        return snapshot == null
                ? "event_cancel_phase_unobserved"
                : eventCancellationPhaseLabel(snapshot.phase());
    }

    private static String eventCloseReturnLabel(EventCloseReturnState state) {
        if (state == null) {
            throw new IllegalStateException("bounded event close-return state is invalid");
        }
        return switch (state) {
            case NOT_RETURNED -> "event_server_close_not_returned";
            case WITHIN_DEADLINE -> "event_server_close_returned_within_deadline";
            case DEADLINE_CROSSED -> "event_server_close_return_deadline_crossed";
        };
    }

    private static String eventCloseReturnLabel(EventCallSnapshot snapshot) {
        return snapshot == null
                ? "event_server_close_not_returned"
                : eventCloseReturnLabel(snapshot.closeReturn());
    }

    private static String exactEventPhaseLabel(EventCallSnapshot snapshot) {
        if (snapshot == null) {
            return "exact_event_interceptor_not_observed";
        }
        return switch (snapshot.phase()) {
            case SERVER_ENTERED -> "exact_event_server_entered";
            case SERVICE_METHOD_ENTERED -> "exact_event_service_method_entered";
            case USE_CASE_ENTERED -> "exact_event_use_case_entered";
            case USE_CASE_COMPLETED -> "exact_event_use_case_completed";
            case ON_COMPLETED_ENTERED -> "exact_event_on_completed_entered";
            case SERVER_CLOSED -> "exact_event_server_closed";
            case ON_COMPLETED_RETURNED -> "exact_event_on_completed_returned";
        };
    }

    private static String exactEventCloseReturnLabel(EventCallSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException("exact event close-return state is unavailable");
        }
        return switch (snapshot.closeReturn()) {
            case NOT_RETURNED -> "exact_event_server_close_not_returned";
            case WITHIN_DEADLINE -> "exact_event_server_close_returned_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_server_close_return_deadline_crossed";
        };
    }

    private static String exactEventCloseReturnReferenceBandLabel(
            EventCallSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException(
                    "exact event close-return reference band is unavailable");
        }
        return switch (snapshot.closeReturnReferenceBand()) {
            case NOT_RETURNED -> "exact_event_server_close_return_not_observed";
            case LOWER_REFERENCE_BAND ->
                    "exact_event_server_close_return_lower_reference_band";
            case UPPER_REFERENCE_BAND ->
                    "exact_event_server_close_return_upper_reference_band";
            case DEADLINE_CROSSED ->
                    "exact_event_server_close_return_reference_band_deadline_crossed";
        };
    }

    private static String exactEventGrpcDeadlineAtCloseReturnLabel(
            EventCallSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException(
                    "exact event gRPC deadline at close-return state is unavailable");
        }
        return switch (snapshot.grpcDeadlineAtCloseReturn()) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_at_close_return_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_at_close_return_unavailable";
            case ACTIVE ->
                    "exact_event_grpc_deadline_at_close_return_active";
            case EXPIRED ->
                    "exact_event_grpc_deadline_at_close_return_expired";
        };
    }

    private static String exactEventGrpcDeadlineHeadroomLabel(
            EventCallSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException(
                    "exact event gRPC deadline headroom state is unavailable");
        }
        return switch (snapshot.grpcDeadlineHeadroom()) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_headroom_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_headroom_unavailable";
            case EXPIRED ->
                    "exact_event_grpc_deadline_headroom_expired";
            case ACTIVE_WITHIN_30_MS_REFERENCE_BAND ->
                    "exact_event_grpc_deadline_headroom_active_within_30ms_reference_band";
            case ACTIVE_ABOVE_30_MS_REFERENCE_BAND ->
                    "exact_event_grpc_deadline_headroom_active_above_30ms_reference_band";
        };
    }

    private static String exactEventGrpcDeadlineIngressHeadroomLabel(
            EventCallSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException(
                    "exact event gRPC deadline ingress headroom state is unavailable");
        }
        return switch (snapshot.grpcDeadlineIngressHeadroom()) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_ingress_headroom_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_ingress_headroom_unavailable";
            case EXPIRED ->
                    "exact_event_grpc_deadline_ingress_headroom_expired";
            case WITHIN_120_MS_REFERENCE_BAND ->
                    "exact_event_grpc_deadline_ingress_headroom_within_120ms_reference_band";
            case ABOVE_120_MS_REFERENCE_BAND ->
                    "exact_event_grpc_deadline_ingress_headroom_above_120ms_reference_band";
        };
    }

    private static String exactEventTransportTerminalLabel(
            EventCallSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException(
                    "exact event transport terminal state is unavailable");
        }
        return switch (snapshot.transportTerminal()) {
            case NOT_RECORDED ->
                    "exact_event_transport_terminal_not_recorded";
            case OK ->
                    "exact_event_transport_terminal_ok";
            case CANCELLED ->
                    "exact_event_transport_terminal_cancelled";
            case OTHER ->
                    "exact_event_transport_terminal_other";
        };
    }

    private static String exactEventUseCaseStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.useCase()) {
            case NOT_COMPLETED -> "exact_event_use_case_not_completed";
            case WITHIN_DEADLINE -> "exact_event_use_case_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_use_case_deadline_crossed";
        };
    }

    private static String exactEventGrpcDeadlineAtUseCaseCompletionLabel(
            EventCallSnapshot snapshot) {
        return switch (snapshot.grpcDeadlineAtUseCaseCompletion()) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_at_use_case_completion_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_at_use_case_completion_unavailable";
            case ACTIVE ->
                    "exact_event_grpc_deadline_at_use_case_completion_active";
            case EXPIRED ->
                    "exact_event_grpc_deadline_at_use_case_completion_expired";
        };
    }

    private static String exactEventAuthorityAdapterStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.authorityAdapterCalls()) {
            case NOT_COMPLETED -> "exact_event_authority_adapter_calls_not_completed";
            case WITHIN_DEADLINE ->
                    "exact_event_authority_adapter_calls_individually_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_authority_adapter_call_deadline_crossed";
        };
    }

    private static String exactEventConsentPolicyStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.consentPolicy()) {
            case NOT_COMPLETED -> "exact_event_consent_call_not_completed";
            case WITHIN_DEADLINE -> "exact_event_consent_call_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_consent_call_deadline_crossed";
        };
    }

    private static String exactEventSourceKeyStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.sourceKey()) {
            case NOT_COMPLETED -> "exact_event_source_key_call_not_completed";
            case WITHIN_DEADLINE -> "exact_event_source_key_call_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_source_key_call_deadline_crossed";
        };
    }

    private static String exactEventSourceEnvelopeStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.sourceEnvelope()) {
            case NOT_COMPLETED -> "exact_event_source_envelope_call_not_completed";
            case WITHIN_DEADLINE -> "exact_event_source_envelope_call_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_source_envelope_call_deadline_crossed";
        };
    }

    private static String exactEventMeasuredComponentSumStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.measuredComponentSum()) {
            case NOT_COMPLETED -> "exact_event_measured_component_sum_not_completed";
            case WITHIN_DEADLINE -> "exact_event_measured_component_sum_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_measured_component_sum_deadline_crossed";
        };
    }

    private static String exactEventUnmeasuredResidualStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.unmeasuredResidual()) {
            case NOT_COMPLETED -> "exact_event_unmeasured_residual_not_completed";
            case WITHIN_DEADLINE -> "exact_event_unmeasured_residual_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_unmeasured_residual_deadline_crossed";
        };
    }

    private static String exactEventApplicationResidualStateLabel(EventCallSnapshot snapshot) {
        return switch (snapshot.applicationResidual()) {
            case NOT_COMPLETED -> "exact_event_application_residual_not_completed";
            case WITHIN_DEADLINE -> "exact_event_application_residual_within_deadline";
            case DEADLINE_CROSSED -> "exact_event_application_residual_deadline_crossed";
        };
    }

    private static String exactEventTransactionEnvelopeResidualStateLabel(
            EventCallSnapshot snapshot) {
        return switch (snapshot.transactionEnvelopeResidual()) {
            case NOT_COMPLETED -> "exact_event_transaction_envelope_residual_not_completed";
            case WITHIN_DEADLINE ->
                    "exact_event_transaction_envelope_residual_within_deadline";
            case DEADLINE_CROSSED ->
                    "exact_event_transaction_envelope_residual_deadline_crossed";
        };
    }

    private static String exactEventTransactionEnvelopeTargetMethodEntryPrefixStateLabel(
            EventCallSnapshot snapshot) {
        return switch (snapshot.transactionEnvelopeTargetMethodEntryPrefix()) {
            case NOT_COMPLETED ->
                    "exact_event_transaction_envelope_target_method_entry_prefix_not_completed";
            case WITHIN_DEADLINE ->
                    "exact_event_transaction_envelope_target_method_entry_prefix_within_deadline";
            case DEADLINE_CROSSED ->
                    "exact_event_transaction_envelope_target_method_entry_prefix_deadline_crossed";
        };
    }

    private static String exactEventTransactionEnvelopeTargetMethodReturnSuffixStateLabel(
            EventCallSnapshot snapshot) {
        return switch (snapshot.transactionEnvelopeTargetMethodReturnSuffix()) {
            case NOT_COMPLETED ->
                    "exact_event_transaction_envelope_target_method_return_suffix_not_completed";
            case WITHIN_DEADLINE ->
                    "exact_event_transaction_envelope_target_method_return_suffix_within_deadline";
            case DEADLINE_CROSSED ->
                    "exact_event_transaction_envelope_target_method_return_suffix_deadline_crossed";
        };
    }

    private static String exactEventGrpcDeadlineAtTransactionBeforeCommitLabel(
            EventGrpcDeadlineAtTransactionBeforeCommitState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded exact event gRPC deadline at transaction before-commit state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_at_transaction_before_commit_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_at_transaction_before_commit_unavailable";
            case ACTIVE ->
                    "exact_event_grpc_deadline_at_transaction_before_commit_active";
            case EXPIRED ->
                    "exact_event_grpc_deadline_at_transaction_before_commit_expired";
        };
    }

    private static String exactEventGrpcDeadlineAtTransactionBeforeCommitLabel(
            EventCallSnapshot snapshot) {
        return snapshot == null
                ? "exact_event_grpc_deadline_at_transaction_before_commit_not_recorded"
                : exactEventGrpcDeadlineAtTransactionBeforeCommitLabel(
                        snapshot.grpcDeadlineAtTransactionBeforeCommit());
    }

    private static String exactEventGrpcDeadlineAtTransactionBeforeCompletionLabel(
            EventGrpcDeadlineAtTransactionBeforeCompletionState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded exact event gRPC deadline at transaction before-completion state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_at_transaction_before_completion_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_at_transaction_before_completion_unavailable";
            case ACTIVE ->
                    "exact_event_grpc_deadline_at_transaction_before_completion_active";
            case EXPIRED ->
                    "exact_event_grpc_deadline_at_transaction_before_completion_expired";
        };
    }

    private static String exactEventGrpcDeadlineAtTransactionBeforeCompletionLabel(
            EventCallSnapshot snapshot) {
        return snapshot == null
                ? "exact_event_grpc_deadline_at_transaction_before_completion_not_recorded"
                : exactEventGrpcDeadlineAtTransactionBeforeCompletionLabel(
                        snapshot.grpcDeadlineAtTransactionBeforeCompletion());
    }

    private static String exactEventGrpcDeadlineAtTransactionFirstAfterCommitLabel(
            EventGrpcDeadlineAtTransactionFirstAfterCommitState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded exact event gRPC deadline at transaction first after-commit state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_at_transaction_first_after_commit_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_at_transaction_first_after_commit_unavailable";
            case ACTIVE ->
                    "exact_event_grpc_deadline_at_transaction_first_after_commit_active";
            case EXPIRED ->
                    "exact_event_grpc_deadline_at_transaction_first_after_commit_expired";
        };
    }

    private static String exactEventGrpcDeadlineAtTransactionFirstAfterCommitLabel(
            EventCallSnapshot snapshot) {
        return snapshot == null
                ? "exact_event_grpc_deadline_at_transaction_first_after_commit_not_recorded"
                : exactEventGrpcDeadlineAtTransactionFirstAfterCommitLabel(
                        snapshot.grpcDeadlineAtTransactionFirstAfterCommit());
    }

    private static String exactEventGrpcDeadlineAtTransactionAfterCommitLabel(
            EventGrpcDeadlineAtTransactionAfterCommitState state) {
        if (state == null) {
            throw new IllegalStateException(
                    "bounded exact event gRPC deadline at transaction after-commit state is invalid");
        }
        return switch (state) {
            case NOT_RECORDED ->
                    "exact_event_grpc_deadline_at_transaction_after_commit_not_recorded";
            case UNAVAILABLE ->
                    "exact_event_grpc_deadline_at_transaction_after_commit_unavailable";
            case ACTIVE ->
                    "exact_event_grpc_deadline_at_transaction_after_commit_active";
            case EXPIRED ->
                    "exact_event_grpc_deadline_at_transaction_after_commit_expired";
        };
    }

    private static String exactEventGrpcDeadlineAtTransactionAfterCommitLabel(
            EventCallSnapshot snapshot) {
        return snapshot == null
                ? "exact_event_grpc_deadline_at_transaction_after_commit_not_recorded"
                : exactEventGrpcDeadlineAtTransactionAfterCommitLabel(
                        snapshot.grpcDeadlineAtTransactionAfterCommit());
    }

    private static final class CorrelationFailureRecord {
        private final String correlationDigest;

        private CorrelationFailureRecord(String correlationDigest) {
            if (correlationDigest == null
                    || !CORRELATION_DIGEST.matcher(correlationDigest).matches()) {
                throw new IllegalArgumentException("correlation failure digest is invalid");
            }
            this.correlationDigest = correlationDigest;
        }

        private String correlationDigest() {
            return correlationDigest;
        }
    }

    private static CorrelationFailureRecord readAndDeleteCorrelationFailure(
            RunConfig config,
            boolean required) throws IOException {
        Path path = config.runDirectory().resolve(CORRELATION_FAILURE_FILE);
        boolean present = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (!present) {
            if (required) {
                throw new IllegalStateException("required correlation failure record is missing");
            }
            return null;
        }
        try {
            if (!required) {
                throw new IllegalStateException("unexpected correlation failure record");
            }
            if (Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("correlation failure record must not be a link");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > 1_024L) {
                throw new IllegalArgumentException(
                        "correlation failure record must be one bounded regular file");
            }
            JsonNode record = readPlainJson(path);
            requireExactFields(record, Set.of("schema_version", "status", "nonce", "profile", "correlation_digest"));
            requireIdentity(record, config.nonce(), config.profile());
            if (!"failed".equals(record.path("status").textValue())) {
                throw new IllegalArgumentException("correlation failure status mismatch");
            }
            String digest = record.path("correlation_digest").textValue();
            if (digest == null || !CORRELATION_DIGEST.matcher(digest).matches()) {
                throw new IllegalArgumentException("correlation failure digest mismatch");
            }
            rejectRawFields(record);
            return new CorrelationFailureRecord(digest);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void requireSuccessfulPython(
            int exit,
            RunConfig config,
            M5R3BoundaryDiagnostics diagnostics,
            ResolveTimingInterceptor timing) throws IOException {
        boolean eventDeadlineExit =
                exit == 46 || exit == 51 || exit == 52 || exit == 53 || exit == 54;
        CorrelationFailureRecord correlationFailure =
                readAndDeleteCorrelationFailure(config, eventDeadlineExit);
        if (exit == 0) {
            return;
        }
        String label = privatePythonFailureLabel(exit);
        if (exit == 45) {
            label += "; " + aggregateResolveFailureLabel(diagnostics);
            ResolveCallSnapshot resolveSnapshot = timing.firstCancelledResolveSnapshot();
            label += "; " + resolveCancellationPhaseLabel(resolveSnapshot);
            label += "; " + resolveCloseStartLabel(resolveSnapshot);
            label += "; " + resolveGrpcDeadlineAtUseCaseCompletionLabel(resolveSnapshot);
            label += "; " + exactResolveGrpcDeadlineAtUseCaseStartLabel(resolveSnapshot);
            label += "; " + exactResolveGrpcDeadlineAtResponseSentLabel(resolveSnapshot);
            label += "; " + exactResolveGrpcDeadlineAtOnCompletedReturnLabel(resolveSnapshot);
        }
        boolean responseHeaderBeforeDispatch = exit == 53 || exit == 54;
        int originalExit = exit;
        if (responseHeaderBeforeDispatch) {
            exit = 51;
        }
        if (exit == 46 || exit == 51 || exit == 52) {
            label += "; " + aggregateEventFailureLabel(diagnostics);
            EventDeadlineDiagnosticSnapshot eventSnapshot =
                    diagnostics.eventDeadlineDiagnosticSnapshot(timing);
            label += "; " + eventDeadlineLocalizationLabel(eventSnapshot);
            if (eventSnapshot.submitEventUseCaseDeadlineCrossed()) {
                label += "; " + eventAuthorityAdapterDurationLabel(eventSnapshot);
                if (!eventSnapshot.submitEventAuthorityAdapterCallDeadlineCrossed()) {
                    label += "; " + eventConsentPolicyDurationLabel(eventSnapshot);
                    label += "; " + eventSourceKeyDurationLabel(eventSnapshot);
                    label += "; " + eventSourceEnvelopeDurationLabel(eventSnapshot);
                    label += "; " + eventMeasuredComponentSumDurationLabel(eventSnapshot);
                    label += "; " + eventUnmeasuredResidualDurationLabel(eventSnapshot);
                    if (eventSnapshot.submitEventUnmeasuredResidualDeadlineCrossed()) {
                        label += "; " + eventApplicationResidualDurationLabel(eventSnapshot);
                        label += "; " + eventTransactionEnvelopeResidualDurationLabel(eventSnapshot);
                    }
                }
            }
            if (originalExit == 54
                    && eventSnapshot.eventServiceCallWithinDeadline()) {
                label += "; " + eventCloseStartReferenceBandLabel(eventSnapshot);
                label += "; " + aggregateEventCloseReturnLabel(eventSnapshot);
            }
            if (originalExit == 54) {
                EventCallSnapshot exactEventSnapshot = timing.exactEventSnapshot(
                        correlationFailure.correlationDigest());
                label += "; " + exactEventPhaseLabel(exactEventSnapshot);
                if (exactEventSnapshot != null) {
                    label += "; "
                            + exactEventGrpcDeadlineIngressHeadroomLabel(exactEventSnapshot);
                    label += "; " + exactEventCloseReturnLabel(exactEventSnapshot);
                    label += "; "
                            + exactEventCloseReturnReferenceBandLabel(exactEventSnapshot);
                    label += "; "
                            + exactEventGrpcDeadlineAtCloseReturnLabel(exactEventSnapshot);
                    label += "; "
                            + exactEventGrpcDeadlineHeadroomLabel(exactEventSnapshot);
                    label += "; "
                            + exactEventTransportTerminalLabel(exactEventSnapshot);
                    label += "; " + exactEventUseCaseStateLabel(exactEventSnapshot);
                    label += "; "
                            + exactEventGrpcDeadlineAtUseCaseCompletionLabel(exactEventSnapshot);
                    label += "; " + exactEventAuthorityAdapterStateLabel(exactEventSnapshot);
                    label += "; " + exactEventConsentPolicyStateLabel(exactEventSnapshot);
                    label += "; " + exactEventSourceKeyStateLabel(exactEventSnapshot);
                    label += "; " + exactEventSourceEnvelopeStateLabel(exactEventSnapshot);
                    label += "; " + exactEventMeasuredComponentSumStateLabel(exactEventSnapshot);
                    label += "; " + exactEventUnmeasuredResidualStateLabel(exactEventSnapshot);
                    label += "; " + exactEventApplicationResidualStateLabel(exactEventSnapshot);
                    label += "; "
                            + exactEventTransactionEnvelopeResidualStateLabel(exactEventSnapshot);
                    label += "; "
                            + exactEventTransactionEnvelopeTargetMethodEntryPrefixStateLabel(
                                    exactEventSnapshot);
                    label += "; "
                            + exactEventTransactionEnvelopeTargetMethodReturnSuffixStateLabel(
                                    exactEventSnapshot);
                    label += "; "
                            + exactEventGrpcDeadlineAtTransactionBeforeCommitLabel(
                                    exactEventSnapshot);
                    label += "; "
                            + exactEventGrpcDeadlineAtTransactionBeforeCompletionLabel(
                                    exactEventSnapshot);
                    label += "; "
                            + exactEventGrpcDeadlineAtTransactionFirstAfterCommitLabel(
                                    exactEventSnapshot);
                    label += "; "
                            + exactEventGrpcDeadlineAtTransactionAfterCommitLabel(
                                    exactEventSnapshot);
                }
            }
            if (eventSnapshot.eventServiceCallIncomplete()
                    || (exit == 51 && eventSnapshot.eventServiceCallWithinDeadline())) {
                EventCallSnapshot eventCallSnapshot = timing.firstCancelledEventSnapshot();
                label += "; " + eventCancellationPhaseLabel(eventCallSnapshot);
                if (exit == 51 && eventSnapshot.eventServiceCallWithinDeadline()) {
                    label += "; " + eventCloseReturnLabel(eventCallSnapshot);
                }
            }
        }
        if (responseHeaderBeforeDispatch) {
            exit = originalExit;
        }
        throw new IllegalStateException(
                "private Python client exited with exit status " + exit + "; "
                        + label);
    }

    private static boolean pythonAlive(Process process) {
        if (!process.isAlive()) {
            throw new IllegalStateException(
                    "private Python client exited before protocol readiness with exit status "
                            + process.exitValue() + "; "
                            + privatePythonFailureLabel(process.exitValue()));
        }
        return true;
    }

    private static JsonNode waitForRecordBeforeClientDone(RunConfig config, Process process) throws Exception {
        Path active = config.runDirectory().resolve(WORKER_ACTIVE_FILE);
        Path done = config.runDirectory().resolve(CLIENT_DONE_FILE);
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(done, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("full client completed without prior worker activity");
            }
            if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
                return readPlainJson(active);
            }
            pythonAlive(process);
            Thread.sleep(20);
        }
        throw new IllegalStateException("worker activity record is missing");
    }

    private static JsonNode waitForRecord(Path path, Duration timeout, CheckedBoolean health) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return readPlainJson(path);
            }
            health.get();
            Thread.sleep(20);
        }
        throw new IllegalStateException("required protocol record is missing");
    }

    private static JsonNode validateClientDone(JsonNode record, RunConfig config) {
        requireExactFields(record, Set.of("schema_version", "status", "nonce", "profile", "result"));
        requireIdentity(record, config.nonce(), config.profile());
        if (!"passed".equals(record.path("status").textValue())) {
            throw new IllegalArgumentException("client done status mismatch");
        }
        JsonNode result = record.path("result");
        requireExactFields(result, Set.of(
                "counts", "python_loopback_wait_samples_ns", "test_pipeline_answer_samples_ns"));
        if (result.has("critical_counters")) {
            throw new IllegalArgumentException("client result has unsupported fields");
        }
        rejectRawFields(record);
        return result;
    }

    private static String validateWorkerReady(JsonNode record, RunConfig config) {
        requireExactFields(record, Set.of("schema_version", "status", "nonce", "profile", "endpoint"));
        requireIdentity(record, config.nonce(), config.profile());
        if (!"ready".equals(record.path("status").textValue())) {
            throw new IllegalArgumentException("worker ready status mismatch");
        }
        return requireLoopback(record.path("endpoint").textValue());
    }

    private static void validateWorkerActive(JsonNode record, String nonce, String profile) {
        requireExactFields(record, Set.of("schema_version", "status", "nonce", "profile"));
        requireIdentity(record, nonce, profile);
        if (!"full".equals(profile) || !"active".equals(record.path("status").textValue())) {
            throw new IllegalArgumentException("worker active record mismatch");
        }
    }

    private static void requireIdentity(JsonNode record, String nonce, String profile) {
        if (!PROTOCOL_VERSION.equals(record.path("schema_version").textValue())
                || !nonce.equals(record.path("nonce").textValue())
                || !profile.equals(record.path("profile").textValue())) {
            throw new IllegalArgumentException("protocol identity mismatch");
        }
    }

    private static void writeReady(RunConfig config, String endpoint) throws IOException {
        revalidateRunDirectory(config, true);
        ObjectNode ready = JSON.createObjectNode()
                .put("schema_version", PROTOCOL_VERSION)
                .put("status", "ready")
                .put("nonce", config.nonce())
                .put("profile", config.profile())
                .put("endpoint", endpoint)
                .put("sessions", 50)
                .put("read_rate", 20)
                .put("event_rate", 50)
                .put("duration_seconds", config.durationSeconds());
        atomicWriteJson(config.runDirectory().resolve(READY_FILE), ready);
    }

    private static void atomicWriteJson(Path target, JsonNode value) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        requirePlainDirectory(parent);
        Path temporary = Files.createTempFile(parent, ".m5-", ".json");
        boolean moved = false;
        try {
            Files.writeString(temporary, JSON.writeValueAsString(value), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static JsonNode readPlainJson(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("protocol record must not be a link");
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > 4_000_000L) {
            throw new IllegalArgumentException("protocol record must be one bounded regular file");
        }
        return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void rejectRawFields(JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException("aggregate protocol value is required");
        }
        if (value.isObject()) {
            value.fieldNames().forEachRemaining(field -> {
                if (RAW_FIELDS.contains(field.toLowerCase())) {
                    throw new IllegalArgumentException("aggregate contains unredacted field");
                }
                rejectRawFields(value.path(field));
            });
        } else if (value.isArray()) {
            value.forEach(M5LoadHarnessTest::rejectRawFields);
        } else if (!value.isValueNode()) {
            throw new IllegalArgumentException("aggregate protocol value is invalid");
        }
    }

    private static void requireExactFields(JsonNode record, Set<String> expected) {
        if (!record.isObject() || !fieldNames(record).equals(expected)) {
            throw new IllegalArgumentException("protocol record fields mismatch");
        }
    }

    private static Set<String> fieldNames(JsonNode record) {
        var names = new java.util.HashSet<String>();
        record.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static ObjectNode requireObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return object;
    }

    private static String requireLoopback(String endpoint) {
        return HostAndPort.parse(endpoint).toString();
    }

    private static void requireClientCompletionOrder(boolean activityObserved, boolean clientCompleted) {
        if (clientCompleted && !activityObserved) {
            throw new IllegalStateException("full client completion requires prior worker activity");
        }
    }

    private static void requireStopped(Process process) {
        if (process.isAlive() || process.descendants().anyMatch(ProcessHandle::isAlive)) {
            throw new IllegalStateException("private Python child leaked");
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test executor leaked");
        }
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("run directory must be a plain directory");
        }
    }

    private static Path createSelfOwnedRunDirectory(Path runRoot) throws IOException {
        Files.createDirectories(runRoot);
        requirePlainDirectory(runRoot);
        Path runDirectory = Files.createTempDirectory(runRoot, "run-self-")
                .toAbsolutePath()
                .normalize();
        validateFreshRunDirectory(runRoot, runDirectory);
        return runDirectory;
    }

    private static void validateFreshRunDirectory(Path runRoot, Path runDirectory) throws IOException {
        validateRunDirectory(runRoot, runDirectory, true);
    }

    private static void revalidateRunDirectory(RunConfig config, boolean requireEmpty) throws IOException {
        validateRunDirectory(
                config.repositoryRoot().resolve("tmp/memory-system/m5"),
                config.runDirectory(),
                requireEmpty);
    }

    private static void validateRunDirectory(
            Path runRoot,
            Path runDirectory,
            boolean requireEmpty) throws IOException {
        validateRunDirectory(runRoot, runDirectory, requireEmpty, Files::isSymbolicLink);
    }

    private static void validateRunDirectory(
            Path runRoot,
            Path runDirectory,
            boolean requireEmpty,
            Predicate<Path> isSymbolicLink) throws IOException {
        runRoot = runRoot.toAbsolutePath().normalize();
        runDirectory = runDirectory.toAbsolutePath().normalize();
        requirePlainDirectory(runRoot);
        if (isSymbolicLink.test(runRoot)
                || isSymbolicLink.test(runDirectory)
                || runDirectory.getParent() == null
                || !runDirectory.getParent().equals(runRoot)
                || !RUN_DIRECTORY_NAME.matcher(runDirectory.getFileName().toString()).matches()) {
            throw new IllegalArgumentException("run directory must be one direct run child");
        }
        requirePlainDirectory(runDirectory);
        if (requireEmpty) {
            try (var children = Files.list(runDirectory)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalArgumentException("run directory must be empty before protocol writes");
                }
            }
        }
    }

    private static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("owned run directory became a link");
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("owned run contains a link");
                }
                Files.delete(path);
            }
        }
    }

    private static ServerInterceptor instrumentedServerInterceptor(
            String stage,
            ServerInterceptor delegate,
            M5R3BoundaryDiagnostics diagnostics) {
        Objects.requireNonNull(delegate, "delegate");
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                return diagnostics.measure(
                        stage,
                        () -> delegate.interceptCall(call, headers, next));
            }
        };
    }

    private static <T> T measureResolvePhase(
            ResolveCallPhase active,
            Supplier<T> action) {
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(action, "action");
        ResolveCallPhaseState state = RESOLVE_CALL_PHASE_STATE.get();
        if (state == null) {
            return action.get();
        }
        state.advance(ResolveCallPhase.USE_CASE_ACTIVE, active);
        try {
            return action.get();
        } finally {
            state.advance(active, ResolveCallPhase.USE_CASE_ACTIVE);
        }
    }

    private static RetrievalChannel instrumentedRetrievalChannel(
            RetrievalChannel delegate,
            ResolveCallPhase phase,
            M5R3BoundaryDiagnostics diagnostics) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(phase, "phase");
        return new RetrievalChannel() {
            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
                return measureResolvePhase(phase,
                        () -> diagnostics.measure(
                                RESOLVE_AUTHORITY_ADAPTER,
                                () -> delegate.retrieve(query)));
            }
        };
    }

    private SubmitMemoryEventsUseCase instrumentedSubmitEvents(
            M5R3BoundaryDiagnostics diagnostics) {
        ConsentQuery.PolicyReader instrumentedEventPolicyReader = subjectHash ->
                diagnostics.measureSubmitEventConsentPolicy(
                        () -> consentRepository.findForSubject(subjectHash));
        InteractionPayloadKeyEnvelopeStore instrumentedSourceEnvelopes =
                new InteractionPayloadKeyEnvelopeStore() {
                    @Override
                    public java.util.Optional<Envelope> load(
                            String subjectHash, UUID eventId, String schemaVersion) {
                        return sourceEnvelopes.load(subjectHash, eventId, schemaVersion);
                    }

                    @Override
                    public void storeIfAbsent(
                            String subjectHash,
                            UUID eventId,
                            String schemaVersion,
                            Envelope envelope) {
                        diagnostics.measureSubmitEventSourceEnvelope(
                                () -> sourceEnvelopes.storeIfAbsent(
                                        subjectHash, eventId, schemaVersion, envelope));
                    }

                    @Override
                    public boolean eraseForRetention(
                            String subjectHash,
                            UUID eventId,
                            String schemaVersion,
                            String policyVersion,
                            Instant dueAt,
                            Instant executedAt) {
                        return sourceEnvelopes.eraseForRetention(
                                subjectHash, eventId, schemaVersion, policyVersion, dueAt, executedAt);
                    }

                    @Override
                    public int eraseAllForSubject(String subjectHash) {
                        return sourceEnvelopes.eraseAllForSubject(subjectHash);
                    }
                };
        DataKeyProvider delegateDataKeyProvider = dataKeyProvider;
        DataKeyProvider instrumentedDataKeyProvider = new DataKeyProvider() {
            @Override
            public KeyMaterial activeKeyFor(String subjectHash, KeyPurpose purpose) {
                return delegateDataKeyProvider.activeKeyFor(subjectHash, purpose);
            }

            @Override
            public KeyMaterial keyFor(String subjectHash, KeyPurpose purpose, String keyReference) {
                return delegateDataKeyProvider.keyFor(subjectHash, purpose, keyReference);
            }

            @Override
            public SourceKeyMaterial activeSourceKeyFor(
                    String subjectHash, UUID eventId, String schemaVersion) {
                return diagnostics.measureSubmitEventSourceKey(
                        () -> delegateDataKeyProvider.activeSourceKeyFor(
                                subjectHash, eventId, schemaVersion));
            }

            @Override
            public KeyMaterial sourceKeyFor(
                    String subjectHash, UUID eventId, String schemaVersion, String keyReference) {
                return delegateDataKeyProvider.sourceKeyFor(
                        subjectHash, eventId, schemaVersion, keyReference);
            }

            @Override
            public void storeSourceEnvelopeIfAbsent(
                    String subjectHash,
                    UUID eventId,
                    String schemaVersion,
                    InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
                delegateDataKeyProvider.storeSourceEnvelopeIfAbsent(
                        subjectHash, eventId, schemaVersion, envelope);
            }
        };
        PayloadProtector instrumentedPayloadProtector =
                new PayloadProtector(instrumentedDataKeyProvider);
        SubmitMemoryEventsUseCase.InteractionEventRepository instrumentedEvents =
                (identity, event) -> measureSubmitAuthorityAdapter(
                        diagnostics,
                        () -> interactionEvents.insertIfAbsent(identity, event));
        SubmitMemoryEventsUseCase.TransactionalOutboxRepository instrumentedOutbox =
                event -> measureSubmitAuthorityAdapter(diagnostics, () -> {
                    transactionalOutbox.insert(event);
                    return Boolean.TRUE;
                });
        var target = new InstrumentedSubmitMemoryEventsUseCase(
                instrumentedEvents,
                instrumentedOutbox,
                instrumentedEventPolicyReader,
                instrumentedPayloadProtector,
                instrumentedSourceEnvelopes,
                diagnostics);
        Object initialized = beanFactory.initializeBean(target, "m5LoadInstrumentedSubmitEvents");
        if (!(initialized instanceof SubmitMemoryEventsUseCase proxied)
                || !AopUtils.isAopProxy(proxied)) {
            throw new IllegalStateException("test-local submit use case transaction proxy is unavailable");
        }
        return proxied;
    }

    private static final class ExactEventBeforeCommitDeadlineSynchronization
            implements TransactionSynchronization, Ordered {
        private final EventCallPhaseState exactState;

        private ExactEventBeforeCommitDeadlineSynchronization(
                EventCallPhaseState exactState) {
            this.exactState = Objects.requireNonNull(
                    exactState, "exact event call phase state");
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            exactState.recordGrpcDeadlineAtTransactionBeforeCommit(
                    Context.current().getDeadline());
        }

        @Override
        public void beforeCompletion() {
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            if (synchronizations.get(synchronizations.size() - 1) != this) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline before-completion synchronization endpoint is invalid");
            }
            exactState.recordGrpcDeadlineAtTransactionBeforeCompletion(
                    Context.current().getDeadline());
        }

        @Override
        public void afterCommit() {
            exactState.recordGrpcDeadlineAtTransactionAfterCommit(
                    Context.current().getDeadline());
        }
    }

    private static final class ExactEventFirstAfterCommitDeadlineSynchronization
            implements TransactionSynchronization, Ordered {
        private final EventCallPhaseState exactState;

        private ExactEventFirstAfterCommitDeadlineSynchronization(
                EventCallPhaseState exactState) {
            this.exactState = Objects.requireNonNull(
                    exactState, "exact event call phase state");
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void afterCommit() {
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            if (synchronizations.get(0) != this) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline first after-commit synchronization endpoint is invalid");
            }
            exactState.recordGrpcDeadlineAtTransactionFirstAfterCommit(
                    Context.current().getDeadline());
        }
    }

    static class InstrumentedSubmitMemoryEventsUseCase
            extends SubmitMemoryEventsUseCase {
        private final M5R3BoundaryDiagnostics diagnostics;

        InstrumentedSubmitMemoryEventsUseCase(
                InteractionEventRepository events,
                TransactionalOutboxRepository outbox,
                ConsentQuery.PolicyReader policyReader,
                PayloadProtector payloadProtector,
                InteractionPayloadKeyEnvelopeStore sourceEnvelopes,
                M5R3BoundaryDiagnostics diagnostics) {
            super(events, outbox, policyReader, payloadProtector, sourceEnvelopes);
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }

        @Override
        @Transactional
        public List<SubmitMemoryEventsUseCase.EventReceipt> submit(
                LearnerIdentity identity,
                List<SubmitMemoryEventsUseCase.InteractionEventCommand> commands) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException(
                        "test-local submit transaction synchronization is unavailable");
            }
            EventCallPhaseState exactState = requireEventCallPhaseState();
            TransactionSynchronizationManager.registerSynchronization(
                    new ExactEventBeforeCommitDeadlineSynchronization(exactState));
            TransactionSynchronizationManager.registerSynchronization(
                    new ExactEventFirstAfterCommitDeadlineSynchronization(exactState));
            return diagnostics.measureSubmitEventApplicationBody(
                    () -> super.submit(identity, commands));
        }
    }

    private static <T> T measureSubmitAuthorityAdapter(
            M5R3BoundaryDiagnostics diagnostics,
            Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("submit authority adapter is outside its transaction");
        }
        return diagnostics.measure(SUBMIT_EVENT_TRANSACTION_AUTHORITY_ADAPTER, action);
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static MemoryProperties loadProperties() {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (MemoryType type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(
                    1, java.math.BigDecimal.valueOf(0.70d), Duration.ofDays(30)));
        }
        return new MemoryProperties(
                "m5-load-v1",
                rules,
                new MemoryProperties.RetrievalWeights(
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ONE),
                60,
                new MemoryProperties.Budgets(8, 900),
                new MemoryProperties.WorkerSettings(
                        "v1",
                        Duration.ofSeconds(30),
                        4_096,
                        16_384,
                        1,
                        Set.of(MemoryType.PREFERENCE)),
                new MemoryProperties.SourceMaterialSettings(Duration.ofHours(24)));
    }

    private static ResolveMemoryContextUseCase.MemoryQuery loadMemoryQuery(
            String queryText,
            String taskType,
            int budget) {
        return new ResolveMemoryContextUseCase.MemoryQuery(
                queryText,
                taskType,
                Set.of(MemoryType.PREFERENCE),
                Set.of(),
                Math.max(1, budget),
                Math.max(1, budget * 128),
                null,
                Duration.ofMillis(120),
                false);
    }

    private record HostAndPort(String host, int port) {
        static HostAndPort parse(String value) {
            if (value == null || !value.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) {
                throw new IllegalArgumentException("endpoint must be numeric IPv4 loopback");
            }
            int separator = value.lastIndexOf(':');
            int port = Integer.parseInt(value.substring(separator + 1));
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("loopback port is invalid");
            }
            return new HostAndPort("127.0.0.1", port);
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private record RunConfig(
            Path repositoryRoot,
            Path runDirectory,
            Path python,
            Path script,
            String profile,
            int durationSeconds,
            String nonce,
            boolean selfOwned) {

        static RunConfig fromSystemProperties() throws IOException {
            Path repositoryRoot = findRepositoryRoot();
            Path runRoot = repositoryRoot.resolve("tmp/memory-system/m5");
            String runProperty = System.getProperty("memory.load.runDir");
            boolean selfOwned = runProperty == null || runProperty.isBlank();
            Path runDirectory;
            if (selfOwned) {
                runDirectory = createSelfOwnedRunDirectory(runRoot);
            } else {
                runDirectory = Path.of(runProperty).toAbsolutePath().normalize();
                validateFreshRunDirectory(runRoot, runDirectory);
            }
            String pythonProperty = System.getProperty("memory.load.python");
            String scriptProperty = System.getProperty("memory.load.script");
            Path python = (pythonProperty == null || pythonProperty.isBlank()
                    ? repositoryRoot.resolve(".venv/Scripts/python.exe")
                    : Path.of(pythonProperty)).toAbsolutePath().normalize();
            Path script = (scriptProperty == null || scriptProperty.isBlank()
                    ? repositoryRoot.resolve("scripts/run_memory_load.py")
                    : Path.of(scriptProperty)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(python, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(python) || Files.isSymbolicLink(script)) {
                throw new IllegalArgumentException("private Python executable and script must be plain files");
            }
            String profile = System.getProperty("memory.load.profile", "full");
            if (!Set.of("java-postgres-only", "full").contains(profile)) {
                throw new IllegalArgumentException("memory.load.profile is invalid");
            }
            int duration = Integer.parseInt(System.getProperty("memory.load.durationSeconds", "2"));
            if (duration < 1 || duration > 600) {
                throw new IllegalArgumentException("memory.load.durationSeconds is invalid");
            }
            String nonce = System.getProperty(
                    "memory.load.nonce",
                    UUID.randomUUID().toString().replace("-", ""));
            if (!NONCE.matcher(nonce).matches()) {
                throw new IllegalArgumentException("memory.load.nonce is invalid");
            }
            return new RunConfig(
                    repositoryRoot, runDirectory, python, script, profile, duration, nonce, selfOwned);
        }

        private static Path findRepositoryRoot() {
            Path current = Path.of("").toAbsolutePath().normalize();
            while (current != null) {
                if (Files.isRegularFile(current.resolve("scripts/run_memory_load.py"))
                        && Files.isDirectory(current.resolve("services/memory-service"))) {
                    return current;
                }
                current = current.getParent();
            }
            throw new IllegalStateException("repository root is unavailable");
        }

        RunConfig withProfile(String replacement) {
            if (!Set.of("java-postgres-only", "full").contains(replacement)) {
                throw new IllegalArgumentException("replacement profile is invalid");
            }
            return new RunConfig(
                    repositoryRoot,
                    runDirectory,
                    python,
                    script,
                    replacement,
                    durationSeconds,
                    nonce,
                    false);
        }

        int scheduledEventAttempts() {
            return Math.multiplyExact(50, durationSeconds);
        }
    }

    private enum ResolveCallPhase {
        SERVER_ENTERED,
        SERVICE_METHOD_ENTERED,
        USE_CASE_ACTIVE,
        CONSENT_ACTIVE,
        STRUCTURED_ACTIVE,
        KEYWORD_ACTIVE,
        RECENT_EPISODE_ACTIVE,
        USE_CASE_COMPLETED,
        ON_COMPLETED_ENTERED,
        SERVER_CLOSED,
        ON_COMPLETED_RETURNED
    }

    private enum ResolveCloseStartState {
        NOT_STARTED,
        WITHIN_DEADLINE,
        DEADLINE_CROSSED
    }

    private enum ResolveGrpcDeadlineAtUseCaseCompletionState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum ResolveGrpcDeadlineAtUseCaseStartState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum ResolveGrpcDeadlineAtResponseSentState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum ResolveGrpcDeadlineAtOnCompletedReturnState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private record ResolveCallSnapshot(
            ResolveCallPhase phase,
            ResolveCloseStartState closeStart,
            ResolveGrpcDeadlineAtUseCaseCompletionState grpcDeadlineAtUseCaseCompletion,
            ResolveGrpcDeadlineAtUseCaseStartState grpcDeadlineAtUseCaseStart,
            ResolveGrpcDeadlineAtResponseSentState grpcDeadlineAtResponseSent,
            ResolveGrpcDeadlineAtOnCompletedReturnState grpcDeadlineAtOnCompletedReturn) {
        private ResolveCallSnapshot {
            if (phase == null
                    || closeStart == null
                    || grpcDeadlineAtUseCaseCompletion == null
                    || grpcDeadlineAtUseCaseStart == null
                    || grpcDeadlineAtResponseSent == null
                    || grpcDeadlineAtOnCompletedReturn == null) {
                throw new IllegalStateException("bounded resolve call snapshot is invalid");
            }
        }
    }

    private static final class ResolveCallPhaseState {
        private ResolveCallPhase phase = ResolveCallPhase.SERVER_ENTERED;
        private ResolveCloseStartState closeStart = ResolveCloseStartState.NOT_STARTED;
        private ResolveGrpcDeadlineAtUseCaseCompletionState grpcDeadlineAtUseCaseCompletion =
                ResolveGrpcDeadlineAtUseCaseCompletionState.NOT_RECORDED;
        private ResolveGrpcDeadlineAtUseCaseStartState grpcDeadlineAtUseCaseStart =
                ResolveGrpcDeadlineAtUseCaseStartState.NOT_RECORDED;
        private ResolveGrpcDeadlineAtResponseSentState grpcDeadlineAtResponseSent =
                ResolveGrpcDeadlineAtResponseSentState.NOT_RECORDED;
        private ResolveGrpcDeadlineAtOnCompletedReturnState grpcDeadlineAtOnCompletedReturn =
                ResolveGrpcDeadlineAtOnCompletedReturnState.NOT_RECORDED;

        private synchronized void advance(ResolveCallPhase expected, ResolveCallPhase next) {
            boolean valid = expected != null && next != null && switch (expected) {
                case SERVER_ENTERED -> next == ResolveCallPhase.SERVICE_METHOD_ENTERED;
                case SERVICE_METHOD_ENTERED -> next == ResolveCallPhase.USE_CASE_ACTIVE;
                case USE_CASE_ACTIVE -> next == ResolveCallPhase.CONSENT_ACTIVE
                        || next == ResolveCallPhase.STRUCTURED_ACTIVE
                        || next == ResolveCallPhase.KEYWORD_ACTIVE
                        || next == ResolveCallPhase.RECENT_EPISODE_ACTIVE
                        || next == ResolveCallPhase.USE_CASE_COMPLETED;
                case CONSENT_ACTIVE -> next == ResolveCallPhase.USE_CASE_ACTIVE;
                case STRUCTURED_ACTIVE -> next == ResolveCallPhase.USE_CASE_ACTIVE;
                case KEYWORD_ACTIVE -> next == ResolveCallPhase.USE_CASE_ACTIVE;
                case RECENT_EPISODE_ACTIVE -> next == ResolveCallPhase.USE_CASE_ACTIVE;
                case USE_CASE_COMPLETED -> next == ResolveCallPhase.ON_COMPLETED_ENTERED;
                case ON_COMPLETED_ENTERED -> next == ResolveCallPhase.SERVER_CLOSED;
                case SERVER_CLOSED -> next == ResolveCallPhase.ON_COMPLETED_RETURNED;
                case ON_COMPLETED_RETURNED -> false;
            };
            if (!valid || phase != expected) {
                throw new IllegalStateException(
                        "bounded resolve call phase transition is invalid");
            }
            phase = next;
        }

        private synchronized void recordGrpcDeadlineAtUseCaseStart(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtUseCaseStart
                    != ResolveGrpcDeadlineAtUseCaseStartState.NOT_RECORDED
                    || phase != ResolveCallPhase.USE_CASE_ACTIVE
                    || grpcDeadlineAtUseCaseCompletion
                            != ResolveGrpcDeadlineAtUseCaseCompletionState.NOT_RECORDED) {
                throw new IllegalStateException(
                        "bounded resolve gRPC deadline at use-case start state is invalid");
            }
            grpcDeadlineAtUseCaseStart = grpcDeadline == null
                    ? ResolveGrpcDeadlineAtUseCaseStartState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? ResolveGrpcDeadlineAtUseCaseStartState.EXPIRED
                    : ResolveGrpcDeadlineAtUseCaseStartState.ACTIVE;
        }

        private synchronized void recordGrpcDeadlineAtUseCaseCompletion(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtUseCaseCompletion
                    != ResolveGrpcDeadlineAtUseCaseCompletionState.NOT_RECORDED
                    || phase != ResolveCallPhase.USE_CASE_ACTIVE
                    || grpcDeadlineAtUseCaseStart
                            == ResolveGrpcDeadlineAtUseCaseStartState.NOT_RECORDED) {
                throw new IllegalStateException(
                        "bounded resolve gRPC deadline at use-case completion state is invalid");
            }
            grpcDeadlineAtUseCaseCompletion = grpcDeadline == null
                    ? ResolveGrpcDeadlineAtUseCaseCompletionState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? ResolveGrpcDeadlineAtUseCaseCompletionState.EXPIRED
                    : ResolveGrpcDeadlineAtUseCaseCompletionState.ACTIVE;
        }

        private synchronized void recordGrpcDeadlineAtResponseSent(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtResponseSent
                    != ResolveGrpcDeadlineAtResponseSentState.NOT_RECORDED
                    || phase != ResolveCallPhase.USE_CASE_COMPLETED
                    || grpcDeadlineAtUseCaseCompletion
                            == ResolveGrpcDeadlineAtUseCaseCompletionState.NOT_RECORDED) {
                throw new IllegalStateException(
                        "bounded resolve gRPC deadline at response sent state is invalid");
            }
            grpcDeadlineAtResponseSent = grpcDeadline == null
                    ? ResolveGrpcDeadlineAtResponseSentState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? ResolveGrpcDeadlineAtResponseSentState.EXPIRED
                    : ResolveGrpcDeadlineAtResponseSentState.ACTIVE;
        }

        private synchronized void recordGrpcDeadlineAtOnCompletedReturn(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtOnCompletedReturn
                    != ResolveGrpcDeadlineAtOnCompletedReturnState.NOT_RECORDED
                    || phase != ResolveCallPhase.ON_COMPLETED_RETURNED
                    || grpcDeadlineAtResponseSent
                            == ResolveGrpcDeadlineAtResponseSentState.NOT_RECORDED) {
                throw new IllegalStateException(
                        "bounded resolve gRPC deadline at on completed return state is invalid");
            }
            grpcDeadlineAtOnCompletedReturn = grpcDeadline == null
                    ? ResolveGrpcDeadlineAtOnCompletedReturnState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? ResolveGrpcDeadlineAtOnCompletedReturnState.EXPIRED
                    : ResolveGrpcDeadlineAtOnCompletedReturnState.ACTIVE;
        }

        private synchronized void recordCloseStart(long elapsed) {
            if (elapsed < 0L || closeStart != ResolveCloseStartState.NOT_STARTED) {
                throw new IllegalStateException("bounded resolve close-start state is invalid");
            }
            closeStart = elapsed > 150_000_000L
                    ? ResolveCloseStartState.DEADLINE_CROSSED
                    : ResolveCloseStartState.WITHIN_DEADLINE;
        }

        private synchronized ResolveCallSnapshot snapshot() {
            return new ResolveCallSnapshot(
                    phase,
                    closeStart,
                    grpcDeadlineAtUseCaseCompletion,
                    grpcDeadlineAtUseCaseStart,
                    grpcDeadlineAtResponseSent,
                    grpcDeadlineAtOnCompletedReturn);
        }

        private synchronized ResolveCallPhase current() {
            if (phase == null) {
                throw new IllegalStateException(
                        "bounded resolve call phase state is invalid");
            }
            return phase;
        }
    }

    private static final Context.Key<ResolveCallPhaseState> RESOLVE_CALL_PHASE_STATE =
            Context.key("m5-resolve-call-phase");

    private enum EventCallPhase {
        SERVER_ENTERED,
        SERVICE_METHOD_ENTERED,
        USE_CASE_ENTERED,
        USE_CASE_COMPLETED,
        ON_COMPLETED_ENTERED,
        SERVER_CLOSED,
        ON_COMPLETED_RETURNED
    }

    private enum EventCloseReturnState {
        NOT_RETURNED,
        WITHIN_DEADLINE,
        DEADLINE_CROSSED
    }

    private enum EventCloseReturnReferenceBand {
        NOT_RETURNED,
        LOWER_REFERENCE_BAND,
        UPPER_REFERENCE_BAND,
        DEADLINE_CROSSED
    }

    private enum EventGrpcDeadlineAtCloseReturnState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum EventGrpcDeadlineHeadroomState {
        NOT_RECORDED,
        UNAVAILABLE,
        EXPIRED,
        ACTIVE_WITHIN_30_MS_REFERENCE_BAND,
        ACTIVE_ABOVE_30_MS_REFERENCE_BAND
    }

    private enum EventGrpcDeadlineIngressHeadroomState {
        NOT_RECORDED,
        UNAVAILABLE,
        EXPIRED,
        WITHIN_120_MS_REFERENCE_BAND,
        ABOVE_120_MS_REFERENCE_BAND
    }

    private enum EventTransportTerminalState {
        NOT_RECORDED,
        OK,
        CANCELLED,
        OTHER
    }

    private enum EventGrpcDeadlineAtUseCaseCompletionState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum EventGrpcDeadlineAtTransactionBeforeCommitState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum EventGrpcDeadlineAtTransactionBeforeCompletionState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum EventGrpcDeadlineAtTransactionFirstAfterCommitState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum EventGrpcDeadlineAtTransactionAfterCommitState {
        NOT_RECORDED,
        UNAVAILABLE,
        ACTIVE,
        EXPIRED
    }

    private enum EventComponentState {
        NOT_COMPLETED,
        WITHIN_DEADLINE,
        DEADLINE_CROSSED
    }

    private record EventCallSnapshot(
            EventCallPhase phase,
            EventCloseReturnState closeReturn,
            EventCloseReturnReferenceBand closeReturnReferenceBand,
            EventGrpcDeadlineAtCloseReturnState grpcDeadlineAtCloseReturn,
            EventGrpcDeadlineHeadroomState grpcDeadlineHeadroom,
            EventGrpcDeadlineIngressHeadroomState grpcDeadlineIngressHeadroom,
            EventTransportTerminalState transportTerminal,
            EventGrpcDeadlineAtUseCaseCompletionState grpcDeadlineAtUseCaseCompletion,
            EventComponentState useCase,
            EventComponentState authorityAdapterCalls,
            EventComponentState consentPolicy,
            EventComponentState sourceKey,
            EventComponentState sourceEnvelope,
            EventComponentState measuredComponentSum,
            EventComponentState unmeasuredResidual,
            EventComponentState applicationResidual,
            EventComponentState transactionEnvelopeResidual,
            EventComponentState transactionEnvelopeTargetMethodEntryPrefix,
            EventComponentState transactionEnvelopeTargetMethodReturnSuffix,
            EventGrpcDeadlineAtTransactionBeforeCommitState
                    grpcDeadlineAtTransactionBeforeCommit,
            EventGrpcDeadlineAtTransactionBeforeCompletionState
                    grpcDeadlineAtTransactionBeforeCompletion,
            EventGrpcDeadlineAtTransactionFirstAfterCommitState
                    grpcDeadlineAtTransactionFirstAfterCommit,
            EventGrpcDeadlineAtTransactionAfterCommitState
                    grpcDeadlineAtTransactionAfterCommit) {
        private EventCallSnapshot {
            if (phase == null
                    || closeReturn == null
                    || closeReturnReferenceBand == null
                    || grpcDeadlineAtCloseReturn == null
                    || grpcDeadlineHeadroom == null
                    || grpcDeadlineIngressHeadroom == null
                    || transportTerminal == null
                    || grpcDeadlineAtUseCaseCompletion == null
                    || useCase == null
                    || authorityAdapterCalls == null
                    || consentPolicy == null
                    || sourceKey == null
                    || sourceEnvelope == null
                    || measuredComponentSum == null
                    || unmeasuredResidual == null
                    || applicationResidual == null
                    || transactionEnvelopeResidual == null
                    || transactionEnvelopeTargetMethodEntryPrefix == null
                    || transactionEnvelopeTargetMethodReturnSuffix == null
                    || grpcDeadlineAtTransactionBeforeCommit == null
                    || grpcDeadlineAtTransactionBeforeCompletion == null
                    || grpcDeadlineAtTransactionFirstAfterCommit == null
                    || grpcDeadlineAtTransactionAfterCommit == null) {
                throw new IllegalStateException("bounded event call snapshot is invalid");
            }
        }
    }

    private static final class EventCallPhaseState {
        private EventCallPhase phase = EventCallPhase.SERVER_ENTERED;
        private EventCloseReturnState closeReturn = EventCloseReturnState.NOT_RETURNED;
        private EventCloseReturnReferenceBand closeReturnReferenceBand =
                EventCloseReturnReferenceBand.NOT_RETURNED;
        private EventGrpcDeadlineAtCloseReturnState grpcDeadlineAtCloseReturn =
                EventGrpcDeadlineAtCloseReturnState.NOT_RECORDED;
        private EventGrpcDeadlineHeadroomState grpcDeadlineHeadroom =
                EventGrpcDeadlineHeadroomState.NOT_RECORDED;
        private EventGrpcDeadlineIngressHeadroomState grpcDeadlineIngressHeadroom =
                EventGrpcDeadlineIngressHeadroomState.NOT_RECORDED;
        private EventTransportTerminalState transportTerminal =
                EventTransportTerminalState.NOT_RECORDED;
        private EventGrpcDeadlineAtUseCaseCompletionState grpcDeadlineAtUseCaseCompletion =
                EventGrpcDeadlineAtUseCaseCompletionState.NOT_RECORDED;
        private EventGrpcDeadlineAtTransactionBeforeCommitState
                grpcDeadlineAtTransactionBeforeCommit =
                EventGrpcDeadlineAtTransactionBeforeCommitState.NOT_RECORDED;
        private EventGrpcDeadlineAtTransactionBeforeCompletionState
                grpcDeadlineAtTransactionBeforeCompletion =
                EventGrpcDeadlineAtTransactionBeforeCompletionState.NOT_RECORDED;
        private EventGrpcDeadlineAtTransactionFirstAfterCommitState
                grpcDeadlineAtTransactionFirstAfterCommit =
                EventGrpcDeadlineAtTransactionFirstAfterCommitState.NOT_RECORDED;
        private EventGrpcDeadlineAtTransactionAfterCommitState
                grpcDeadlineAtTransactionAfterCommit =
                EventGrpcDeadlineAtTransactionAfterCommitState.NOT_RECORDED;
        private EventComponentState useCase = EventComponentState.NOT_COMPLETED;
        private EventComponentState authorityAdapterCalls = EventComponentState.NOT_COMPLETED;
        private EventComponentState consentPolicy = EventComponentState.NOT_COMPLETED;
        private EventComponentState sourceKey = EventComponentState.NOT_COMPLETED;
        private EventComponentState sourceEnvelope = EventComponentState.NOT_COMPLETED;
        private EventComponentState measuredComponentSum = EventComponentState.NOT_COMPLETED;
        private EventComponentState unmeasuredResidual = EventComponentState.NOT_COMPLETED;
        private EventComponentState applicationResidual = EventComponentState.NOT_COMPLETED;
        private EventComponentState transactionEnvelopeResidual = EventComponentState.NOT_COMPLETED;
        private EventComponentState transactionEnvelopeTargetMethodEntryPrefix =
                EventComponentState.NOT_COMPLETED;
        private EventComponentState transactionEnvelopeTargetMethodReturnSuffix =
                EventComponentState.NOT_COMPLETED;

        private synchronized void advance(EventCallPhase expected, EventCallPhase next) {
            boolean valid = expected != null && next != null && switch (expected) {
                case SERVER_ENTERED -> next == EventCallPhase.SERVICE_METHOD_ENTERED;
                case SERVICE_METHOD_ENTERED -> next == EventCallPhase.USE_CASE_ENTERED;
                case USE_CASE_ENTERED -> next == EventCallPhase.USE_CASE_COMPLETED;
                case USE_CASE_COMPLETED -> next == EventCallPhase.ON_COMPLETED_ENTERED;
                case ON_COMPLETED_ENTERED -> next == EventCallPhase.SERVER_CLOSED;
                case SERVER_CLOSED -> next == EventCallPhase.ON_COMPLETED_RETURNED;
                case ON_COMPLETED_RETURNED -> false;
            };
            if (!valid || phase != expected) {
                throw new IllegalStateException(
                        "bounded event call phase transition is invalid");
            }
            phase = next;
        }

        private synchronized void recordGrpcDeadlineIngress(
                Deadline grpcDeadline) {
            if (grpcDeadlineIngressHeadroom
                    != EventGrpcDeadlineIngressHeadroomState.NOT_RECORDED
                    || phase != EventCallPhase.SERVER_ENTERED) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline ingress headroom state is invalid");
            }
            if (grpcDeadline == null) {
                grpcDeadlineIngressHeadroom =
                        EventGrpcDeadlineIngressHeadroomState.UNAVAILABLE;
            } else {
                long remainingNanos =
                        grpcDeadline.timeRemaining(TimeUnit.NANOSECONDS);
                grpcDeadlineIngressHeadroom = remainingNanos <= 0L
                        ? EventGrpcDeadlineIngressHeadroomState.EXPIRED
                        : remainingNanos > 120_000_000L
                        ? EventGrpcDeadlineIngressHeadroomState.ABOVE_120_MS_REFERENCE_BAND
                        : EventGrpcDeadlineIngressHeadroomState.WITHIN_120_MS_REFERENCE_BAND;
            }
        }

        private synchronized void recordCloseReturn(
                long elapsed, Deadline grpcDeadline) {
            if (elapsed < 0L
                    || closeReturn != EventCloseReturnState.NOT_RETURNED
                    || closeReturnReferenceBand
                    != EventCloseReturnReferenceBand.NOT_RETURNED
                    || grpcDeadlineAtCloseReturn
                    != EventGrpcDeadlineAtCloseReturnState.NOT_RECORDED
                    || grpcDeadlineHeadroom
                    != EventGrpcDeadlineHeadroomState.NOT_RECORDED
                    || phase != EventCallPhase.ON_COMPLETED_ENTERED) {
                throw new IllegalStateException(
                        "bounded event close-return state is invalid");
            }
            closeReturn = elapsed > 150_000_000L
                    ? EventCloseReturnState.DEADLINE_CROSSED
                    : EventCloseReturnState.WITHIN_DEADLINE;
            closeReturnReferenceBand = elapsed > 150_000_000L
                    ? EventCloseReturnReferenceBand.DEADLINE_CROSSED
                    : elapsed > 120_000_000L
                    ? EventCloseReturnReferenceBand.UPPER_REFERENCE_BAND
                    : EventCloseReturnReferenceBand.LOWER_REFERENCE_BAND;
            if (grpcDeadline == null) {
                grpcDeadlineAtCloseReturn =
                        EventGrpcDeadlineAtCloseReturnState.UNAVAILABLE;
                grpcDeadlineHeadroom =
                        EventGrpcDeadlineHeadroomState.UNAVAILABLE;
            } else if (grpcDeadline.isExpired()) {
                grpcDeadlineAtCloseReturn =
                        EventGrpcDeadlineAtCloseReturnState.EXPIRED;
                grpcDeadlineHeadroom =
                        EventGrpcDeadlineHeadroomState.EXPIRED;
            } else {
                grpcDeadlineAtCloseReturn =
                        EventGrpcDeadlineAtCloseReturnState.ACTIVE;
                grpcDeadlineHeadroom =
                        grpcDeadline.timeRemaining(TimeUnit.NANOSECONDS) > 30_000_000L
                        ? EventGrpcDeadlineHeadroomState.ACTIVE_ABOVE_30_MS_REFERENCE_BAND
                        : EventGrpcDeadlineHeadroomState.ACTIVE_WITHIN_30_MS_REFERENCE_BAND;
            }
        }

        private synchronized void recordTransportTerminal(
                EventTransportTerminalState next) {
            if (next == null
                    || next == EventTransportTerminalState.NOT_RECORDED
                    || transportTerminal
                    != EventTransportTerminalState.NOT_RECORDED) {
                throw new IllegalStateException(
                        "bounded event transport terminal state is invalid");
            }
            transportTerminal = next;
        }

        private synchronized boolean transportTerminalRecorded() {
            return transportTerminal != EventTransportTerminalState.NOT_RECORDED;
        }

        private synchronized void recordGrpcDeadlineAtUseCaseCompletion(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtUseCaseCompletion
                    != EventGrpcDeadlineAtUseCaseCompletionState.NOT_RECORDED
                    || phase != EventCallPhase.USE_CASE_ENTERED) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline at use-case completion state is invalid");
            }
            grpcDeadlineAtUseCaseCompletion = grpcDeadline == null
                    ? EventGrpcDeadlineAtUseCaseCompletionState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? EventGrpcDeadlineAtUseCaseCompletionState.EXPIRED
                    : EventGrpcDeadlineAtUseCaseCompletionState.ACTIVE;
        }

        private synchronized void recordGrpcDeadlineAtTransactionBeforeCommit(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtTransactionBeforeCommit
                    != EventGrpcDeadlineAtTransactionBeforeCommitState.NOT_RECORDED
                    || phase != EventCallPhase.USE_CASE_ENTERED) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline at transaction before-commit state is invalid");
            }
            grpcDeadlineAtTransactionBeforeCommit = grpcDeadline == null
                    ? EventGrpcDeadlineAtTransactionBeforeCommitState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? EventGrpcDeadlineAtTransactionBeforeCommitState.EXPIRED
                    : EventGrpcDeadlineAtTransactionBeforeCommitState.ACTIVE;
        }

        private synchronized void recordGrpcDeadlineAtTransactionBeforeCompletion(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtTransactionBeforeCommit
                    == EventGrpcDeadlineAtTransactionBeforeCommitState.NOT_RECORDED) {
                return;
            }
            if (grpcDeadlineAtTransactionBeforeCompletion
                    != EventGrpcDeadlineAtTransactionBeforeCompletionState.NOT_RECORDED
                    || grpcDeadlineAtTransactionAfterCommit
                    != EventGrpcDeadlineAtTransactionAfterCommitState.NOT_RECORDED
                    || phase != EventCallPhase.USE_CASE_ENTERED) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline at transaction before-completion state is invalid");
            }
            grpcDeadlineAtTransactionBeforeCompletion = grpcDeadline == null
                    ? EventGrpcDeadlineAtTransactionBeforeCompletionState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? EventGrpcDeadlineAtTransactionBeforeCompletionState.EXPIRED
                    : EventGrpcDeadlineAtTransactionBeforeCompletionState.ACTIVE;
        }

        private synchronized void recordGrpcDeadlineAtTransactionFirstAfterCommit(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtTransactionBeforeCompletion
                    == EventGrpcDeadlineAtTransactionBeforeCompletionState.NOT_RECORDED) {
                return;
            }
            if (grpcDeadlineAtTransactionFirstAfterCommit
                    != EventGrpcDeadlineAtTransactionFirstAfterCommitState.NOT_RECORDED
                    || grpcDeadlineAtTransactionAfterCommit
                    != EventGrpcDeadlineAtTransactionAfterCommitState.NOT_RECORDED
                    || phase != EventCallPhase.USE_CASE_ENTERED) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline at transaction first after-commit state is invalid");
            }
            grpcDeadlineAtTransactionFirstAfterCommit = grpcDeadline == null
                    ? EventGrpcDeadlineAtTransactionFirstAfterCommitState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? EventGrpcDeadlineAtTransactionFirstAfterCommitState.EXPIRED
                    : EventGrpcDeadlineAtTransactionFirstAfterCommitState.ACTIVE;
        }

        private synchronized void recordGrpcDeadlineAtTransactionAfterCommit(
                Deadline grpcDeadline) {
            if (grpcDeadlineAtTransactionAfterCommit
                    != EventGrpcDeadlineAtTransactionAfterCommitState.NOT_RECORDED
                    || grpcDeadlineAtTransactionBeforeCommit
                    == EventGrpcDeadlineAtTransactionBeforeCommitState.NOT_RECORDED
                    || phase != EventCallPhase.USE_CASE_ENTERED) {
                throw new IllegalStateException(
                        "bounded event gRPC deadline at transaction after-commit state is invalid");
            }
            grpcDeadlineAtTransactionAfterCommit = grpcDeadline == null
                    ? EventGrpcDeadlineAtTransactionAfterCommitState.UNAVAILABLE
                    : grpcDeadline.isExpired()
                    ? EventGrpcDeadlineAtTransactionAfterCommitState.EXPIRED
                    : EventGrpcDeadlineAtTransactionAfterCommitState.ACTIVE;
        }

        private synchronized void recordUseCase(long elapsed) {
            useCase = recordSingle(useCase, elapsed);
        }

        private synchronized void recordAuthorityAdapterCall(long elapsed) {
            EventComponentState completed = completedState(elapsed);
            if (authorityAdapterCalls == EventComponentState.NOT_COMPLETED) {
                authorityAdapterCalls = completed;
            } else if (completed == EventComponentState.DEADLINE_CROSSED) {
                authorityAdapterCalls = EventComponentState.DEADLINE_CROSSED;
            }
        }

        private synchronized void recordConsentPolicy(long elapsed) {
            consentPolicy = recordSingle(consentPolicy, elapsed);
        }

        private synchronized void recordSourceKey(long elapsed) {
            sourceKey = recordSingle(sourceKey, elapsed);
        }

        private synchronized void recordSourceEnvelope(long elapsed) {
            sourceEnvelope = recordSingle(sourceEnvelope, elapsed);
        }

        private synchronized void recordMeasuredComponentSum(long elapsed) {
            measuredComponentSum = recordSingle(measuredComponentSum, elapsed);
        }

        private synchronized void recordUnmeasuredResidual(long elapsed) {
            unmeasuredResidual = recordSingle(unmeasuredResidual, elapsed);
        }

        private synchronized void recordApplicationResidual(long elapsed) {
            applicationResidual = recordSingle(applicationResidual, elapsed);
        }

        private synchronized void recordTransactionEnvelopeResidual(long elapsed) {
            transactionEnvelopeResidual = recordSingle(transactionEnvelopeResidual, elapsed);
        }

        private synchronized void recordTransactionEnvelopeTargetMethodEntryPrefix(
                long elapsed) {
            transactionEnvelopeTargetMethodEntryPrefix = recordSingle(
                    transactionEnvelopeTargetMethodEntryPrefix, elapsed);
        }

        private synchronized void recordTransactionEnvelopeTargetMethodReturnSuffix(
                long elapsed) {
            transactionEnvelopeTargetMethodReturnSuffix = recordSingle(
                    transactionEnvelopeTargetMethodReturnSuffix, elapsed);
        }

        private static EventComponentState recordSingle(
                EventComponentState current,
                long elapsed) {
            if (current != EventComponentState.NOT_COMPLETED) {
                throw new IllegalStateException(
                        "duplicate event component timing is invalid");
            }
            return completedState(elapsed);
        }

        private static EventComponentState completedState(long elapsed) {
            if (elapsed < 0L) {
                throw new IllegalStateException(
                        "negative event component timing is invalid");
            }
            return elapsed > 150_000_000L
                    ? EventComponentState.DEADLINE_CROSSED
                    : EventComponentState.WITHIN_DEADLINE;
        }

        private synchronized EventCallSnapshot snapshot() {
            return new EventCallSnapshot(
                    phase,
                    closeReturn,
                    closeReturnReferenceBand,
                    grpcDeadlineAtCloseReturn,
                    grpcDeadlineHeadroom,
                    grpcDeadlineIngressHeadroom,
                    transportTerminal,
                    grpcDeadlineAtUseCaseCompletion,
                    useCase,
                    authorityAdapterCalls,
                    consentPolicy,
                    sourceKey,
                    sourceEnvelope,
                    measuredComponentSum,
                    unmeasuredResidual,
                    applicationResidual,
                    transactionEnvelopeResidual,
                    transactionEnvelopeTargetMethodEntryPrefix,
                    transactionEnvelopeTargetMethodReturnSuffix,
                    grpcDeadlineAtTransactionBeforeCommit,
                    grpcDeadlineAtTransactionBeforeCompletion,
                    grpcDeadlineAtTransactionFirstAfterCommit,
                    grpcDeadlineAtTransactionAfterCommit);
        }
    }

    private static final Context.Key<EventCallPhaseState> EVENT_CALL_PHASE_STATE =
            Context.key("m5-event-call-phase");
    private static final Context.Key<LearnerIdentity> LOAD_IDENTITY = Context.key("m5-load-identity");

    private static ResolveCallPhaseState requireResolveCallPhaseState() {
        ResolveCallPhaseState state = RESOLVE_CALL_PHASE_STATE.get();
        if (state == null) {
            throw new IllegalStateException("bounded resolve call phase context is invalid");
        }
        return state;
    }

    private static EventCallPhaseState requireEventCallPhaseState() {
        EventCallPhaseState state = EVENT_CALL_PHASE_STATE.get();
        if (state == null) {
            throw new IllegalStateException("bounded event call phase context is invalid");
        }
        return state;
    }

    private static final class LoadIdentityInterceptor implements ServerInterceptor {
        private static final Metadata.Key<String> SESSION_HEADER = Metadata.Key.of(
                "x-memory-load-session", Metadata.ASCII_STRING_MARSHALLER);
        private final Map<String, LearnerIdentity> identities;
        private final java.util.concurrent.atomic.AtomicInteger ingress;
        private final M5R3BoundaryDiagnostics diagnostics;

        private LoadIdentityInterceptor(
                Map<String, LearnerIdentity> identities,
                java.util.concurrent.atomic.AtomicInteger ingress,
                M5R3BoundaryDiagnostics diagnostics) {
            this.identities = Map.copyOf(identities);
            this.ingress = Objects.requireNonNull(ingress, "ingress");
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            return diagnostics.measure(LOAD_IDENTITY_INGRESS, () -> {
                ingress.incrementAndGet();
                String bearer = headers.get(SignedSessionInterceptor.AUTHORIZATION);
                String session = headers.get(SESSION_HEADER);
                LearnerIdentity identity = session != null && SESSION.matcher(session).matches()
                        ? identities.get(session) : null;
                if (!FIXED_BEARER.equals(bearer) || identity == null) {
                    call.close(Status.UNAUTHENTICATED.withDescription("invalid load identity"), new Metadata());
                    return new ServerCall.Listener<ReqT>() {
                    };
                }
                ServerCall.Listener<ReqT> listener = Contexts.interceptCall(
                        Context.current().withValue(LOAD_IDENTITY, identity), call, headers, next);
                return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
                    @Override
                    public void onMessage(ReqT message) {
                        diagnostics.measure(
                                GRPC_LISTENER_ON_MESSAGE,
                                () -> super.onMessage(message));
                    }

                    @Override
                    public void onHalfClose() {
                        diagnostics.measure(
                                GRPC_LISTENER_ON_HALF_CLOSE,
                                () -> super.onHalfClose());
                    }

                    @Override
                    public void onCancel() {
                        diagnostics.measure(
                                GRPC_LISTENER_ON_CANCEL,
                                () -> super.onCancel());
                    }
                };
            });
        }
    }

    private static LearnerIdentity requireLoadIdentity() {
        LearnerIdentity identity = LOAD_IDENTITY.get();
        if (identity == null) {
            throw Status.UNAUTHENTICATED.asRuntimeException();
        }
        return identity;
    }

    private static final class LoadContextService
            extends MemoryContextServiceGrpc.MemoryContextServiceImplBase {
        private final ResolveMemoryContextUseCase resolver;
        private final M5R3BoundaryDiagnostics diagnostics;

        private LoadContextService(
                ResolveMemoryContextUseCase resolver,
                M5R3BoundaryDiagnostics diagnostics) {
            this.resolver = resolver;
            this.diagnostics = diagnostics;
        }

        @Override
        public void resolveMemoryContext(
                ResolveMemoryContextRequest request,
                StreamObserver<ResolveMemoryContextResponse> observer) {
            ResolveCallPhaseState resolveCallPhaseState = requireResolveCallPhaseState();
            resolveCallPhaseState.advance(
                    ResolveCallPhase.SERVER_ENTERED,
                    ResolveCallPhase.SERVICE_METHOD_ENTERED);
            try {
                diagnostics.mark(RESOLVE_SERVICE_METHOD_ENTRY);
                LearnerIdentity identity = requireLoadIdentity();
                if (!request.getSessionId().equals(identity.sessionId())) {
                    throw Status.PERMISSION_DENIED.asRuntimeException();
                }
                resolveCallPhaseState.advance(
                        ResolveCallPhase.SERVICE_METHOD_ENTERED,
                        ResolveCallPhase.USE_CASE_ACTIVE);
                resolveCallPhaseState.recordGrpcDeadlineAtUseCaseStart(
                        Context.current().getDeadline());
                var result = diagnostics.measure(
                        RESOLVE_USE_CASE,
                        () -> resolver.resolve(identity, loadMemoryQuery(
                                request.getQueryText(),
                                request.getTaskType(),
                                request.getBudget().getMaxItems())));
                resolveCallPhaseState.recordGrpcDeadlineAtUseCaseCompletion(
                        Context.current().getDeadline());
                resolveCallPhaseState.advance(
                        ResolveCallPhase.USE_CASE_ACTIVE,
                        ResolveCallPhase.USE_CASE_COMPLETED);
                MemoryApplicationStatus status = switch (result.status()) {
                    case APPLIED -> MemoryApplicationStatus.APPLIED;
                    case EMPTY -> MemoryApplicationStatus.EMPTY;
                    case DEGRADED -> MemoryApplicationStatus.DEGRADED;
                };
                observer.onNext(ResolveMemoryContextResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setSchemaVersion("v1")
                        .setStatus(status)
                        .setIdentityBindingDigest("0".repeat(64))
                        .build());
                resolveCallPhaseState.recordGrpcDeadlineAtResponseSent(
                        Context.current().getDeadline());
                resolveCallPhaseState.advance(
                        ResolveCallPhase.USE_CASE_COMPLETED,
                        ResolveCallPhase.ON_COMPLETED_ENTERED);
                observer.onCompleted();
                resolveCallPhaseState.advance(
                        ResolveCallPhase.SERVER_CLOSED,
                        ResolveCallPhase.ON_COMPLETED_RETURNED);
                resolveCallPhaseState.recordGrpcDeadlineAtOnCompletedReturn(
                        Context.current().getDeadline());
            } catch (RuntimeException error) {
                observer.onError(Status.UNAVAILABLE.withDescription("load resolve unavailable").asRuntimeException());
            }
        }
    }

    private static final class LoadEventService extends MemoryEventServiceGrpc.MemoryEventServiceImplBase {
        private final SubmitMemoryEventsUseCase useCase;
        private final M5R3BoundaryDiagnostics diagnostics;

        private LoadEventService(
                SubmitMemoryEventsUseCase useCase,
                M5R3BoundaryDiagnostics diagnostics) {
            this.useCase = useCase;
            this.diagnostics = diagnostics;
        }

        @Override
        public void submitMemoryEvents(
                SubmitMemoryEventsRequest request,
                StreamObserver<SubmitMemoryEventsResponse> observer) {
            EventCallPhaseState eventCallPhaseState = requireEventCallPhaseState();
            eventCallPhaseState.advance(
                    EventCallPhase.SERVER_ENTERED,
                    EventCallPhase.SERVICE_METHOD_ENTERED);
            try {
                LearnerIdentity identity = requireLoadIdentity();
                var commands = request.getEventsList().stream()
                        .map(event -> new SubmitMemoryEventsUseCase.InteractionEventCommand(
                                UUID.fromString(event.getId()),
                                "v1",
                                "REFLECTION",
                                MemoryCategory.REFLECTION,
                                SourceKind.GENERAL,
                                identity.subjectHash(),
                                identity.consentRevision(),
                                Instant.now(),
                                PrivacyLevel.STANDARD,
                                event.getData().toByteArray(),
                                request.getRequestId()))
                        .toList();
                var response = SubmitMemoryEventsResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setSchemaVersion("v1");
                eventCallPhaseState.advance(
                        EventCallPhase.SERVICE_METHOD_ENTERED,
                        EventCallPhase.USE_CASE_ENTERED);
                var receipts = diagnostics.measure(
                        SUBMIT_EVENT_USE_CASE,
                        () -> useCase.submit(identity, commands));
                eventCallPhaseState.advance(
                        EventCallPhase.USE_CASE_ENTERED,
                        EventCallPhase.USE_CASE_COMPLETED);
                for (var receipt : receipts) {
                    response.addReceipts(EventReceipt.newBuilder()
                            .setEventId(receipt.eventId().toString())
                            .setResult(switch (receipt.result()) {
                                case ACCEPTED -> EventReceipt.Result.ACCEPTED;
                                case DUPLICATE -> EventReceipt.Result.DUPLICATE;
                                case REJECTED -> EventReceipt.Result.REJECTED;
                            })
                            .setReasonCode(receipt.reasonCode() == null ? "" : receipt.reasonCode()));
                }
                observer.onNext(response.build());
                eventCallPhaseState.advance(
                        EventCallPhase.USE_CASE_COMPLETED,
                        EventCallPhase.ON_COMPLETED_ENTERED);
                observer.onCompleted();
                eventCallPhaseState.advance(
                        EventCallPhase.SERVER_CLOSED,
                        EventCallPhase.ON_COMPLETED_RETURNED);
            } catch (RuntimeException error) {
                observer.onError(Status.UNAVAILABLE.withDescription("load event unavailable").asRuntimeException());
            }
        }
    }

    private static final class EventTransportTerminalTracerFactory
            extends ServerStreamTracer.Factory {
        private final ResolveTimingInterceptor timing;

        private EventTransportTerminalTracerFactory(
                ResolveTimingInterceptor timing) {
            this.timing = Objects.requireNonNull(
                    timing, "resolve timing interceptor");
        }

        @Override
        public ServerStreamTracer newServerStreamTracer(
                String fullMethodName,
                Metadata headers) {
            if (!MemoryEventServiceGrpc.getSubmitMemoryEventsMethod()
                    .getFullMethodName().equals(fullMethodName)) {
                return new ServerStreamTracer() {};
            }
            Iterable<String> values = headers.getAll(
                    ResolveTimingInterceptor.EVENT_CORRELATION_HEADER_KEY);
            if (values == null) {
                return new ServerStreamTracer() {};
            }
            String correlationDigest = null;
            for (String value : values) {
                if (correlationDigest != null
                        || value == null
                        || !CORRELATION_DIGEST.matcher(value).matches()) {
                    return new ServerStreamTracer() {};
                }
                correlationDigest = value;
            }
            if (correlationDigest == null) {
                return new ServerStreamTracer() {};
            }
            String boundCorrelationDigest = correlationDigest;
            return new ServerStreamTracer() {
                @Override
                public void streamClosed(Status status) {
                    timing.recordCorrelatedTransportTerminal(
                            boundCorrelationDigest,
                            transportTerminalState(status));
                }
            };
        }

        private static EventTransportTerminalState transportTerminalState(
                Status status) {
            if (status == null) {
                throw new IllegalStateException(
                        "event transport terminal status is invalid");
            }
            return switch (status.getCode()) {
                case OK -> EventTransportTerminalState.OK;
                case CANCELLED -> EventTransportTerminalState.CANCELLED;
                default -> EventTransportTerminalState.OTHER;
            };
        }
    }

    private static final class ResolveTimingInterceptor implements ServerInterceptor {
        private static final Metadata.Key<String> EVENT_RESPONSE_HEADER_KEY =
                Metadata.Key.of(
                        "x-yilan-m5-event-response",
                        Metadata.ASCII_STRING_MARSHALLER);
        private static final Metadata.Key<String> EVENT_CORRELATION_HEADER_KEY =
                Metadata.Key.of(
                        "x-yilan-m5-correlation",
                        Metadata.ASCII_STRING_MARSHALLER);
        private static final String EVENT_RESPONSE_HEADER_VALUE = "observed";
        private final List<Long> samples = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Long> eventSamples = new ArrayList<>();
        private final List<Long> eventCloseReturnSamples = new ArrayList<>();
        private final AtomicLong eventEntryCount = new AtomicLong();
        private final Object eventDeadlineDiagnosticLock;
        private ResolveCallSnapshot firstCancelledResolveSnapshot;
        private java.util.concurrent.CountDownLatch firstCancelledResolveSnapshotSignal = new java.util.concurrent.CountDownLatch(1);
        private EventCallSnapshot firstCancelledEventSnapshot;
        private java.util.concurrent.CountDownLatch firstCancelledEventSnapshotSignal = new java.util.concurrent.CountDownLatch(1);
        private final int maximumCorrelatedEventCalls;
        private final Map<String, EventCallPhaseState> correlatedEventCalls = new java.util.HashMap<>();
        private final Map<String, java.util.concurrent.atomic.AtomicInteger> statusCounts =
                new java.util.concurrent.ConcurrentHashMap<>();

        private ResolveTimingInterceptor(
                Object eventDeadlineDiagnosticLock,
                int maximumCorrelatedEventCalls) {
            this.eventDeadlineDiagnosticLock = Objects.requireNonNull(
                    eventDeadlineDiagnosticLock, "eventDeadlineDiagnosticLock");
            if (maximumCorrelatedEventCalls < 1) {
                throw new IllegalArgumentException(
                        "maximum correlated event calls must be positive");
            }
            this.maximumCorrelatedEventCalls = maximumCorrelatedEventCalls;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            String method = call.getMethodDescriptor().getFullMethodName();
            boolean resolve = method.endsWith("/ResolveMemoryContext");
            boolean event = method.endsWith("/SubmitMemoryEvents");
            final Deadline eventGrpcDeadline =
                    event ? Context.current().getDeadline() : null;
            if (!event
                    && headers.getAll(EVENT_CORRELATION_HEADER_KEY) != null) {
                throw new IllegalStateException(
                        "event correlation metadata is forbidden on non-event calls");
            }
            if (!resolve && !event) {
                return next.startCall(call, headers);
            }
            ResolveCallPhaseState resolveCallPhaseState = resolve ? new ResolveCallPhaseState() : null;
            EventCallPhaseState eventCallPhaseState = event ? new EventCallPhaseState() : null;
            if (event) {
                eventCallPhaseState.recordGrpcDeadlineIngress(eventGrpcDeadline);
                synchronized (eventDeadlineDiagnosticLock) {
                    registerCorrelatedEvent(headers, eventCallPhaseState);
                    eventEntryCount.incrementAndGet();
                }
            }
            long started = System.nanoTime();
            ServerCall<ReqT, RespT> timingCall =
                    new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                private boolean recorded;

                @Override
                public void sendHeaders(Metadata responseHeaders) {
                    if (event) {
                        responseHeaders.put(
                                EVENT_RESPONSE_HEADER_KEY,
                                EVENT_RESPONSE_HEADER_VALUE);
                    }
                    super.sendHeaders(responseHeaders);
                }

                @Override
                public void close(Status status, Metadata trailers) {
                    long elapsed = 0L;
                    boolean firstClose = false;
                    if (!recorded) {
                        recorded = true;
                        firstClose = true;
                        elapsed = Math.max(0L, System.nanoTime() - started);
                        if (resolve) {
                            samples.add(elapsed);
                        } else {
                            synchronized (eventDeadlineDiagnosticLock) {
                                eventSamples.add(elapsed);
                            }
                        }
                        statusCounts.computeIfAbsent(
                                (resolve ? "resolve:" : "event:") + status.getCode().name(),
                                ignored -> new java.util.concurrent.atomic.AtomicInteger())
                                .incrementAndGet();
                    }
                    if (resolve && firstClose) {
                        resolveCallPhaseState.recordCloseStart(elapsed);
                    }
                    super.close(status, trailers);
                    if (event && firstClose) {
                        long closeReturnElapsed =
                                Math.max(0L, System.nanoTime() - started);
                        synchronized (eventDeadlineDiagnosticLock) {
                            eventCloseReturnSamples.add(closeReturnElapsed);
                        }
                        if (status.isOk()) {
                            eventCallPhaseState.recordCloseReturn(
                                    closeReturnElapsed, eventGrpcDeadline);
                        }
                    }
                    if (event && status.isOk()) {
                        eventCallPhaseState.advance(
                                EventCallPhase.ON_COMPLETED_ENTERED,
                                EventCallPhase.SERVER_CLOSED);
                    }
                    if (resolve && status.isOk()) {
                        resolveCallPhaseState.advance(
                                ResolveCallPhase.ON_COMPLETED_ENTERED,
                                ResolveCallPhase.SERVER_CLOSED);
                    }
                }
            };
            if (!event) {
                if (resolve) {
                    return interceptResolveCall(
                            resolveCallPhaseState, timingCall, headers, next);
                }
                return next.startCall(timingCall, headers);
            }
            ServerCall.Listener<ReqT> listener = Contexts.interceptCall(
                    Context.current().withValue(
                            EVENT_CALL_PHASE_STATE, eventCallPhaseState),
                    timingCall,
                    headers,
                    next);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                    listener) {
                @Override
                public void onCancel() {
                    captureFirstCancelledEventSnapshot(eventCallPhaseState.snapshot());
                    super.onCancel();
                }
            };
        }

        List<Long> snapshot() {
            synchronized (samples) {
                return List.copyOf(samples);
            }
        }

        private <ReqT, RespT> ServerCall.Listener<ReqT> interceptResolveCall(
                ResolveCallPhaseState resolveCallPhaseState,
                ServerCall<ReqT, RespT> timingCall,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> listener = Contexts.interceptCall(
                    Context.current().withValue(
                            RESOLVE_CALL_PHASE_STATE, resolveCallPhaseState),
                    timingCall,
                    headers,
                    next);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
                @Override
                public void onCancel() {
                    captureFirstCancelledResolveSnapshot(resolveCallPhaseState.snapshot());
                    super.onCancel();
                }
            };
        }

        void clear() {
            samples.clear();
            synchronized (eventDeadlineDiagnosticLock) {
                eventSamples.clear();
                eventCloseReturnSamples.clear();
                eventEntryCount.set(0L);
                firstCancelledResolveSnapshot = null;
                firstCancelledResolveSnapshotSignal =
                        new java.util.concurrent.CountDownLatch(1);
                firstCancelledEventSnapshot = null;
                firstCancelledEventSnapshotSignal =
                        new java.util.concurrent.CountDownLatch(1);
                correlatedEventCalls.clear();
            }
            statusCounts.clear();
        }

        private void registerCorrelatedEvent(
                Metadata headers,
                EventCallPhaseState eventCallPhaseState) {
            Iterable<String> values = headers.getAll(EVENT_CORRELATION_HEADER_KEY);
            if (values == null) {
                return;
            }
            String correlationDigest = null;
            for (String value : values) {
                if (correlationDigest != null
                        || value == null
                        || !CORRELATION_DIGEST.matcher(value).matches()) {
                    throw new IllegalStateException(
                            "event correlation metadata is invalid");
                }
                correlationDigest = value;
            }
            if (correlationDigest == null) {
                throw new IllegalStateException(
                        "event correlation metadata is empty");
            }
            if (correlatedEventCalls.size() >= maximumCorrelatedEventCalls) {
                throw new IllegalStateException(
                        "event correlation state exceeded its configured bound");
            }
            if (correlatedEventCalls.putIfAbsent(
                    correlationDigest, eventCallPhaseState) != null) {
                throw new IllegalStateException(
                        "event correlation digest was registered more than once");
            }
            eventDeadlineDiagnosticLock.notifyAll();
        }

        private void recordCorrelatedTransportTerminal(
                String correlationDigest,
                EventTransportTerminalState transportTerminal) {
            synchronized (eventDeadlineDiagnosticLock) {
                EventCallPhaseState eventCallPhaseState =
                        correlatedEventCalls.get(correlationDigest);
                if (eventCallPhaseState == null) {
                    return;
                }
                eventCallPhaseState.recordTransportTerminal(transportTerminal);
                eventDeadlineDiagnosticLock.notifyAll();
            }
        }

        private EventCallSnapshot exactEventSnapshot(String correlationDigest) {
            if (correlationDigest == null
                    || !CORRELATION_DIGEST.matcher(correlationDigest).matches()) {
                throw new IllegalArgumentException(
                        "exact event correlation digest is invalid");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
            synchronized (eventDeadlineDiagnosticLock) {
                while (true) {
                    EventCallPhaseState eventCallPhaseState =
                            correlatedEventCalls.get(correlationDigest);
                    if (eventCallPhaseState != null
                            && eventCallPhaseState.transportTerminalRecorded()) {
                        return eventCallPhaseState.snapshot();
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        return eventCallPhaseState == null
                                ? null
                                : eventCallPhaseState.snapshot();
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(
                                eventDeadlineDiagnosticLock,
                                remaining);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "exact event correlation wait was interrupted");
                    }
                }
            }
        }

        String diagnostic() {
            long eventCount;
            long eventMaximumNanos;
            synchronized (eventDeadlineDiagnosticLock) {
                eventCount = eventSamples.size();
                eventMaximumNanos = eventSamples.stream()
                        .mapToLong(Long::longValue)
                        .max()
                        .orElse(0L);
            }
            return "service_timing{resolve_count=" + samples.size()
                    + ",resolve_max_ms=" + maximumMillis(samples)
                    + ",event_count=" + eventCount
                    + ",event_max_ms=" + eventMaximumNanos / 1_000_000.0d
                    + ",status_counts=" + statusCounts.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> entry.getKey() + "=" + entry.getValue().get())
                            .toList()
                    + "}";
        }

        private void captureFirstCancelledResolveSnapshot(ResolveCallSnapshot snapshot) {
            if (snapshot == null) {
                throw new IllegalStateException(
                        "bounded resolve cancellation snapshot is invalid");
            }
            synchronized (eventDeadlineDiagnosticLock) {
                if (firstCancelledResolveSnapshot == null) {
                    firstCancelledResolveSnapshot = snapshot;
                    firstCancelledResolveSnapshotSignal.countDown();
                }
            }
        }

        private ResolveCallSnapshot firstCancelledResolveSnapshot() {
            synchronized (eventDeadlineDiagnosticLock) {
                if (firstCancelledResolveSnapshot != null) {
                    return firstCancelledResolveSnapshot;
                }
            }
            try {
                firstCancelledResolveSnapshotSignal.await(1L, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "bounded resolve cancellation wait was interrupted");
            }
            synchronized (eventDeadlineDiagnosticLock) {
                return firstCancelledResolveSnapshot;
            }
        }

        private void captureFirstCancelledEventSnapshot(EventCallSnapshot snapshot) {
            if (snapshot == null) {
                throw new IllegalStateException(
                        "bounded event cancellation snapshot is invalid");
            }
            synchronized (eventDeadlineDiagnosticLock) {
                if (firstCancelledEventSnapshot == null) {
                    firstCancelledEventSnapshot = snapshot;
                    firstCancelledEventSnapshotSignal.countDown();
                }
            }
        }

        private EventCallSnapshot firstCancelledEventSnapshot() {
            synchronized (eventDeadlineDiagnosticLock) {
                if (firstCancelledEventSnapshot != null) {
                    return firstCancelledEventSnapshot;
                }
            }
            try {
                firstCancelledEventSnapshotSignal.await(1L, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "bounded event cancellation wait was interrupted");
            }
            synchronized (eventDeadlineDiagnosticLock) {
                return firstCancelledEventSnapshot;
            }
        }

        private static double maximumMillis(List<Long> source) {
            return maximumNanos(source) / 1_000_000.0d;
        }

        private static long maximumNanos(List<Long> source) {
            synchronized (source) {
                return source.stream().mapToLong(Long::longValue).max().orElse(0L);
            }
        }
    }

    private static final class M5R3BoundaryDiagnostics {
        private static final List<String> STAGES = List.of(
                SIGNED_IDENTITY_INGRESS,
                LOAD_IDENTITY_INGRESS,
                GRPC_LISTENER_ON_MESSAGE,
                GRPC_LISTENER_ON_HALF_CLOSE,
                GRPC_LISTENER_ON_CANCEL,
                RESOLVE_SERVICE_METHOD_ENTRY,
                RESOLVE_USE_CASE,
                SUBMIT_EVENT_USE_CASE,
                RESOLVE_AUTHORITY_ADAPTER,
                SUBMIT_EVENT_TRANSACTION_AUTHORITY_ADAPTER);

        private final Map<String, BoundaryCounter> counters =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Object eventDeadlineDiagnosticLock;
        private final ThreadLocal<EventUseCaseAccumulator> eventUseCaseAccumulator = new ThreadLocal<>();
        private final AtomicLong maximumConsentPolicyDurationNanos = new AtomicLong();
        private final AtomicLong maximumSourceKeyDurationNanos = new AtomicLong();
        private final AtomicLong maximumSourceEnvelopeDurationNanos = new AtomicLong();
        private final AtomicLong maximumMeasuredComponentSumNanos = new AtomicLong();
        private final AtomicLong maximumUnmeasuredResidualNanos = new AtomicLong();
        private final AtomicLong maximumApplicationResidualNanos = new AtomicLong();
        private final AtomicLong maximumTransactionEnvelopeResidualNanos = new AtomicLong();

        private M5R3BoundaryDiagnostics(Object eventDeadlineDiagnosticLock) {
            this.eventDeadlineDiagnosticLock = Objects.requireNonNull(
                    eventDeadlineDiagnosticLock, "eventDeadlineDiagnosticLock");
            STAGES.forEach(stage -> counters.put(stage, new BoundaryCounter()));
        }

        <T> T measure(String stage, Supplier<T> action) {
            BoundaryCounter counter = requireStage(stage);
            Objects.requireNonNull(action, "action");
            if (SUBMIT_EVENT_USE_CASE.equals(stage)) {
                if (eventUseCaseAccumulator.get() != null) {
                    throw new IllegalStateException("nested event use-case timing is invalid");
                }
                eventUseCaseAccumulator.set(new EventUseCaseAccumulator());
                synchronized (eventDeadlineDiagnosticLock) {
                    counter.entryCount.incrementAndGet();
                }
            } else {
                counter.entryCount.incrementAndGet();
            }
            long started = System.nanoTime();
            if (SUBMIT_EVENT_USE_CASE.equals(stage)) {
                eventUseCaseAccumulator.get().recordUseCaseStarted(started);
            }
            try {
                return action.get();
            } finally {
                long elapsed = Math.max(0L, System.nanoTime() - started);
                if (SUBMIT_EVENT_USE_CASE.equals(stage)) {
                    try {
                        requireEventCallPhaseState().recordGrpcDeadlineAtUseCaseCompletion(
                                Context.current().getDeadline());
                        recordCompletedEventUseCase(counter, elapsed);
                    } finally {
                        eventUseCaseAccumulator.remove();
                    }
                } else {
                    recordCompletion(counter, elapsed);
                    if (SUBMIT_EVENT_TRANSACTION_AUTHORITY_ADAPTER.equals(stage)) {
                        recordSubmitEventAuthorityAdapterComponent(elapsed);
                    }
                }
            }
        }

        private static void recordCompletion(BoundaryCounter counter, long elapsed) {
            counter.durationNanos.addAndGet(elapsed);
            counter.maximumDurationNanos.accumulateAndGet(elapsed, Math::max);
            counter.completionCount.incrementAndGet();
        }

        <T> T measureSubmitEventConsentPolicy(Supplier<T> action) {
            return measureSubmitEventComponent(
                    action,
                    maximumConsentPolicyDurationNanos,
                    EventCallPhaseState::recordConsentPolicy);
        }

        <T> T measureSubmitEventSourceKey(Supplier<T> action) {
            return measureSubmitEventComponent(
                    action,
                    maximumSourceKeyDurationNanos,
                    EventCallPhaseState::recordSourceKey);
        }

        <T> T measureSubmitEventApplicationBody(Supplier<T> action) {
            EventUseCaseAccumulator accumulator = eventUseCaseAccumulator.get();
            if (accumulator == null) {
                throw new IllegalStateException("event application-body timing is outside its use case");
            }
            Objects.requireNonNull(action, "action");
            long started = System.nanoTime();
            try {
                return action.get();
            } finally {
                long applicationBodyElapsed = Math.max(0L, System.nanoTime() - started);
                accumulator.recordApplicationBody(started, applicationBodyElapsed);
            }
        }

        void measureSubmitEventSourceEnvelope(Runnable action) {
            measureSubmitEventComponent(() -> {
                action.run();
                return Boolean.TRUE;
            }, maximumSourceEnvelopeDurationNanos, EventCallPhaseState::recordSourceEnvelope);
        }

        private <T> T measureSubmitEventComponent(
                Supplier<T> action,
                AtomicLong maximumDurationNanos,
                java.util.function.ObjLongConsumer<EventCallPhaseState> exactRecorder) {
            EventUseCaseAccumulator accumulator = eventUseCaseAccumulator.get();
            if (accumulator == null) {
                throw new IllegalStateException("event component timing is outside its use case");
            }
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(exactRecorder, "exactRecorder");
            long started = System.nanoTime();
            try {
                return action.get();
            } finally {
                long elapsed = Math.max(0L, System.nanoTime() - started);
                accumulator.addMeasuredComponent(elapsed);
                maximumDurationNanos.accumulateAndGet(elapsed, Math::max);
                exactRecorder.accept(requireEventCallPhaseState(), elapsed);
            }
        }

        private void recordCompletedEventUseCase(BoundaryCounter counter, long elapsed) {
            EventUseCaseAccumulator accumulator = eventUseCaseAccumulator.get();
            if (accumulator == null) {
                throw new IllegalStateException("event use-case timing state is invalid");
            }
            long measuredComponentSum = accumulator.measuredComponentSumNanos();
            long applicationBodyElapsed = accumulator.applicationBodyElapsedNanos();
            long targetMethodEntryPrefix = accumulator.targetMethodEntryPrefixNanos();
            long unmeasuredResidual = Math.max(0L, elapsed - measuredComponentSum);
            long applicationResidual = Math.max(0L, applicationBodyElapsed - measuredComponentSum);
            long transactionEnvelopeResidual = Math.max(0L, elapsed - applicationBodyElapsed);
            if (targetMethodEntryPrefix < 0L
                    || transactionEnvelopeResidual < targetMethodEntryPrefix) {
                throw new IllegalStateException("event transaction-envelope split ordering is invalid");
            }
            long targetMethodReturnSuffix = Math.subtractExact(
                    transactionEnvelopeResidual, targetMethodEntryPrefix);
            if (targetMethodReturnSuffix < 0L
                    || Math.addExact(targetMethodEntryPrefix, targetMethodReturnSuffix)
                    != transactionEnvelopeResidual) {
                throw new IllegalStateException("event transaction-envelope split is inconsistent");
            }
            EventCallPhaseState exactState = requireEventCallPhaseState();
            exactState.recordUseCase(elapsed);
            exactState.recordMeasuredComponentSum(measuredComponentSum);
            exactState.recordUnmeasuredResidual(unmeasuredResidual);
            exactState.recordApplicationResidual(applicationResidual);
            exactState.recordTransactionEnvelopeResidual(transactionEnvelopeResidual);
            exactState.recordTransactionEnvelopeTargetMethodEntryPrefix(targetMethodEntryPrefix);
            exactState.recordTransactionEnvelopeTargetMethodReturnSuffix(targetMethodReturnSuffix);
            synchronized (eventDeadlineDiagnosticLock) {
                recordCompletion(counter, elapsed);
                maximumMeasuredComponentSumNanos.accumulateAndGet(measuredComponentSum, Math::max);
                maximumUnmeasuredResidualNanos.accumulateAndGet(unmeasuredResidual, Math::max);
                maximumApplicationResidualNanos.accumulateAndGet(applicationResidual, Math::max);
                maximumTransactionEnvelopeResidualNanos.accumulateAndGet(
                        transactionEnvelopeResidual, Math::max);
            }
        }

        private void recordSubmitEventAuthorityAdapterComponent(long elapsed) {
            EventUseCaseAccumulator accumulator = eventUseCaseAccumulator.get();
            if (accumulator == null) {
                throw new IllegalStateException("event authority adapter timing is outside its use case");
            }
            accumulator.addMeasuredComponent(elapsed);
            requireEventCallPhaseState().recordAuthorityAdapterCall(elapsed);
        }

        void measure(String stage, Runnable action) {
            Objects.requireNonNull(action, "action");
            measure(stage, () -> {
                action.run();
                return Boolean.TRUE;
            });
        }

        void mark(String stage) {
            measure(stage, () -> {
            });
        }

        void clear() {
            for (String stage : STAGES) {
                BoundaryCounter counter = requireStage(stage);
                if (SUBMIT_EVENT_USE_CASE.equals(stage)) {
                    synchronized (eventDeadlineDiagnosticLock) {
                        counter.clear();
                    }
                } else {
                    counter.clear();
                }
            }
            maximumConsentPolicyDurationNanos.set(0L);
            maximumSourceKeyDurationNanos.set(0L);
            maximumSourceEnvelopeDurationNanos.set(0L);
            maximumMeasuredComponentSumNanos.set(0L);
            maximumUnmeasuredResidualNanos.set(0L);
            maximumApplicationResidualNanos.set(0L);
            maximumTransactionEnvelopeResidualNanos.set(0L);
        }

        private boolean resolveServiceMethodEntered() {
            return requireStage(RESOLVE_SERVICE_METHOD_ENTRY).entryCount.get() > 0L;
        }

        private boolean resolveUseCaseEntered() {
            return requireStage(RESOLVE_USE_CASE).entryCount.get() > 0L;
        }

        private boolean resolveAuthorityAdapterEntered() {
            return requireStage(RESOLVE_AUTHORITY_ADAPTER).entryCount.get() > 0L;
        }

        private boolean resolveAuthorityAdapterComplete() {
            BoundaryCounter counter = requireStage(RESOLVE_AUTHORITY_ADAPTER);
            return counter.entryCount.get() > 0L
                    && counter.completionCount.get() >= counter.entryCount.get();
        }

        private boolean submitEventUseCaseEntered() {
            return requireStage(SUBMIT_EVENT_USE_CASE).entryCount.get() > 0L;
        }

        private boolean submitEventUseCaseComplete() {
            BoundaryCounter counter = requireStage(SUBMIT_EVENT_USE_CASE);
            return counter.entryCount.get() > 0L
                    && counter.completionCount.get() >= counter.entryCount.get();
        }

        private boolean submitEventAuthorityAdapterEntered() {
            return requireStage(SUBMIT_EVENT_TRANSACTION_AUTHORITY_ADAPTER)
                    .entryCount.get() > 0L;
        }

        private boolean submitEventAuthorityAdapterComplete() {
            BoundaryCounter counter = requireStage(SUBMIT_EVENT_TRANSACTION_AUTHORITY_ADAPTER);
            return counter.entryCount.get() > 0L
                    && counter.completionCount.get() >= counter.entryCount.get();
        }

        private EventDeadlineDiagnosticSnapshot eventDeadlineDiagnosticSnapshot(
                ResolveTimingInterceptor timing) {
            Objects.requireNonNull(timing, "timing");
            if (eventDeadlineDiagnosticLock != timing.eventDeadlineDiagnosticLock) {
                throw new IllegalStateException("bounded event timing lock is invalid");
            }
            synchronized (eventDeadlineDiagnosticLock) {
                BoundaryCounter useCase = requireStage(SUBMIT_EVENT_USE_CASE);
                BoundaryCounter authorityAdapter =
                        requireStage(SUBMIT_EVENT_TRANSACTION_AUTHORITY_ADAPTER);
                return new EventDeadlineDiagnosticSnapshot(
                        useCase.maximumDurationNanos.get(),
                        authorityAdapter.maximumDurationNanos.get(),
                        maximumConsentPolicyDurationNanos.get(),
                        maximumSourceKeyDurationNanos.get(),
                        maximumSourceEnvelopeDurationNanos.get(),
                        maximumMeasuredComponentSumNanos.get(),
                        maximumUnmeasuredResidualNanos.get(),
                        maximumApplicationResidualNanos.get(),
                        maximumTransactionEnvelopeResidualNanos.get(),
                        timing.eventEntryCount.get(),
                        timing.eventSamples.size(),
                        timing.eventSamples.stream()
                                .mapToLong(Long::longValue)
                                .max()
                                .orElse(0L),
                        timing.eventCloseReturnSamples.size(),
                        timing.eventCloseReturnSamples.stream()
                                .mapToLong(Long::longValue)
                                .max()
                                .orElse(0L));
            }
        }

        String diagnostic() {
            var stages = new ArrayList<String>(STAGES.size());
            for (String stage : STAGES) {
                BoundaryCounter counter = requireStage(stage);
                long entries = counter.entryCount.get();
                long completions = counter.completionCount.get();
                if (entries < 0L || completions < 0L || completions > entries) {
                    throw new IllegalStateException("bounded diagnostic count is invalid");
                }
                double durationMillis = counter.durationNanos.get() / 1_000_000.0d;
                if (!Double.isFinite(durationMillis) || durationMillis < 0.0d) {
                    throw new IllegalStateException("bounded diagnostic duration is invalid");
                }
                String status = completions < entries ? "incomplete" : "complete";
                stages.add("{diagnostic_stage=" + stage
                        + ",diagnostic_status=" + status
                        + ",diagnostic_entry_count=" + entries
                        + ",diagnostic_completion_count=" + completions
                        + ",diagnostic_duration_ms=" + durationMillis + "}");
            }
            return "boundary_diagnostics" + stages;
        }

        private BoundaryCounter requireStage(String stage) {
            if (!STAGES.contains(stage)) {
                throw new IllegalArgumentException("unknown bounded diagnostic stage");
            }
            return counters.get(stage);
        }

        private static final class BoundaryCounter {
            private final AtomicLong entryCount = new AtomicLong();
            private final AtomicLong completionCount = new AtomicLong();
            private final AtomicLong durationNanos = new AtomicLong();
            private final AtomicLong maximumDurationNanos = new AtomicLong();

            private void clear() {
                entryCount.set(0L);
                completionCount.set(0L);
                durationNanos.set(0L);
                maximumDurationNanos.set(0L);
            }
        }

        private static final class EventUseCaseAccumulator {
            private long measuredComponentSumNanos;
            private long useCaseStartedNanos;
            private long applicationBodyElapsedNanos;
            private long targetMethodEntryPrefixNanos;
            private boolean useCaseStartedRecorded;
            private boolean applicationBodyRecorded;

            private void addMeasuredComponent(long elapsed) {
                measuredComponentSumNanos = Math.addExact(measuredComponentSumNanos, elapsed);
            }

            private void recordUseCaseStarted(long started) {
                if (useCaseStartedRecorded) {
                    throw new IllegalStateException("duplicate event use-case start timing is invalid");
                }
                useCaseStartedNanos = started;
                useCaseStartedRecorded = true;
            }

            private void recordApplicationBody(long applicationBodyStartedNanos, long elapsed) {
                if (applicationBodyRecorded || !useCaseStartedRecorded || elapsed < 0L) {
                    throw new IllegalStateException("event application-body timing is invalid");
                }
                long targetMethodEntryPrefix = Math.subtractExact(
                        applicationBodyStartedNanos, useCaseStartedNanos);
                if (targetMethodEntryPrefix < 0L) {
                    throw new IllegalStateException("event target-method entry prefix is invalid");
                }
                applicationBodyElapsedNanos = elapsed;
                targetMethodEntryPrefixNanos = targetMethodEntryPrefix;
                applicationBodyRecorded = true;
            }

            private long measuredComponentSumNanos() {
                return measuredComponentSumNanos;
            }

            private long applicationBodyElapsedNanos() {
                if (!applicationBodyRecorded) {
                    throw new IllegalStateException("missing event application-body timing is invalid");
                }
                return applicationBodyElapsedNanos;
            }

            private long targetMethodEntryPrefixNanos() {
                if (!applicationBodyRecorded) {
                    throw new IllegalStateException("missing event target-method entry prefix is invalid");
                }
                return targetMethodEntryPrefixNanos;
            }
        }
    }

    private static final class SingleUseGate {
        private final String name;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private SingleUseGate(String name) {
            this.name = name;
        }

        void claim() {
            if (!claimed.compareAndSet(false, true)) {
                throw new IllegalStateException("second " + name + " is prohibited");
            }
        }
    }

    private static final class LeakedProcess extends Process {
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return false; }
        @Override public int exitValue() { throw new IllegalThreadStateException(); }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return true; }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
