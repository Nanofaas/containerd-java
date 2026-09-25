package io.nanofaas.containerd.sonar;

import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.ExecResult;
import io.nanofaas.containerd.RemoveOptions;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.UUID;

/**
 * Brings up PostgreSQL and SonarQube with this library, runs the scanner against a project, and
 * takes it all down again.
 *
 * <p>Everything runs on the host's network. SonarQube must be reachable on a port from the host
 * and must reach its database, and this library has no CNI and so no private addressing or port
 * mapping — host networking is the only way to wire the three together. That is also why the
 * ports are checked before anything starts: on the host's stack a port already in use is a
 * conflict, and the failure it produces otherwise is unrecognisable.
 *
 * <p>Everything created is registered for teardown as it is created, so an interrupted or failed
 * run removes what it made rather than leaving containers and snapshots behind.
 */
final class SonarStack implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SonarStack.class);

    static final String POSTGRES_IMAGE = "docker.io/library/postgres:16-alpine";
    static final String SONARQUBE_IMAGE = "docker.io/library/sonarqube:community";

    static final int POSTGRES_PORT = 5432;
    static final int SONARQUBE_PORT = 9000;
    private static final String DB_NAME = "sonar";
    private static final String DB_USER = "sonar";
    private static final String DB_PASSWORD = "sonar";

    /**
     * uid:gid of the {@code sonarqube} account inside the image. The image declares the account by
     * name, which the OCI runtime spec cannot express, and SonarQube's embedded Elasticsearch
     * refuses to run as root — so the numeric id has to be supplied here.
     */
    private static final String SONARQUBE_USER = "1000:0";

    private final ContainerdClient client;
    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    /** Where the containers' output is captured, so a failure to start can explain itself. */
    private final Path logDirectory;
    /** Undone in reverse order, so the database outlives what depends on it. */
    private final Deque<Runnable> teardown = new ArrayDeque<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    /** Set once a token exists; most of the API answers nothing useful without one. */
    private volatile String token;

    SonarStack(ContainerdClient client) {
        this.client = client;
        this.logDirectory = Path.of(System.getProperty("java.io.tmpdir"), "containerd-java-sonar-logs");
    }

    /** Authenticates later reads. Only /api/system/status answers without this. */
    void authenticateWith(String token) {
        this.token = token;
    }

    String sonarQubeUrl() {
        return "http://127.0.0.1:" + SONARQUBE_PORT;
    }

    /** Fails early and legibly if a port this stack needs is already taken on the host. */
    static void requirePortsFree() {
        for (int port : new int[]{POSTGRES_PORT, SONARQUBE_PORT}) {
            try (ServerSocket probe = new ServerSocket(port)) {
                probe.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException("port " + port + " is already in use on this host."
                        + " These containers share the host's network stack, so the port must be free."
                        + " Stop whatever is listening, then run again.", e);
            }
        }
    }

    void pullImages() {
        for (String image : List.of(POSTGRES_IMAGE, SONARQUBE_IMAGE)) {
            log.info("pulling {}", image);
            client.images().pull(image);
        }
    }

    void startPostgres() {
        String id = "sonar-db-" + runId;
        log.info("starting postgres as {}", id);
        // No command: the image's entrypoint initialises the cluster and starts the server.
        create(ContainerSpec.builder().id(id).image(POSTGRES_IMAGE)
                .hostNetwork(true)
                .logDirectory(logDirectory)
                .environment(Map.of(
                        "POSTGRES_DB", DB_NAME,
                        "POSTGRES_USER", DB_USER,
                        "POSTGRES_PASSWORD", DB_PASSWORD))
                .build());
        client.containers().start(id);

        try {
            awaitPostgres(id);
        } catch (RuntimeException e) {
            throw new IllegalStateException("postgres did not come up. Its output was:\n"
                    + tail(id, 40), e);
        }
        log.info("postgres is accepting connections");
    }

    void startSonarQube() {
        String id = "sonar-server-" + runId;
        log.info("starting sonarqube as {} (this takes a couple of minutes on first start)", id);
        create(ContainerSpec.builder().id(id).image(SONARQUBE_IMAGE)
                .hostNetwork(true)
                .logDirectory(logDirectory)
                .user(SONARQUBE_USER)
                // Elasticsearch enforces this as a bootstrap check and refuses to start below it.
                // The library's default of 1024 leaves SonarQube dying at startup.
                .openFilesLimit(65536)
                .environment(Map.of(
                        "SONAR_JDBC_URL", "jdbc:postgresql://127.0.0.1:" + POSTGRES_PORT + "/" + DB_NAME,
                        "SONAR_JDBC_USERNAME", DB_USER,
                        "SONAR_JDBC_PASSWORD", DB_PASSWORD))
                .build());
        client.containers().start(id);

        try {
            awaitSonarQube();
        } catch (RuntimeException e) {
            // The reason is in the container's own output. Reporting only the timeout is what made
            // the first attempt at this take a rerun under ctr to discover a file-descriptor limit.
            throw new IllegalStateException("sonarqube did not come up. Its output was:\n"
                    + tail(id, 40), e);
        }
        log.info("sonarqube is up at {}", sonarQubeUrl());
    }

    /**
     * Runs the analysis with the Gradle SonarQube plugin, as a nested build.
     *
     * <p>Not a container, unlike the server and its database: the sonar-scanner-cli image is
     * published for amd64 only and cannot run on an arm64 host. The plugin also derives the source
     * sets, compiled classes and test reports from the build itself, which the CLI has to be told
     * about by hand and which drifts the moment the build changes.
     *
     * @param project the project directory, which is also the Gradle build to analyse
     * @param token an analysis token for the SonarQube server
     * @param projectKey the key to publish the results under
     * @return the nested build's exit code
     */
    int runAnalysis(Path project, String token, String projectKey) {
        log.info("analysing {} with the Gradle sonar task", project);
        List<String> command = List.of("./gradlew", "--no-daemon", "sonar",
                "-Dsonar.host.url=" + sonarQubeUrl(),
                "-Dsonar.token=" + token,
                "-Dsonar.projectKey=" + projectKey);
        try {
            Process gradle = new ProcessBuilder(command)
                    .directory(project.toFile())
                    .inheritIO()
                    .start();
            return gradle.waitFor();
        } catch (IOException e) {
            throw new IllegalStateException("could not start " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while analysing", e);
        }
    }

    /**
     * The id of the analysis SonarQube has most recently processed, or null if it has processed
     * none. Taken before submitting a report so {@link #awaitComputeEngine} can tell the new
     * result from the old one.
     *
     * @param projectKey the project to look at
     * @return the current analysis id, or null
     */
    String lastAnalysisId(String projectKey) {
        String body = get("/api/ce/component?component=" + projectKey);
        Matcher id = ANALYSIS_ID.matcher(body == null ? "" : body);
        return id.find() ? id.group(1) : null;
    }

    private static final java.util.regex.Pattern ANALYSIS_ID =
            java.util.regex.Pattern.compile("\"analysisId\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Waits for SonarQube to finish processing the report just uploaded.
     *
     * <p>The scanner returns as soon as the report is uploaded, but nothing is queryable until the
     * Compute Engine has processed it — asking straight away reads either nothing or the previous
     * run's results.
     *
     * <p>Waiting for "a SUCCESS" is not enough on a server that has analysed this project before:
     * the previous run's SUCCESS is already sitting there and satisfies the check immediately,
     * which returns stale numbers that look perfectly plausible. Hence {@code previousAnalysisId}:
     * the wait ends only once the queue has drained and the reported analysis is a different one.
     *
     * @param projectKey the analysed project
     * @param previousAnalysisId what {@link #lastAnalysisId} returned before the analysis ran
     */
    void awaitComputeEngine(String projectKey, String previousAnalysisId) {
        await("the compute engine", Duration.ofMinutes(5), () -> {
            String body = get("/api/ce/component?component=" + projectKey);
            if (body == null) {
                return false;
            }
            if (body.contains("\"status\":\"FAILED\"") || body.contains("\"status\":\"CANCELED\"")) {
                throw new IllegalStateException("SonarQube failed to process the report: " + body);
            }
            if (!body.contains("\"queue\":[]") || !body.contains("\"status\":\"SUCCESS\"")) {
                return false;
            }
            Matcher id = ANALYSIS_ID.matcher(body);
            String current = id.find() ? id.group(1) : null;
            return current != null && !current.equals(previousAnalysisId);
        });
    }

    /** The last {@code lines} lines a container wrote, or a note saying why they are unavailable. */
    private String tail(String id, int lines) {
        try {
            List<String> all = client.containers().logs(id).lines().toList();
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (RuntimeException e) {
            return "(could not read the container's log: " + e.getMessage() + ")";
        }
    }

    private void create(ContainerSpec spec) {
        client.containers().create(spec);
        // Registered before start: a container that fails to start still has a snapshot to remove.
        teardown.push(() -> remove(spec.id()));
    }

    private void remove(String id) {
        try {
            log.info("removing {}", id);
            client.containers().remove(id, RemoveOptions.builder()
                    .removeSnapshot(true).force(true).build());
        } catch (RuntimeException e) {
            log.warn("could not remove {}: {}", id, e.getMessage());
        }
    }

    private void awaitPostgres(String id) {
        await("postgres", Duration.ofMinutes(2), () -> {
            ExecResult ready = client.containers().exec(id,
                    List.of("/bin/sh", "-c", "pg_isready -U " + DB_USER + " 2>&1 || true"));
            return ready.stdout().contains("accepting connections");
        });
    }

    private void awaitSonarQube() {
        await("sonarqube", Duration.ofMinutes(10), () -> {
            String status = get("/api/system/status");
            if (status == null) {
                return false;
            }
            if (status.contains("\"status\":\"UP\"")) {
                return true;
            }
            log.debug("sonarqube not ready yet: {}", status);
            return false;
        });
    }

    /** Polls until {@code condition} holds, failing with a message that names what was waited on. */
    private static void await(String what, Duration limit, java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(limit);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("still waiting for {}: {}", what, e.toString());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + what, e);
            }
        }
        throw new IllegalStateException(what + " did not become ready within " + limit);
    }

    /**
     * GETs a SonarQube API path, returning null when the server is not answering yet.
     *
     * <p>Sends the analysis token once there is one. Without it everything but
     * {@code /api/system/status} answers 401 with an empty body — which polls as "not ready yet"
     * and looks exactly like a slow server until the timeout expires.
     */
    String get(String path) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(sonarQubeUrl() + path))
                    .timeout(Duration.ofSeconds(10)).GET();
            String current = token;
            if (current != null) {
                // SonarQube takes a token as the basic-auth username, with an empty password.
                request.header("Authorization", "Basic " + java.util.Base64.getEncoder()
                        .encodeToString((current + ":").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("GET {} -> {} (not authenticated)", path, response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** A SonarQube API response: the status matters as much as the body, which is often empty. */
    record ApiResponse(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return status + (body == null || body.isBlank() ? " (empty body)" : " " + body);
        }
    }

    /** POSTs a form to a SonarQube API path with basic auth. */
    ApiResponse post(String path, String form, String user, String password) {
        try {
            String credentials = java.util.Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(sonarQubeUrl() + path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Basic " + credentials)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            log.debug("POST {} -> {}", path, response.statusCode());
            return new ApiResponse(response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("POST " + path + " failed", e);
        }
    }

    /** Removes every container this tool creates, whichever run left them behind. */
    static void removeLeftovers(ContainerdClient client) {
        client.containers().list().stream()
                .map(io.nanofaas.containerd.Container::id)
                .filter(id -> id.startsWith("sonar-"))
                .forEach(id -> {
                    log.info("removing leftover {}", id);
                    try {
                        client.containers().remove(id, RemoveOptions.builder()
                                .removeSnapshot(true).force(true).build());
                    } catch (RuntimeException e) {
                        log.warn("could not remove {}: {}", id, e.getMessage());
                    }
                });
    }

    @Override
    public void close() {
        while (!teardown.isEmpty()) {
            teardown.pop().run();
        }
    }
}
