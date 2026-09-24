# NanoFaaS containerd Java Client — Specification

> Verbatim spec for the NanoFaaS containerd client library.

## 1. Main objective

Implement a Java client library that communicates directly with the **native
containerd gRPC API** through `/run/containerd/containerd.sock` (Unix Domain
Socket), with an ergonomics similar to `docker-java`, for use by **NanoFaaS**,
a lightweight Function-as-a-Service runtime written in Java.

The implementation MUST NOT:

- invoke the `ctr` CLI;
- invoke the `nerdctl` CLI;
- invoke shell commands to manage containers;
- use Docker or the Docker API;
- use Kubernetes;
- use the Kubernetes CRI API as the main abstraction;
- require a sidecar daemon or additional service.

The Java library must communicate **directly with containerd's native gRPC
API**. Before implementing anything, inspect the current containerd
API/protobuf definitions and make sure that the implementation matches the
target containerd version. Do not invent RPC methods or protobuf messages.

## 2. Target environment

- Linux
- containerd 2.x (pinned: v2.2.1, verified on this machine)
- crun as OCI runtime
- Java 21+
- Gradle
- grpc-java
- protobuf-java
- Netty (epoll native transport for Unix Domain Sockets)

Default socket `/run/containerd/containerd.sock`, configurable.

## 3. containerd concepts

Respect containerd's actual object model. A containerd `Container` stores
metadata/specification; a running container is represented by a `Task`.
Correctly orchestrate: `Image`, `Content`, `Snapshot`, `Container`, `Task`,
`Process`.

## 4. Namespaces

Configurable namespace (default `nanofaas`), propagated centrally via a gRPC
interceptor using the `containerd-namespace` metadata key.

## 5. Public Java API

Ergonomic, docker-java-like. No generated protobuf classes in the public API.
Example usage:

```java
try (ContainerdClient client = ContainerdClient.builder()
        .socketPath("/run/containerd/containerd.sock")
        .namespace("nanofaas")
        .build()) {

    client.images().pull("docker.io/library/alpine:latest");

    Container container = client.containers().create(
        ContainerSpec.builder()
            .id("test-1")
            .image("docker.io/library/alpine:latest")
            .command(List.of("/bin/sh", "-c", "while true; do sleep 10; done"))
            .build()
    );

    client.containers().start(container.id());
    ContainerStatus status = client.containers().inspect(container.id());
    client.containers().stop(container.id());
    client.containers().remove(container.id(),
        RemoveOptions.builder().removeSnapshot(true).build());
}
```

## 6. Initial feature set

Connection (connect/close/version/namespace), Images (list/get/pull/remove —
pull via the containerd Transfer API, never a CLI), Containers
(list/create/inspect/delete), Tasks (create/start/inspect/kill/stop/delete),
exec with exit code + stdout + stderr, events with filtering, task IO via
FIFO handling where containerd requires it.

## 7. OCI specification

Generate a valid OCI runtime spec (command/args, env, cwd, hostname, user,
read-only rootfs, mounts, Linux namespaces, resource limits: CPU, memory,
PIDs). Clean abstraction — no hand-rolled opaque `Any` payloads without a
dedicated builder.

## 8. Snapshotters

Configurable snapshotter (default `overlayfs`). Explicit snapshot lifecycle
(prepare/mount/remove); no leaked snapshots on failure.

## 9. Runtime selection

containerd launches the OCI runtime (crun). Runtime id configurable, default
`io.containerd.runc.v2`. Never invoke `crun` directly. Document assumptions.

## 10. Architecture

Modular: `ContainerdClient` + `ImageService`/`ContainerService`/`TaskService`/
`EventService` + internal (`GrpcChannelFactory`, `NamespaceInterceptor`,
`ProtoMapper`, `OciSpecBuilder`, `SnapshotManager`, `IoManager`). Clear
separation public API / protobuf, testability, resource safety, thread
safety, minimal hidden state. `ContainerdClient implements AutoCloseable`.

## 11. Error handling

Typed exceptions (ContainerdException, ContainerNotFoundException,
ImageNotFoundException, ContainerAlreadyExistsException,
TaskNotFoundException, ImagePullException, ContainerStartException,
ContainerStopException), preserving the original gRPC status/cause. Map
common gRPC status codes.

## 12. Concurrency

Shared gRPC stubs/channel, thread-safe services, async APIs internally where
useful (sync public API now; async later). No new channel per operation, no
OS thread per container.

## 13. Failure handling

Transactional lifecycle where reasonably possible (e.g. snapshot +
container created, task create fails → clean up). Document cleanup
guarantees. Careful with tasks, snapshots, FIFOs, temp dirs, gRPC streams.

## 14. Idempotency

stop/remove/cleanup have documented idempotency semantics.

## 15. Observability

SLF4J (no concrete backend). TRACE/DEBUG/INFO/WARN/ERROR levels; no noisy
INFO per operation; DEBUG for lifecycle diagnosis.

## 16. Dependencies

Minimal: grpc-netty, grpc-protobuf, grpc-stub, protobuf-java, slf4j-api,
JUnit 5, AssertJ, plus what is genuinely necessary. No Spring/K8s/docker-java.

## 17. Protobuf handling

Official containerd `.proto` definitions, vendored from a pinned version,
generated during the Gradle build. Document the upgrade procedure.

## 18. Testing

Unit tests (mapping, OCI spec generation, namespace metadata, exception
mapping, resource cleanup) + integration tests (`@Tag("integration")`)
against real containerd: pull alpine, create/start/stop/delete, exec,
failure scenarios, cleanup of all resources, unique identifiers, no Docker
assumption.

## 19. Example application

`Example.java`: connect → print version → pull alpine → create → start →
exec `echo "hello from containerd"` → print result → stop → delete →
cleanup snapshot.

## 20. Gradle

`build.gradle.kts`, Java 21 toolchain, proto generation, `./gradlew clean
build` works, `./gradlew integrationTest` separate.

## 21. Documentation

README: purpose, requirements, architecture, installation, usage, containerd
configuration, crun configuration assumptions, namespace/snapshotter
configuration, limitations, integration test instructions, and the
image/content/snapshot/container/task/process conceptual distinction.

## 22. Development strategy

Work incrementally. Inspect the repository, determine the target containerd
version, inspect its official protobuf API, identify required services/RPCs,
determine the UDS strategy, determine the image pull/unpack mechanism,
determine OCI spec encoding, propose the architecture, then implement in
small compilable increments, running `./gradlew test` after each.

## 23. Anti-hallucination rule

Never infer the API from memory when the source can be inspected. For every
operation, verify the actual service/RPC/message/semantics for the target
version. If an operation does not exist as a single RPC, implement the
necessary sequence instead. In particular there is **no** Docker-like
`PullImage` RPC.

## 24. Scope prioritization for NanoFaaS

Rapid lifecycle of short-lived function containers: low startup overhead,
low latency, high concurrency, predictable cleanup, minimal memory, minimal
dependencies. Keep lower-level APIs accessible internally so NanoFaaS can
later pre-create snapshots/containers and rapidly create/start tasks.

## 25. Deliverables

README.md, build.gradle.kts, settings.gradle.kts, src/main/java, src/main/
proto, src/test/java, src/integrationTest/java, Example.java, and a final
report on: architecture, containerd APIs used, lifecycle sequences, image
pull strategy, snapshot strategy, OCI spec strategy, concurrency model,
limitations, version-specific assumptions, NanoFaaS optimizations.
