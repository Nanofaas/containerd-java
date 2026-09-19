package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.nanofaas.containerd.ContainerSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds OCI runtime specs (JSON) wrapped in typeurl {@link Any}s with the type URLs containerd
 * registers for the Go runtime-spec structs. Verified against containerd v2.2.1: the spec travels
 * as JSON bytes, not protobuf.
 */
public final class OciSpecBuilder {

    private static final Logger log = LoggerFactory.getLogger(OciSpecBuilder.class);

    public static final String SPEC_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Spec";
    public static final String PROCESS_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Process";

    /**
     * The OCI runtime spec version this config declares.
     *
     * <p>1.0.0 because it is accurate: every field built here exists in 1.0, and nothing uses a
     * feature added since. This declared 1.2.0, which was both more than was true and, in
     * practice, unusable — crun before 1.14.3 has a version-check bug and refuses any config
     * declaring 1.2.x with "unknown version specified" (fixed upstream in crun 1.14.3, and the
     * same bug bites podman and singularity).
     *
     * <p>Declaring the version actually used sidesteps that entirely rather than requiring a crun
     * new enough to tolerate a claim this library never needed to make. Raise it only alongside a
     * field that genuinely requires the newer spec.
     *
     * <p>That this went unnoticed is worth recording: containerd's runc-v2 shim runs runc unless
     * binary_name says otherwise, and runc accepts 1.2.x, so the whole integration suite passed
     * while crun — the runtime this library's own specification names — could not start a single
     * container. {@code ContainerLifecycleIT.runsUnderCrunAsWellAsRunc} is the test that was
     * missing.
     */
    static final String SPEC_VERSION = "1.0.0";

    // Not a path this code opens: it is the PATH given to the container's process, the same
    // default docker and ctr use.
    @SuppressWarnings("java:S1075")
    private static final String DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    private static final List<String> DEFAULT_CAPABILITIES = List.of(
            "CAP_CHOWN", "CAP_DAC_OVERRIDE", "CAP_FSETID", "CAP_FOWNER", "CAP_MKNOD", "CAP_NET_RAW",
            "CAP_SETGID", "CAP_SETUID", "CAP_SETFCAP", "CAP_SETPCAP", "CAP_NET_BIND_SERVICE",
            "CAP_SYS_CHROOT", "CAP_KILL", "CAP_AUDIT_WRITE");
    // Mount options, named because the standard table below repeats them and a typo in one would
    // silently weaken a container's isolation rather than fail.
    private static final String NOSUID = "nosuid";
    private static final String NOEXEC = "noexec";
    private static final String NODEV = "nodev";
    private static final String TMPFS = "tmpfs";

    /** The namespace to drop for host networking: without it the container keeps the host's stack. */
    private static final String NETWORK_NAMESPACE = "network";
    private static final List<String> DEFAULT_NAMESPACES =
            List.of("pid", NETWORK_NAMESPACE, "ipc", "uts", "mount");
    private static final List<String> DEFAULT_MASKED_PATHS = List.of(
            "/proc/acpi", "/proc/asound", "/proc/kcore", "/proc/keys", "/proc/latency_stats",
            "/proc/timer_list", "/proc/timer_stats", "/proc/sched_debug", "/sys/firmware", "/proc/scsi");
    private static final List<String> DEFAULT_READONLY_PATHS = List.of(
            "/proc/bus", "/proc/fs", "/proc/irq", "/proc/sys", "/proc/sysrq-trigger");

    private OciSpecBuilder() {
    }

    /** Builds the full OCI runtime spec for a container, ignoring any image configuration. */
    public static Any buildContainerSpec(ContainerSpec spec) {
        return buildContainerSpec(spec, ImageConfig.EMPTY);
    }

    /**
     * Builds the full OCI runtime spec for a container as a typeurl Any (JSON payload), with the
     * image's configuration supplying what the caller left unset.
     *
     * @param spec the caller's wishes, which win wherever they are expressed
     * @param image the image's entrypoint, cmd, env, user and working directory
     * @return the spec, ready to attach to a container
     */
    static Any buildContainerSpec(ContainerSpec spec, ImageConfig image) {
        return buildContainerSpec(spec, image, List.of());
    }

