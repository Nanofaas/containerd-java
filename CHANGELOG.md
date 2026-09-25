# Changelog

All notable changes to this project are documented here. This project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the major version is 0, minor
versions may carry breaking changes.

## [0.23.0]

### Removed

- **`ContainerdClient.tasks()` and the `Tasks` interface.** They skipped the lifecycle that
  `Containers` manages: `tasks().start` left a networked container without its network,
  `tasks().delete` never released its address, and neither honoured pending cleanup. Use
  `Containers.start`/`stop`/`kill`/`wait`/`inspect` instead.
- `ContainerState.STARTING`. containerd never reports it and this client never produced it.
- `EventFilter.toFieldpathFilters`. It ignored the filter and only built the namespace
  scope, which is now internal to the events service.

### Changed

- `containerd-java-cni` depends on libcni-java 0.23.0.
- `ContainerdClient.Builder` is a final class rather than an interface. Code that calls
  `ContainerdClient.builder()` compiles unchanged but must be recompiled.

## [0.22.0]

- Persist network attachments and cleanup ownership atomically; expose `networkAttachment` and
  `pendingRemovals`, including identities whose daemon metadata has already been deleted.
- Report cleanup failures, attempt independent resources, retain retry state and preserve the
  primary rollback failure. Refuse reuse while cleanup is pending.
- Isolate durable state by daemon socket and namespace.
- Add CPU affinity, memory reservation, OCI cgroup path and explicit systemd shim options.
- Publish separate core and CNI Maven artifacts with transitive dependencies and native metadata.
- Add unit/native-compatible journal tests and an explicit rootless cgroup integration gate.

### Added

