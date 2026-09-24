package io.nanofaas.containerd;

/** Lifecycle state of a container's task, mirroring containerd's task status enum. */
public enum ContainerState {
    /** The task exists and its process is spawned but not yet started. */
    CREATED,
    /** The task's init process is running. */
    RUNNING,
    /** The task's init process has exited; its exit code is available until the task is deleted. */
    STOPPED,
    /** The task is paused (its cgroup is frozen). */
    PAUSED,
    /** The task is in the middle of being paused. */
    PAUSING,
    /** No task, or a status this client does not recognise. */
    UNKNOWN
}