    /**
     * Builds the spec with mounts this library adds of its own, on top of the caller's.
     *
     * @param spec the caller's wishes
     * @param image the image's configuration
     * @param extraMounts mounts the library adds, such as the resolv.conf a networked container gets
     * @return the spec, ready to attach to a container
     */
    static Any buildContainerSpec(ContainerSpec spec, ImageConfig image,
                                  List<ContainerSpec.MountSpec> extraMounts) {
        Struct.Builder root = Struct.newBuilder()
                .putFields("ociVersion", stringValue(SPEC_VERSION))
                .putFields("process", structValue(buildProcess(spec, image).build()))
                .putFields("root", structValue(Struct.newBuilder()
                        .putFields("path", stringValue("rootfs"))
                        .putFields("readonly", boolValue(spec.readonlyRootfs()))
                        .build()))
                .putFields("hostname", stringValue(spec.hostname() != null ? spec.hostname() : spec.id()))
                .putFields("mounts", buildStandardMounts(spec, extraMounts))
                .putFields("linux", structValue(buildLinux(spec).build()));
        return toAny(SPEC_TYPE_URL, root.build());
    }

    /** Builds the process spec for exec, with nothing inherited from the container. */
    public static Any buildExecSpec(List<String> command, Map<String, String> environment, String workingDir) {
        return buildExecSpec(command, environment, workingDir, List.of(), null, null);
    }

    /**
     * Builds the process spec for exec as a typeurl Any (JSON payload).
     *
     * @param command argv to run
     * @param environment the caller's environment, which wins over the container's
     * @param workingDir the caller's working directory, or {@code null} to use the container's
     * @param containerEnv the environment the container's own process runs with, inherited here
     *        the way {@code docker exec} does: a command run inside a container should see the
     *        same environment the container does, or nothing that image ships is on its PATH
     * @param containerWorkingDir the container's working directory, used when the caller sets none
     * @param containerRlimits the container's own {@code rlimits}, carried over so a command run
     *        inside a container runs under the limits that container was created with. Without
     *        this the runtime falls back to its own default (1024 open files, typically), so
     *        {@code openFilesLimit} would silently apply to the entrypoint and not to an exec.
     * @return the process spec
     */
    static Any buildExecSpec(List<String> command, Map<String, String> environment, String workingDir,
                             List<String> containerEnv, String containerWorkingDir,
                             Value containerRlimits) {
        String cwd = workingDir != null ? workingDir : containerWorkingDir;
        Struct.Builder process = process(command, environment, cwd, null, containerEnv, List.of());
        if (containerRlimits != null) {
            process.putFields("rlimits", containerRlimits);
        }
        return toAny(PROCESS_TYPE_URL, process.build());
    }

    private static Struct.Builder buildProcess(ContainerSpec spec, ImageConfig image) {
        // The caller wins wherever they expressed a wish; the image fills in the rest. A container
        // built from an image that ships an entrypoint (most real images do) is unusable
        // otherwise, because the caller would have to restate it, and its environment, by hand.
        List<String> args = spec.command().isEmpty() ? image.defaultArgs() : spec.command();
        String workingDir = spec.workingDir() != null ? spec.workingDir() : image.workingDir();
        String user = spec.user() != null ? spec.user() : image.user();

        Struct.Builder process = process(args, spec.environment(), workingDir, user,
                image.env(), List.of("TERM=xterm"));
        process.putFields("rlimits", rlimitsValue(spec.openFilesLimit()));
        return process;
    }

    /**
     * The fields every OCI process spec carries, shared by the container's init process and by
     * exec, so the two cannot drift apart. {@code user} is {@code null} for exec (which always
     * runs as uid 0).
     *
     * <p>Environment precedence, weakest first: this library's default PATH, {@code extraEnv},
     * the image's environment, then the caller's. Later entries win, and duplicates are collapsed
     * so a single value per key reaches the runtime — an image that sets its own PATH (a JDK
     * image, say) must not be shadowed by ours, and the caller must be able to override both.
     */
    private static Struct.Builder process(List<String> command, Map<String, String> environment,
                                          String workingDir, String user, List<String> imageEnv,
                                          List<String> extraEnv) {
        List<String> args = command == null || command.isEmpty() ? List.of("/bin/sh") : command;
        List<String> env = mergeEnv(extraEnv, imageEnv, environment);
        return Struct.newBuilder()
                .putFields("terminal", boolValue(false))
                .putFields("user", structValue(parseUser(user)))
                .putFields("args", stringListValue(args))
                .putFields("env", stringListValue(env))
                .putFields("cwd", stringValue(workingDir != null ? workingDir : "/"))
                // Same value for the init process and for exec, matching what docker and ctr do
                // by default: setuid binaries keep working, and a command behaves the same way
                // whether it is the entrypoint or an exec. Previously exec alone set this true,
                // so "sudo" failed under exec and worked as the entrypoint, with nothing saying why.
                .putFields("noNewPrivileges", boolValue(false))
                .putFields("capabilities", structValue(capabilitiesValue()));
    }