- **Container networking through CNI.** `ContainerSpec.network("mynet")` attaches a container to a
  CNI network; `containerd-java-cni` provides the implementation, built on
  [libcni-java](https://github.com/miciav/libcni-java). Until now the only way to give a container
  a working network was `hostNetwork(true)`, which gives it the host's stack and no isolation.
- `ContainerNetwork`, the interface the core knows networking by. It names no CNI type, so the CNI
  implementation and its transitive Gson stay off the classpath of consumers who do not want them.

- `NetworkAttachment`, what a container got when it was attached: addresses, gateways and DNS.
  `ContainerNetwork.attach` returns one instead of discarding the CNI result.
- `ContainerdClient.Builder.stateDirectory(Path)` names where this client keeps its per-container
  files — today the `resolv.conf` mounted into a networked container. The default lives under
  `java.io.tmpdir`, which is writable by whoever is running and survives nothing: a host that
  reboots with a container still running would leave that container's mount pointing at a file
  that no longer exists. Anything long-lived should name a persistent path.
- **DNS now reaches the container.** CNI reports nameservers but never applies them — that is the
  runtime's job — so until now a container had an address and a route and could not resolve a
  single name. A per-container `resolv.conf` is bind-mounted at `/etc/resolv.conf` and filled in
  once the network reports its DNS, which is how docker does it and the only way that works: most
  images have no `/etc/resolv.conf` to write into, and there is no writable rootfs before the
  container is running.

### Notes

- The lifecycle is the library's to get right, not the caller's. A container's network namespace is
  its task's, existing only between the task starting and being torn down, so attaching happens
  after start and detaching before teardown — including for a task that already exited, whose
  address still has to be released. Nine tests assert the ordering of the calls, not merely that
  they happened.
- Ten end-to-end scenarios run against real plugins across three networks that differ on purpose —
  routed without DNS, routed with DNS, isolated with neither — and assert from inside the
  container: the interface is up with an address from the right range, loopback works, names
  resolve, egress works or does not according to the route, and containers on different networks
  cannot reach each other.
- Asking for a network without giving the client a `ContainerNetwork` is refused at create time
  rather than ignored: a container that asked to be on a network and silently is not is worse than
  one that never started. `hostNetwork` and `network` are likewise mutually exclusive.

## [0.3.0] — 2026-09-06

### Breaking

- **Requires Java 22**, up from 21. `mkfifo` now goes through the Foreign Function and Memory API,
  which is final from 22. The JNR binding it replaced generates its native stubs as bytecode at
  runtime, which a GraalVM native image cannot do at all.

### Added

- **GraalVM native image support.** The library ships reachability metadata under
  `META-INF/native-image/io.nanofaas/containerd-java/`, so a consumer's native build works without
  their running the tracing agent. Verified rather than assumed: a native image of the example
  drives a real containerd end to end — pull, create, start, exec, stop, delete — and CI builds and
  runs it on every push, checking the output rather than just the exit code.
- `./gradlew nativeCompile` builds that image.

- `ContainerSpec.hostNetwork(true)` shares the host's network stack instead of giving the
  container a private, unconfigured namespace. Containers previously had only `lo`, with no
  routes and no DNS, so they could neither reach anything nor be reached; configuring a private
  namespace needs CNI, which this library does not do.
- The image's own configuration now shapes the container. Entrypoint, Cmd, Env, User and
  WorkingDir were read from the image config blob but ignored, so any image that relies on them —
  which is most real images — had to have all of it restated by the caller. The caller's spec
  still wins wherever it expresses a wish.
- An exec inherits the container's environment and working directory, the way `docker exec` does,
  read back from the OCI spec containerd already stores on the container. Without it nothing the
  image ships was on the exec's PATH.
- `Containers.logs(String)` returns what a container's init process wrote, captured when the
  container is created with `ContainerSpec.logDirectory(Path)`. There was previously no way to
  see a container's output at all: one that died on startup did so silently, and diagnosing it
  meant leaving the library and re-running the image under `ctr`. stdout and stderr come back
  interleaved, because containerd writes both to one destination and ignores a second.
- `ContainerSpec.openFilesLimit(long)` sets RLIMIT_NOFILE, which was hard-coded to 1024 for every
  container. Servers that pool connections or memory-map many files need far more: Elasticsearch
  enforces a minimum of 65535 as a startup check, so SonarQube could not run at all.
- `./gradlew sonarAnalysis` analyses this project with SonarQube, running the server and its
  database as containers driven by this library. `-PsonarArgs=--keep` leaves the stack up for
  browsing; `-PsonarArgs=--cleanup` removes what a kept run left behind.
- JaCoCo coverage reporting, wired into the analysis. Without it SonarQube reported 0% however
  many tests ran, and failed its quality gate on code that was in fact covered. Line coverage is
  currently 69%, branch 56%.

### Fixed

- Removed a dead `runtimeBinaryName` field in `DefaultContainerdClient`, unused imports, and a
  test that asserted nothing while its name promised it checked the epoll error message — it
  could not have: that message only appears when epoll is absent, which is when the suite cannot
  run at all.

- Container creation holds a containerd lease across the window where a snapshot exists but the
  container that will own it does not. Verified to matter: an unreferenced snapshot survives a
  synchronous garbage collection indefinitely, while one held by a lease is collected as soon as
  the lease goes — so a process killed mid-create now leaves something containerd will clean up
  within the lease's 24 hours instead of a snapshot nobody can account for. The lease is released
  as soon as the container references the snapshot itself, and a containerd that refuses to issue
  one is not treated as an error: the create proceeds unleased rather than failing.

### Fixed

- Container creation now releases the prepared snapshot on any failure, not only on a gRPC one.
  The snapshot is prepared before the container exists, so anything failing in between left it
  owned by nothing and never collected — the stale snapshot the create path already had to work
  around.
- An image index without the host's platform no longer silently resolves to its first entry,
  which is usually amd64. On an arm64 host that produced a container whose every binary was the
  wrong architecture, reported by the runtime as nothing more than `exec format error`. It now
  says which platform was wanted and which the image offers.

### Removed

- The `jnr-posix` dependency. It existed for one call, `mkfifo`.

### Changed

- A `User` that is a name rather than a uid now logs a warning and runs as uid 0, instead of
  throwing. Images commonly declare a name, and refusing would make them unusable. Resolving one
  would mean reading `/etc/passwd` from a filesystem that is not mounted yet.

## [0.2.0] — 2026-09-06

First release with a licence and published javadoc. The bulk of this release is a full review of
the library: 14 defects fixed and 14 code smells cleaned up, with the test suite grown from 50 to
78 unit tests plus 26 integration tests that run against a real containerd in CI.

### Breaking

- `TaskInfo` gained an `exitedAt` component. `inspect()` used to discard the exit timestamp that
  `Tasks.wait` already reported.
- `ProtoMapper.mapStatus` takes the generated `containerd.v1.types.Status` enum instead of an
  `int` (internal API).
- `ContainerdClientBuilder` is no longer public; reach it through `ContainerdClient.builder()`.
- Container ids are validated against containerd's real rule. Ids the previous, looser pattern
  accepted — a trailing or leading separator (`fn-`, `-fn`), doubled separators (`a..b`) — are
  now rejected up front instead of failing server-side a round trip later.
- `exec` no longer sets `no_new_privs`. It now matches the container's init process, as docker
  and `ctr` do by default, so setuid binaries behave the same way whichever way a command is
  started. Callers relying on execs being more restricted than the entrypoint should set their
  own policy.

### Fixed

- `ContentStoreReader.read` looped forever when the Read stream completed before delivering the
  size Info advertised, re-issuing the same RPC at full CPU. Reached by every `containers.create`.
- Closing the client raised `RejectedExecutionException` on a gRPC callback thread: the event
  executors were shut down while streams were still live.
- `Subscription.close()` only set a flag and never cancelled the gRPC call, so containerd kept
  streaming into a subscription nobody read for the life of the channel.
- User labels could overwrite the `containerd.io/gc.ref.snapshot` label, leaving containerd free
  to collect the snapshot out from under a live container.
- `stop()` could block forever: after SIGKILL it waited with no deadline, and SIGKILL does not
  reach a process parked in uninterruptible sleep. It now reports the failure instead.
- `stop()` reported the wrong cause: the task delete ran in a `finally`, and deleting a live task
  fails, so that failure replaced the exception explaining why the stop failed.
- `exec` left a process reading stdin blocked forever when the caller supplied none; stdin is now
  always opened and closed so the process sees EOF.
- Six RPCs bypassed the exception mapper and threw raw `StatusRuntimeException` at callers:
  `Tasks.start`/`inspect`/`list`, `Containers.list`, `Images.list`, `SnapshotManager.mounts`.
- Task lookup used `exists()` followed by `inspect()`, two round trips with a race in the gap. A
  single-round-trip `find()` replaces both.
- Exception messages dropped containerd's own explanation, leaving only a bare status code.
- OCI manifests were decoded with the platform default charset instead of UTF-8.
- `EventFilter.topics()` with no argument does not compile — it resolves against the instance
  getter of the same name — leaving the documented "every topic" filter unreachable. Added
  `EventFilter.all()`.
- A directory stream was left unclosed in `IoManager`, and `close()` did not let in-flight exec
  IO drain before the channel went away.

### Changed

- Stop grace period is configurable per client with `.stopTimeout(Duration)`; it was a hard-coded
  10 seconds.
- `Signal` covers 17 signals and gained `fromNumber`.
- `Platform` normalizes the architecture the same way as the os, so `AMD64` is recognised.
- Builders reject nulls where they are given rather than in a later `List.copyOf`.
- `Identifiers` is a public class; the model no longer reaches into the `internal` package.

### Added

- Apache 2.0 `LICENSE`, and a `NOTICE` attributing the containerd protobuf definitions vendored
  under `src/main/proto`.
- Published `sources` and `javadoc` jars. Javadoc covers the public API and is checked strictly:
  an undocumented public member, a missing `@param`/`@return`, a broken link or malformed HTML
  fails the build.
- GitHub Actions CI: build plus unit tests, and integration tests against containerd 2.2.1
  installed in the runner. The integration job fails if any test skipped, since these tests skip
  themselves when containerd is unreachable.

### Security

- The checked-in `gradle-wrapper.jar` was Gradle 4.4.1, paired by hand with a properties file
  declaring 9.7.1, and matched no published checksum. Replaced with the real 9.7.1 wrapper, and
  `distributionSha256Sum` — previously absent — now pins the distribution the wrapper downloads.

## [0.1.0] — 2026-09-04

Initial implementation: images, containers, tasks, exec and events over containerd's native gRPC
API.
