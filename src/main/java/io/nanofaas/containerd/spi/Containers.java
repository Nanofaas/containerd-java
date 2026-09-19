package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.*;

import java.util.List;

/**
 * Container lifecycle operations. A containerd container is metadata + spec + snapshot; running
 * state lives in a task. {@link #start} creates the task; {@link #stop} tears it down.
 */
public interface Containers {

    /**
     * Creates a container: prepares a snapshot from the image's root filesystem and stores the
     * container metadata and OCI spec. Does not start anything; see {@link #start}.
     *
     * @param spec desired state of the container
     * @return the container as containerd stored it
     * @throws io.nanofaas.containerd.ContainerAlreadyExistsException if the id is taken
     * @throws io.nanofaas.containerd.ImageNotFoundException if the image is not in the store
     */
    Container create(ContainerSpec spec);

    /**
     * Reads the last durable network allocation, including after a client restart.
     * @param id container id
     * @return the allocation, or null if no attachment has completed or it was detached
     * @throws ContainerdException if the daemon or durable state cannot be read
     */
    NetworkAttachment networkAttachment(String id);

    /** {@return durable identities of incomplete cleanup operations, even without daemon metadata} */
    List<Container> pendingRemovals();

    /**
     * Combined view of the container metadata and its task state, if any.
     *
     * @param id container id
     * @return the container's current status
     */
    ContainerStatus inspect(String id);

    /** {@return every container in the client's namespace} */
    List<Container> list();

    /**
     * Returns what the container's init process has written so far, stdout and stderr interleaved
     * as they were produced.
     *
     * <p>The two are not separable: given a file destination containerd writes both streams to it
     * and ignores any second destination, so what comes back is one combined stream — the same
     * thing {@code docker logs} shows by default.
     *
     * <p>Only works for containers created with
     * {@link io.nanofaas.containerd.ContainerSpec.Builder#logDirectory}: containerd discards a
     * task's output unless it is told where to send it, and that cannot be decided after the task
     * has started.
     *
     * @param id container id
     * @return the captured output, empty if the task has not written anything yet
     * @throws io.nanofaas.containerd.ContainerdException if the container was created without a
     *         log directory, or the file cannot be read
     */
    String logs(String id);

    /**
     * Removes a container. Idempotent — a missing container is ignored. If a task is still running,
     * removal throws unless {@link RemoveOptions#force()} is set (which stops the task first).
     *
     * @param id container id
     * @param options whether to remove the snapshot and whether to force
     */
    void remove(String id, RemoveOptions options);

    /**
     * Creates (if needed) and starts the container's task.
     *
     * @param id container id
     * @return pid of the init process
     */
    int start(String id);

    /**
     * Stops a running container: SIGTERM, wait up to the client's
     * {@link ContainerdClient.Builder#stopTimeout stopTimeout} (10s by default), then SIGKILL,
     * then task delete.
     * Idempotent — a container with no task returns an empty {@link java.util.Optional}.
     *
     * @param id container id
     * @return how the task exited, or empty if there was no task
     */
    java.util.Optional<ExitStatus> stop(String id);

    /**
     * Sends a signal to every process in the container's task, without waiting for it to exit.
     *
     * @param id container id
     * @param signal signal to send
     */
    void kill(String id, Signal signal);

    /**
     * Blocks until the container's init process exits.
     *
     * @param id container id
     * @return how it exited
     */
    ExitStatus wait(String id);

    /**
     * Runs a command inside the running container and collects its output.
     *
     * @param id container id; its task must be RUNNING
     * @param command argv of the process to run
     * @return exit code and captured output
     */
    ExecResult exec(String id, List<String> command);

    /**
     * Runs a command inside the running container, with control over environment, working
     * directory and stdin.
     *
     * @param id container id; its task must be RUNNING
     * @param spec what to run and how
     * @return exit code and captured output
     */
    ExecResult exec(String id, ExecSpec spec);
}