    /** Collapses the environment layers into {@code KEY=VALUE} entries, last writer winning. */
    private static List<String> mergeEnv(List<String> extraEnv, List<String> imageEnv,
                                         Map<String, String> callerEnv) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        merged.put("PATH", DEFAULT_PATH);
        for (List<String> layer : List.of(extraEnv, imageEnv)) {
            for (String entry : layer) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    merged.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
        }
        if (callerEnv != null) {
            merged.putAll(callerEnv);
        }
        List<String> env = new ArrayList<>(merged.size());
        merged.forEach((k, v) -> env.add(k + "=" + v));
        return env;
    }

    private static Struct parseUser(String user) {
        Struct.Builder b = Struct.newBuilder().putFields("uid", numberValue(0)).putFields("gid", numberValue(0));
        if (user == null || user.isBlank()) {
            return b.build();
        }
        String[] parts = user.split(":", 2);
        try {
            b.putFields("uid", numberValue(Long.parseLong(parts[0].trim())));
            b.putFields("gid", numberValue(parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0));
        } catch (NumberFormatException e) {
            // A name cannot be honoured: the OCI runtime spec's process.user carries uid/gid only,
            // with no field for a name, so there is nothing to hand the runtime. Resolving it
            // would mean reading /etc/passwd out of a root filesystem that is not mounted yet.
            // Warn rather than throw: this value often comes from the image, not the caller, and
            // refusing would make such images unusable. Pass "uid[:gid]" to select a user.
            log.warn("user \"{}\" is a name, which cannot be mapped to a uid; running as uid 0."
                    + " Pass \"uid[:gid]\" on the ContainerSpec to run as that user", user);
            return Struct.newBuilder().putFields("uid", numberValue(0)).putFields("gid", numberValue(0)).build();
        }
        return b.build();
    }

    private static Value buildStandardMounts(ContainerSpec spec, List<ContainerSpec.MountSpec> extraMounts) {
        List<Value> mounts = new ArrayList<>(List.of(
                mount("proc", "proc", "/proc", List.of(NOSUID, NOEXEC, NODEV)),
                mount(TMPFS, TMPFS, "/dev", List.of(NOSUID, "strictatime", "mode=755", "size=65536k")),
                mount("devpts", "devpts", "/dev/pts",
                        List.of(NOSUID, NOEXEC, "newinstance", "ptmxmode=0666", "mode=0620", "gid=5")),
                mount(TMPFS, "shm", "/dev/shm", List.of(NOSUID, NOEXEC, NODEV, "mode=1777", "size=65536k")),
                mount("mqueue", "mqueue", "/dev/mqueue", List.of(NOSUID, NOEXEC, NODEV)),
                mount("sysfs", "sysfs", "/sys", List.of(NOSUID, NOEXEC, NODEV, "ro")),
                mount("cgroup", "cgroup", "/sys/fs/cgroup", List.of(NOSUID, NOEXEC, NODEV, "relatime", "ro"))));
        for (ContainerSpec.MountSpec m : spec.mounts()) {
            mounts.add(mount(m.type(), m.source(), m.destination(), m.options()));
        }
        for (ContainerSpec.MountSpec m : extraMounts) {
            mounts.add(mount(m.type(), m.source(), m.destination(), m.options()));
        }
        return listValue(mounts);
    }

    private static Value mount(String type, String source, String destination, List<String> options) {
        Struct.Builder b = Struct.newBuilder()
                .putFields("destination", stringValue(destination))
                .putFields("type", stringValue(type))
                .putFields("source", stringValue(source));
        if (options != null && !options.isEmpty()) {
            b.putFields("options", stringListValue(options));
        }
        return Value.newBuilder().setStructValue(b.build()).build();
    }

    private static Struct.Builder buildLinux(ContainerSpec spec) {
        List<Value> namespaces = new ArrayList<>();
        for (String ns : DEFAULT_NAMESPACES) {
            // An OCI spec asks for a namespace by listing it; omitting it inherits the host's.
            // There is no "host" value to set — leaving it out is the mechanism.
            if (spec.hostNetwork() && NETWORK_NAMESPACE.equals(ns)) {
                continue;
            }
            namespaces.add(Value.newBuilder().setStructValue(Struct.newBuilder()
                    .putFields("type", stringValue(ns))
                    .build()).build());
        }
        Struct.Builder linux = Struct.newBuilder()
                .putFields("namespaces", listValue(namespaces))
                .putFields("maskedPaths", stringListValue(DEFAULT_MASKED_PATHS))
                .putFields("readonlyPaths", stringListValue(DEFAULT_READONLY_PATHS));

        if (spec.cgroupsPath() != null) linux.putFields("cgroupsPath", stringValue(spec.cgroupsPath()));
        Struct.Builder resources = Struct.newBuilder();
        Struct.Builder cpu = Struct.newBuilder();
        if (spec.cpuSetCpus() != null) cpu.putFields("cpus", stringValue(spec.cpuSetCpus()));
        if (spec.cpuShares() > 0) {
            cpu.putFields("shares", numberValue(spec.cpuShares()));
        }
        if (spec.cpuQuotaMicros() > 0) {
            cpu.putFields("quota", numberValue(spec.cpuQuotaMicros()));
        }
        if (spec.cpuPeriodMicros() > 0) {
            cpu.putFields("period", numberValue(spec.cpuPeriodMicros()));
        }
        if (cpu.getFieldsCount() > 0) {
            resources.putFields("cpu", structValue(cpu.build()));
        }
        Struct.Builder memory = Struct.newBuilder();
        if (spec.memoryReservationBytes() > 0) memory.putFields("reservation", numberValue(spec.memoryReservationBytes()));
        if (spec.memoryLimitBytes() > 0) {
            memory.putFields("limit", numberValue(spec.memoryLimitBytes()));
        }
        if (spec.memorySwapLimitBytes() > 0) {
            memory.putFields("swap", numberValue(spec.memorySwapLimitBytes()));
        }
        if (memory.getFieldsCount() > 0) {
            resources.putFields("memory", structValue(memory.build()));
        }
        if (spec.pidsLimit() > 0) {
            resources.putFields("pids", structValue(Struct.newBuilder()
                    .putFields("limit", numberValue(spec.pidsLimit()))
                    .build()));
        }
        if (resources.getFieldsCount() > 0) {
            linux.putFields("resources", structValue(resources.build()));
        }
        return linux;
    }

    private static Struct capabilitiesValue() {
        Struct.Builder caps = Struct.newBuilder();
        for (String set : List.of("bounding", "effective", "inheritable", "permitted", "ambient")) {
            caps.putFields(set, stringListValue(DEFAULT_CAPABILITIES));
        }
        return caps.build();
    }

    private static Value rlimitsValue(long openFiles) {
        // OCI runtime spec: rlimits is an array of {type, hard, soft} objects (specs-go PosixRlimit).
        Value nofile = Value.newBuilder().setStructValue(Struct.newBuilder()
                .putFields("type", stringValue("RLIMIT_NOFILE"))
                .putFields("hard", numberValue(openFiles))
                .putFields("soft", numberValue(openFiles))
                .build()).build();
        return listValue(List.of(nofile));
    }

    private static Any toAny(String typeUrl, Struct spec) {
        return Any.newBuilder()
                .setTypeUrl(typeUrl)
                .setValue(ByteString.copyFromUtf8(JsonSupport.print(spec)))
                .build();
    }

    private static Value stringValue(String s) {
        return Value.newBuilder().setStringValue(s).build();
    }

    private static Value numberValue(long n) {
        return Value.newBuilder().setNumberValue(n).build();
    }

    private static Value boolValue(boolean b) {
        return Value.newBuilder().setBoolValue(b).build();
    }

    private static Value stringListValue(List<String> values) {
        List<Value> items = new ArrayList<>(values.size());
        for (String v : values) {
            items.add(stringValue(v));
        }
        return listValue(items);
    }

    private static Value listValue(List<Value> values) {
        return Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(values)).build();
    }

    private static Value structValue(Struct s) {
        return Value.newBuilder().setStructValue(s).build();
    }
}
