# containerd-java

[![build](https://github.com/miciav/containerd-java/actions/workflows/build.yml/badge.svg)](https://github.com/miciav/containerd-java/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A Java client library that talks **directly to containerd's native gRPC API** over a Unix
Domain Socket — no `ctr`, no `nerdctl`, no Docker, no Kubernetes CRI, no sidecar daemon. Built
for [NanoFaaS](https://github.com/), a lightweight Function-as-a-Service runtime, but usable
anywhere a JVM process needs to drive containerd directly.

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
            .build());

    client.containers().start(container.id());
    ContainerStatus status = client.containers().inspect(container.id());
    client.containers().stop(container.id());
    client.containers().remove(container.id(),
        RemoveOptions.builder().removeSnapshot(true).build());
}
```

## Requirements

- Linux (the UDS transport is Netty epoll; no other OS is supported)
- containerd 2.x — developed and tested against **v2.2.1** on both `linux/amd64` and
  `linux/arm64`
- Java 22+ (the Foreign Function and Memory API's floor; the build itself uses a 25 toolchain
  and targets 22 bytecode)
- `crun` or `runc` as the OCI runtime, launched by containerd's `io.containerd.runc.v2` shim
- Gradle (wrapper included, no local Gradle install needed)

## Architecture

```
ContainerdClient  (public entry point, AutoCloseable, one shared gRPC channel)
   │
   ├── Images       pull / get / list / remove          (io.nanofaas.containerd.spi)
   ├── Containers   create / inspect / list / remove /
   │                start / stop / kill / wait / exec
   ├── Tasks        low-level task ops (NanoFaaS fast path)
   └── Events       subscribe(filter, handler) → Subscription
   │
   ▼ (internal — never exposed in public signatures)
   GrpcChannelFactory     Netty-epoll channel over the UDS
   NamespaceInterceptor   stamps every call with the `containerd-namespace` header
   ProtoMapper            proto ⇄ public record conversions
   OciSpecBuilder         builds the OCI runtime spec as JSON (see below)
   SnapshotManager        prepare / mounts / remove, per snapshotter
   ImageRootfsResolver    manifest → config → diff_ids → ChainID
   IoManager              FIFO create/read/write for exec IO, on virtual threads
   TransferImagePuller    drives the Transfer service for pulls
   EventMapper            envelope → typed Event/TaskEvent
```

Generated protobuf/gRPC classes (`containerd.services.*`, `containerd.types.*`) are vendored
from the containerd v2.2.1 source tree under `src/main/proto` and never appear in a public
method signature — only `io.nanofaas.containerd` and `io.nanofaas.containerd.spi` types do.

containerd's own object model is preserved rather than flattened into Docker semantics: a
`Container` is metadata (spec, snapshot key, image); a running instance is a separate `Task`;
an `exec` inside a task is a `Process`. See [Conceptual glossary](#conceptual-glossary).

## Installation

```bash
./gradlew build          # compiles, runs unit tests, produces build/libs/containerd-java-0.3.0.jar
./gradlew publishToMavenLocal   # if you add the maven-publish plugin for local consumption
```

`build` also produces `-sources.jar` and `-javadoc.jar` alongside the main artifact.

Gradle coordinates (once published): `io.nanofaas:containerd-java:0.3.0`.

Changes between releases are listed in [CHANGELOG.md](CHANGELOG.md). Note that 0.3.0 requires Java 22 and carries other breaking
changes; 0.2.0 did too.

## Basic usage

### Connect, pull, create, start, inspect, stop, remove

See the example at the top of this file, or run it directly:

```bash
./gradlew run   # runs src/main/java/io/nanofaas/containerd/example/Example.java
```

### Exec — capture stdout/stderr/exit code

```java
ExecResult result = client.containers().exec(id,
        List.of("/bin/sh", "-c", "echo hello; echo error >&2; exit 3"));
result.exitCode();  // 3
result.stdout();    // "hello\n"
result.stderr();    // "error\n"
```

Requires the container's task to already be `RUNNING`; internally, IO travels over FIFOs
(`jnr-posix` `mkfifo`, no shell) read on JDK 21 virtual threads.

An exec runs under the same privilege rules as the container's entrypoint: `no_new_privs` is
off, as it is by default in docker and `ctr`, so setuid binaries behave the same way whichever
way the command is started.

### Events

```java
Subscription sub = client.events().subscribe(
        EventFilter.topics("/tasks/start", "/tasks/exit"),
        event -> System.out.println(event.topic() + " " + event.taskEvent()));
// ... later
sub.close();
```

Use `EventFilter.all()` for every topic in the namespace — note that `EventFilter.topics()`
with no argument does not compile, because it resolves against the instance getter of the same
name.

The handler runs on a virtual thread per event, so a slow consumer never blocks stream
delivery. A dropped stream auto-reconnects with exponential backoff (1s, doubling, capped at
30s), until `close()` is called. `close()` cancels the underlying gRPC call, and closing the
client cancels any subscription still open.

## containerd configuration

- Default socket: `/run/containerd/containerd.sock` (configurable via
  `ContainerdClientBuilder.socketPath(...)`). The socket is typically root-owned; either run
  the JVM as root, or add the running user to the group that owns the socket
  (`containerd.toml`'s `grpc.gid`).
- Default runtime id: `io.containerd.runc.v2` (the standard v2 shim). Configurable via
  `.runtimeName(...)`.
- To run under **crun** instead of `runc`, either:
  - set `.runtimeBinaryName("crun")` on the client builder — this library passes it as the
    runc-v2 shim's `binary_name` option (`containerd.runc.v1.Options`, proto-encoded `Any` on
    `CreateTaskRequest.options`); or
  - alias a runtime in containerd's `config.toml`:
    ```toml
    [plugins."io.containerd.grpc.v1.cri".containerd.runtimes.crun]
      runtime_type = "io.containerd.runc.v2"
      [plugins."io.containerd.grpc.v1.cri".containerd.runtimes.crun.options]
        BinaryName = "crun"
    ```
    and set `.runtimeName("crun")`.

## crun configuration assumptions

The library never invokes `crun` or `runc` directly — it only ever talks to containerd, which
launches the OCI runtime itself via the shim. It assumes the shim binary
(`containerd-shim-runc-v2`) is on containerd's `PATH` and that the configured runtime binary
(`runc` by default, or `crun` via `runtimeBinaryName`) is resolvable by that shim.

## Namespace configuration

Every gRPC call carries the `containerd-namespace` metadata header, injected centrally by
`NamespaceInterceptor` at the channel level (so `version()` and every facade share it — no call
site can forget it). Default namespace: `nanofaas`. containerd auto-creates namespaces on first
use; there is no explicit "create namespace" step.

```java
ContainerdClient.builder().namespace("my-namespace").build();
```

## Snapshotter configuration

`stop()` sends SIGTERM, waits, then SIGKILL. The grace period is 10s by default and
configurable via `.stopTimeout(Duration)` on the client builder.

Container ids follow containerd's own rule: alphanumeric runs joined by single `.`, `_` or `-`
separators, at most 76 characters — so `my-fn-01` is valid, `-fn`, `fn-` and `a..b` are not.

Default snapshotter: `overlayfs`. Configurable via `.snapshotter(...)`. Each container gets an
**active** snapshot keyed by its container id, parented on the image's top ChainID
(`opencontainers/image-spec` ChainID algorithm, computed client-side from the image's
`rootfs.diff_ids`). A `containerd.io/gc.ref.snapshot.<snapshotter>` label is set on the
container so containerd's garbage collector never reclaims a snapshot that's still in use.
Snapshot removal is explicit (`RemoveOptions.removeSnapshot(true)`) and idempotent.

## Limitations

- **Linux only** — the UDS transport requires Netty's epoll native library.
- **Anonymous registry pulls only** — no registry authentication/credentials customization yet
  (the Transfer API's `OCIRegistry.resolver` supports auth, but this library doesn't expose it).
- **No checkpoint/restore.**
- **No `Tasks.Update`** (live resource-limit changes on a running task).
- **No TTY/PTY support** — `exec`/task IO is FIFO-only, `terminal` is always `false`.
- **Container output is one combined stream.** `Containers.logs(id)` returns stdout and stderr
  interleaved, and only for containers created with `ContainerSpec.logDirectory(...)`: containerd
  discards a task's output unless told where to send it before the task starts, and it writes both
  streams to a single destination.
- `exec` requires the container's task to already be `RUNNING`.
- **`ContainerSpec.user` must be `"uid:gid"`** — a bare username cannot be honoured, because the
  OCI runtime spec's `process.user` carries uid/gid only and has no field for a name. A username
  is ignored and the process runs as uid 0.
- Event topic filtering is applied **client-side**: this containerd's server-side fieldpath
  filter rejects multi-clause combinations (see Design Notes, R25) and falls back to an
  unfiltered stream, so only the namespace scoping filter is sent to the server and topics are
  matched in the client.

## Integration tests

Integration tests are tagged `@Tag("integration")`, live under `src/integrationTest`, run
against a **real** containerd (they pull `docker.io/library/alpine:latest`, so networking and
registry access are required), and are **not** part of `./gradlew build`/`check`. CI runs them as a separate job against
containerd 2.2.1 installed in the runner:

```bash
sudo ./gradlew integrationTest
# or, if your user has socket access (e.g. member of containerd's socket group):
./gradlew integrationTest
# to point at a non-default socket:
./gradlew integrationTest -Dio.nanofaas.containerd.socket=/custom/path/containerd.sock
```

Each integration test class extends `ContainerdConnectionIT`, whose `@BeforeAll` uses JUnit
`Assumptions` to **skip** (not fail) when the socket is missing or unreadable. Because that would
let a CI job pass having run nothing, the workflow fails the build if any integration test skips
or if none ran. Every test uses a
unique id (`it-<name>-` + UUID) and cleans up its own containers/snapshots in `finally`/`@AfterEach`.

## End-to-end scenario

Integration tests run against whatever containerd the developer's machine already has. That
leaves the more interesting half of this library's claims untested: rootless containers, CNI
attachment into RootlessKit's network namespace, and a runtime that is crun rather than runc.
`e2e/containerd_java_e2e.py` covers them by creating the machine it needs, on a machine that has
nothing on it yet.

It is a [sonata-engine](https://github.com/miciav/sonata) workflow with five tasks around one
resource: build the demo from the working tree, acquire a Multipass VM, install containerd and
crun to run rootless in it, define a CNI network that declares DNS, deploy the demo, run it, and
release the VM. The VM is a `Resource`, so it is torn down on the failure path too — the case
that would otherwise leave a machine running.

```bash
uv venv .venv-e2e && uv pip install --python .venv-e2e \
    /path/to/sonata "/path/to/sonata/packages/sonata-tasks[multipass]"
.venv-e2e/bin/python e2e/containerd_java_e2e.py
```

[docs/end-to-end.md](docs/end-to-end.md) covers the rest: what each unit does, the environment
variables, how to keep the VM for a
second look and clean it up afterwards, how to run the demo on its own against a local containerd,
and the obstacles — AppArmor and unprivileged user namespaces, `user.home` inside them — that
anyone reproducing this by hand will meet.

The demo it runs is `src/e2e`, a plain application built on the two libraries: it pulls an image,
starts a pair of containers on a shared network and has one reach the other by name, reads logs,
execs (with stdin, and checking what the exec inherits), applies limits, watches events, uses the
host's network, and checks that nothing is left behind. It reports which scenarios held rather
than asserting, so one failure does not hide the rest, and each runs under a time limit — without
one a hang stops the whole run with no report at all.

This is what found the exec faults fixed in `68a51b7`: the unit tests run against a fake shim,
which behaves differently from a real one in exactly the places that mattered.

## Conceptual glossary

containerd's object model does not map one-to-one onto Docker's. Understanding these five
concepts is the fastest way to reason about this library correctly:

- **Image** — a manifest (+ optional index for multi-platform images) plus a config blob,
  referencing layer blobs by digest. Pulling an image populates the content store and creates
  an `Image` record; it does **not** by itself create any running state.
- **Content** — the blob store, addressed by digest (`sha256:...`). Manifests, configs, and
  layers all live here, read via the `Content` service.
- **Snapshot** — filesystem state for a container's root filesystem, managed by a
  *snapshotter* (default `overlayfs`). A snapshot is `ACTIVE` (mutable, in use by a container)
  or `COMMITTED` (immutable, e.g. an image layer); active snapshots are addressed by a key and
  parented on a `ChainID` computed from the image's layer diff IDs.
- **Container** — metadata only: an id, an OCI runtime spec, a snapshotter + snapshot key, an
  image reference, labels. Creating a container does **not** start anything.
- **Task** — the running instance of a container: an OS process tree launched from the
  container's spec and snapshot mounts by the OCI runtime. Starting/stopping/killing operates
  on the task, not the container.
- **Process** — the task's init process, or an `exec`'d process inside it (tracked by an
  `exec_id`); both have a pid and, once exited, an exit status.

---

## Design Notes

1. **Architecture implemented:** layered client (`ContainerdClient` → `Images`/`Containers`/
   `Tasks`/`Events` facades → generated gRPC stubs) over a single shared Netty-epoll channel on
   the UDS; internal `GrpcChannelFactory`, `NamespaceInterceptor`, `ProtoMapper`,
   `OciSpecBuilder`, `SnapshotManager`, `IoManager`, `TransferImagePuller`,
   `ImageRootfsResolver`, `EventMapper`.
2. **containerd APIs used:** `version.v1.Version` (health), `containers.v1.Containers`
   (create/get/list/delete), `tasks.v1.Tasks` (create/start/kill/wait/delete/exec/delete-process),
   `snapshots.v1.Snapshots` (prepare/mounts/remove), `images.v1.Images` (get/list/delete),
   `transfer.v1.Transfer` (pull), `content.v1.Content` (read manifest/config),
   `events.v1.Events` (subscribe). There is no `PullImage` RPC — pull is implemented as
   `Transfer` with an `OCIRegistry` source and an `ImageStore` destination, exactly what
   `ctr images pull` does.
3. **Lifecycle create/start/stop/delete:** create = resolve image ChainID (manifest → config →
   `diff_ids` → ChainID) → `Snapshots.Prepare(key=id, parent=chainID)` → spec `Any` (JSON, type
   URL `types.containerd.io/opencontainers/runtime-spec/1/Spec`) → `Containers.Create` (+ GC
   label); on failure, best-effort snapshot remove. start = `Tasks.Create(rootfs=snapshot
   mounts)` → `Tasks.Start`. stop = SIGTERM → wait(10s) → SIGKILL → wait → task delete
   (idempotent). remove = task delete (if `force`) → `Containers.Delete` → snapshot remove
   (idempotent).
4. **Image pull strategy:** the containerd Transfer service, `OCIRegistry` source +
   `ImageStore` destination (platform-scoped, `unpacks` into the configured snapshotter,
   `all_metadata=true`) — no CLI shelled out, no separate pull-then-unpack step to coordinate.
5. **Snapshot strategy:** snapshotter configurable (default `overlayfs`); containers get an
   active snapshot keyed by container id, parented on the image's top ChainID;
   `containerd.io/gc.ref.snapshot.<snapshotter>` label protects it from GC; removal is explicit
   and idempotent.
6. **OCI spec strategy:** JSON spec (field names from `opencontainers/runtime-spec`) carried in
   a `google.protobuf.Any` with containerd's registered type URL, built by a dedicated
   `OciSpecBuilder` — no hand-rolled opaque byte payloads. Resource limits (CPU shares/quota/
   period, memory limit/swap, pids) live under `linux.resources`.
7. **Concurrency model:** one shared `ManagedChannel` + thread-safe blocking stubs per client;
   no thread per container. FIFO IO for exec (`IoManager`) and event handler dispatch
   (`EventsServiceImpl`) both run on virtual threads; event streams auto-reconnect with
   exponential backoff.
8. **Known limitations:** Linux-only; anonymous registry pulls (no auth customization yet); no
   checkpoint/restore, TTY, or `Tasks.Update`; `exec` requires a running task; event topic
   filtering is client-side (see R25 below).
9. **containerd-version-specific assumptions:** API pinned to v2.2.1 (protos vendored under
   `src/main/proto`, upgrade = replace files + bump the `containerdApiVersion` property in
   `build.gradle.kts`); runtime-spec 1.x type URLs; `io.containerd.runc.v2` shim; spec travels
   as JSON (not proto) inside the `Any` — verified against the containerd Go source, not
   assumed from memory; `ociVersion` emitted as `1.2.0`; default platform is the **host**
   platform (not hardcoded `linux/amd64` — this was corrected in Task 10, see R21: an x86_64
   default silently pulled the wrong rootfs on this aarch64 development machine and every task
   died with `exec format error`).
10. **NanoFaaS optimizations possible next:** pre-pull images once and prepare snapshots ahead
    of time (`Snapshots.Prepare` is cheap metadata work; task create just reuses the active
    snapshot's mounts via `SnapshotManager.mounts`); pre-create containers (metadata + spec) and
    only create/start tasks per invocation; reuse a warmed exec path; cache resolved ChainIDs to
    skip `Images.Get` on the hot path; use `View` (read-only) snapshots for function rootfs that
    never needs to be written to.

### Notable implementation findings (post-plan corrections)

The implementation plan (`docs/superpowers/plans/2026-09-02-containerd-java-client.md`) records
every place the verified containerd behavior diverged from the plan's initial code sketch, as
`R1`–`R26` notes per task. The ones most likely to surprise a new contributor:

- **R21 (Task 10):** the default `Platform` is the **host** platform, not a hardcoded
  `linux/amd64` — pulling an amd64 image on an arm64 host (or vice versa) silently produces a
  container whose init process fails with `exec format error`.
- **R22 (Task 10):** the OCI spec JSON must serialize integral fields as bare integers, not
  `1024.0` — `protobuf-java-util`'s `Struct`/`Value` model has no integer type, so
  `JsonSupport.print` post-processes integral doubles.
- **R23/R24 (Task 11):** reading a FIFO cannot use `InputStream.readAllBytes()` (it seeks to
  size the read; pipes don't support seeking), and unblocking a pending exec-IO reader must only
  happen for readers still stuck in `open(2)` — unblocking a reader that already hit EOF blocks
  forever waiting for a reader that will never arrive.
- **R25 (Task 12):** this containerd's event fieldpath filter parser rejects multi-clause
  filters and any unquoted value containing `/`, silently falling back to an unfiltered stream
  rather than erroring — confirmed with a raw-stub probe of eight filter variants before
  settling on server-side namespace scoping + client-side topic matching.

See the plan file for the full list and the verification each finding is based on.

## Snapshot ownership

A container's snapshot is prepared before the container record that will own it exists. That gap
is held by a containerd lease, so a process killed inside it leaves a snapshot containerd will
collect when the lease expires rather than one that lives forever — an unreferenced snapshot is
not collected on its own, which was verified rather than assumed. The lease is released as soon as
the container's own GC reference takes over.

## Networking

By default a container gets a network namespace this library leaves empty: no addresses, no
routes, nothing reachable. `hostNetwork(true)` shares the host's stack instead. For a real network
of its own, `containerd-java-cni` runs CNI plugins:

```java
ContainerdClient client = ContainerdClient.builder()
        .network(CniContainerNetwork.builder().build())   // /opt/cni/bin, /etc/cni/net.d
        .build();

client.containers().create(ContainerSpec.builder()
        .id("fn-1").image("docker.io/library/alpine:latest")
        .network("mynet")            // the "name" inside a .conflist, not its filename
        .build());
client.containers().start("fn-1");   // attached here, once the namespace exists
client.containers().stop("fn-1");    // detached here, while it still does
```

It is a separate artifact on purpose: it pulls
[libcni-java](https://github.com/miciav/libcni-java) and, through it, Gson, and the core has no
JSON dependency by design. Consumers who do not want CNI never see either.

libcni-java is published to GitHub Packages, which requires a token to read even a public package,
unlike Maven Central:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/miciav/libcni-java")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")   // needs read:packages
        }
        content { includeGroup("io.libcni") }   // only this group, so the core needs no token
    }
}
```

When libcni-java happens to sit next to this repository it is built from source instead, so
working on both at once needs neither a token nor a publish step. Pass
`-PlibcniFromPackages=true` to use the published artifact anyway. CI never has the directory, so
it always exercises the published path — a broken publish is caught there rather than by a
consumer.

The timing is the library's responsibility rather than the caller's, because it is easy to get
wrong and expensive when you do: the namespace CNI configures is the task's, so it exists only
between the task starting and being torn down. Detaching after the task has gone finds nothing to
undo and leaves an address allocated on the host that nothing will reclaim.

```java
ContainerdClient.builder()
        .network(CniContainerNetwork.builder().build())
        .stateDirectory(Path.of("/var/lib/nanofaas"))   // survives a reboot; the default does not
        .build();
```

The state directory holds the per-container files this library owns, and the container's mount
points into it. The default is under `java.io.tmpdir` because a default has to be writable by
whoever is running; it is the wrong choice for a host that reboots with containers still running,
which would leave them mounting a file that no longer exists.

DNS is applied, not merely reported. CNI hands back the nameservers a network specifies but does
not configure anything with them; that is the runtime's job. A per-container `resolv.conf` is
bind-mounted at `/etc/resolv.conf` and filled in once the network is attached — without it a
container has an address and a route and cannot resolve a single name, which is a more confusing
kind of broken than having no network at all.

```bash
# The CNI tests need root and installed plugins, so they have their own task.
sudo ./gradlew cniIntegrationTest
```

The scenarios run against three networks that differ deliberately — routed without DNS, routed
with DNS, isolated with neither — and assert from inside the container rather than from the CNI
result, since a result saying an address was assigned is not the same as the container having one.

## GraalVM native image

The library is usable from a GraalVM native image and ships the reachability metadata to make that
work without the consumer running the tracing agent:

```bash
./gradlew nativeCompile          # needs GRAALVM_HOME, or a GraalVM as the toolchain
./build/native/nativeCompile/containerd-java-example
```

`mkfifo` goes through the Foreign Function and Memory API, which is why Java 22 is the floor. The
JNR binding it replaced generated its native stubs as bytecode at runtime: images built fine and
then died on the first exec with "Class defined at runtime". Compiling is not the test — CI builds
the image and runs it against a real containerd, and checks it actually exec'd inside a container
rather than trusting the exit code.

The metadata was recorded from a real run with the tracing agent, then curated: the example's
logging backend was dropped, and the Netty epoll library glob widened to both architectures, since
the agent only sees the one it ran on.

## Static analysis

`./gradlew sonarAnalysis` runs SonarQube over this project, with the server and its PostgreSQL
database started as containers **through this library** — the orchestrator under `src/sonar` is
itself a consumer of the public API, so the analysis doubles as an end-to-end exercise of it.

```bash
./gradlew sonarAnalysis                          # analyse, report, tear everything down
./gradlew sonarAnalysis -PsonarArgs=--keep       # leave the stack up to browse the results
./gradlew sonarAnalysis -PsonarArgs=--cleanup    # remove what a --keep run left behind
```

Ports 5432 and 9000 must be free: the containers share the host's network stack, because this
library has no CNI and therefore no port mapping. The run checks both before starting anything.

Findings judged not worth changing carry a `@SuppressWarnings("java:S…")` at the code they refer
to, with the reason beside them, so the judgement travels with the code and survives the analysis
stack being thrown away. One finding is deliberately left open — dependency verification metadata
is a fair point about supply chain, not a false positive, and suppressing it would hide a real
suggestion rather than address it.

Coverage comes from JaCoCo (`build/reports/jacoco/test/`), which the analysis feeds to SonarQube;
without it the server reports 0% no matter how many tests run.

The analysis itself goes through the `org.sonarqube` Gradle plugin rather than a scanner
container. The `sonar-scanner-cli` image is published for amd64 only and cannot run on arm64, and
the plugin knows the source sets, compiled classes and test reports without being told.

## Documentation

- [CHANGELOG.md](CHANGELOG.md) — what changed in each release.
- [docs/spec.md](docs/spec.md) — the specification this implementation is written against.
- [docs/end-to-end.md](docs/end-to-end.md) — running the end-to-end scenario: the sonata-engine
  workflow that builds a VM, installs rootless containerd and crun, and runs the demo against it.
- Javadoc: `./gradlew javadoc`, then open `build/docs/javadoc/index.html`. It covers the public
  API (`io.nanofaas.containerd` and its `spi` package); the `internal` package and the generated
  protobuf stubs are deliberately excluded, since neither is API. The javadoc build is strict —
  an undocumented public member or a missing `@param` fails it — so the published API stays
  documented.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The Protocol Buffers definitions under `src/main/proto/github.com/containerd/` are vendored
verbatim from containerd v2.2.1 and remain copyright The containerd Authors, under the same
licence. See [NOTICE](NOTICE).


## Recoverable lifecycle (0.22.0)

Published Maven coordinates are `io.nanofaas:containerd-java:0.22.0` (core) and
`io.nanofaas:containerd-java-cni:0.22.0` (core plus `io.libcni:libcni-java:0.22.0`
transitively). Both include source/Javadoc artifacts; native reachability metadata ships in core
and in the transitive libcni artifact. No remote repository is configured for publication here.

For a local source build with the sibling libcni checkout:

```sh
(cd ../libcni-java && ./gradlew publishToMavenLocal)
./gradlew test publishToMavenLocal -PlibcniFromPackages=true
```

Consumers need only the CNI coordinate when networking is required:

```groovy
dependencies { implementation 'io.nanofaas:containerd-java-cni:0.22.0' }
```

Choose a persistent `ContainerdClient.builder().stateDirectory(path)`. The client scopes files as
`path/scope-<sha256>/container-id/`, hashing the normalized absolute daemon socket path, a NUL
separator, and the namespace. Reuse all three settings after restart; use the same spelling for
socket symlinks. The directory contains `resolv.conf` and an atomically replaced, fsynced
`lifecycle.properties` record. Upgrading from older versions preserves cleanup through daemon
labels, but attachments allocated before this version cannot be reconstructed.

`containers().networkAttachment(id)` returns the last durable allocation, or `null` when no
attachment completed or after successful detach. Daemon read errors propagate rather than
appearing as an absent allocation. `pendingRemovals()` reads durable container identities, labels,
and snapshot keys even after daemon metadata disappeared. These entries are cleanup work, not
healthy containers. Retry `remove(id, options)` on each owned entry; an earlier request to remove
the snapshot is remembered even if the retry uses default options. Failed DEL, task deletion,
snapshot removal and state cleanup are reported; independent cleanup steps are still attempted.
The first failure is retained and later failures are suppressed. Reusing an id with pending
cleanup is refused. Serialize lifecycle operations for a given container id across clients.

Rootless systemd cgroups require both `.systemdCgroup(true)` on the **client** (shim option) and
`.cgroupsPath("user.slice:nanofaas:my-container")` on the **spec**, adjusted for the delegated slice.
CRI plugin configuration does not configure these native gRPC calls. Specs also accept
`.cpuSetCpus("0-1")` and `.memoryReservationBytes(67108864)` alongside the existing quota, period,
shares and hard memory limit options. Actual controller delegation is an environment requirement.

Run the live check inside the rootless daemon's namespaces, where its returned PIDs are visible:

```sh
./gradlew rootlessIntegrationTest \
  -Dio.nanofaas.containerd.socket=/run/user/1000/containerd/containerd.sock \
  -Dio.nanofaas.containerd.snapshotter=native \
  -Dio.nanofaas.containerd.runtimeBinaryName=crun \
  -Dio.nanofaas.containerd.cpus=0 \
  -Dio.nanofaas.containerd.cgroupSlice=user.slice
```

This separate task fails on missing setup or ineffective cgroup v2 limits; it does not skip.
The regular `test` suite covers durable cleanup and namespace isolation and can also run as
`nativeTest` with GraalVM. JVM tests and published metadata alone do not establish live rootless
or native runtime correctness. For reproducible CI, build pinned library commits or released
versions instead of using mutable snapshots as the only source.
