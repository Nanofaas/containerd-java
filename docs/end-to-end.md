# Running the end-to-end scenario

The integration tests under `src/integrationTest` run against whatever containerd the developer's
machine already has. That leaves the more interesting half of this library's claims untested:
rootless containers, CNI attachment into RootlessKit's network namespace, and a runtime that is
crun rather than runc. None of them can be exercised on a host already set up the way its owner
likes it, because a pass there might come from the host rather than from the library.

`e2e/containerd_java_e2e.py` covers them by creating the machine it needs, running against it, and
destroying it. It is a [sonata-engine](https://github.com/Nanofaas/sonata) workflow.

## What it does

Seven units, in order. The VM is a `Resource`, not a first and last task, which is what makes the
teardown run on the failure path too — the case that would otherwise leave a machine running and
billing.

| # | Unit | What it does |
|---|------|--------------|
| 001 | Build the demo from this checkout | `./gradlew e2eDistribution`, then tars it. Built from the working tree, so the run reports on the code you have, not on a published artefact. |
| 002 | Multipass VM | Launches a 4 CPU / 6 GiB / 20 GiB Ubuntu VM and waits until it answers SSH. |
| 003 | Install containerd and crun, rootless | apt for crun, `uidmap`, `dbus-user-session` and a JRE; the nerdctl-full bundle for containerd, CNI plugins and RootlessKit; then `containerd-rootless-setuptool.sh install`. |
| 004 | Define the CNI network | Writes a conflist for `cjava-e2e` (10.90.0.0/24) that declares DNS servers, a domain and a search list. |
| 005 | Deploy the demo | `scp` of the archive, unpacked in the VM. |
| 006 | Run the scenarios | Runs the demo inside RootlessKit's network namespace and parses its tally. |
| 007 | Release Multipass VM | Deletes the VM. |

## Prerequisites

- **Multipass**, with enough free disk for a 20 GiB VM.
- **An SSH key** at `~/.ssh/id_*.pub`. The provider injects it through cloud-init at launch and
  then talks to the VM over SSH, so a machine created by hand without it will not be reachable.
- **A sonata checkout**, since `sonata-engine` and `sonata-tasks` are not published to PyPI.
- **A JDK and this repo's Gradle build working**, because unit 001 builds the demo locally.

Nothing is needed on the VM side: unit 003 installs it.

## Running it

```bash
uv venv .venv-e2e
uv pip install --python .venv-e2e \
    /path/to/sonata "/path/to/sonata/packages/sonata-tasks[multipass]"

.venv-e2e/bin/python e2e/containerd_java_e2e.py
```

The `multipass` extra is what pulls in the Multipass SDK and pydantic; without it the import of
`sonata_tasks.vm.models` fails.

A full run takes roughly ten minutes, most of it apt and the nerdctl-full download. It prints each
unit as it starts and finishes, with the demo's own output nested underneath, and ends with
`workflow passed: 7 units`. The process exit status is 0 only when every unit passed.

## Knobs

All of them are environment variables read at import time.

| Variable | Default | What it is for |
|----------|---------|----------------|
| `E2E_VM_NAME` | `cjava-workflow` | The VM's name. Change it to run two scenarios side by side. |
| `KEEP_VM` | unset | Leaves the VM running instead of deleting it. |

There is deliberately no architecture setting. The nerdctl bundle runs in the VM, so the VM is
what decides which one it needs: unit 003 asks it with `dpkg --print-architecture`, whose names
are the ones nerdctl's release assets use. A local default would only ever be right for whoever
set it, and wrong for everyone else in a way that surfaces as a tar error.

The network name, its subnet and the pinned nerdctl version are constants in the file rather than
variables. The version is pinned deliberately: a scenario that reports what the libraries do has
to run against a stated containerd, or a failure cannot be told apart from an upstream change.

## Keeping the VM for a second look

```bash
KEEP_VM=1 .venv-e2e/bin/python e2e/containerd_java_e2e.py
```

`keep` makes the engine skip every release, so the VM survives the run. This workflow configures
no journal, so the retention is not recorded anywhere and `release_retained` has nothing to act
on: **clean up by hand** when you are done.

```bash
multipass delete cjava-workflow && multipass purge
```

`delete` alone leaves the instance in a `Deleted` state that still occupies disk; `purge` is what
frees it.

## Reading a failure

The demo reports each scenario as `PASS` or `FAIL` with its own notes, and ends with a tally like
`9 scenarios, 0 failed`. It reports rather than asserts, so one failure does not hide the rest,
and each scenario runs under a three-minute limit — without one, a hang stops the whole run with
no report at all, which is exactly how the exec faults fixed in `68a51b7` first showed up.

Unit 006 fails when the tally reports a failure, and also when there is no tally at all: trusting
the exit status alone would let a run that printed nothing pass as a run in which everything held.

## Running the demo without the workflow

The demo is an ordinary program and can be pointed at any containerd, including the one on your
own machine, which is quicker while iterating on a scenario:

```bash
./gradlew e2eDistribution
sudo env E2E_NETWORK=<a network in your CNI config> \
         E2E_CNI_CONFIG=/etc/cni/net.d E2E_CNI_PLUGINS=/opt/cni/bin \
     java --enable-native-access=ALL-UNNAMED \
          -cp "build/e2e-dist/classes:build/e2e-dist/lib/*" \
          io.nanofaas.containerd.e2e.E2eRun
```

| Variable | Default |
|----------|---------|
| `CONTAINERD_SOCKET` (or `-Dio.nanofaas.containerd.socket`) | `/run/containerd/containerd.sock` |
| `E2E_NETWORK` | `e2e-net` |
| `E2E_CNI_CONFIG` | `~/.config/cni/net.d` |
| `E2E_CNI_PLUGINS` | `/usr/local/libexec/cni` |

Point it at a network whose configuration declares DNS. nerdctl's default bridge does not, and a
run against that reports an empty `resolv.conf`: the DNS half of the networking scenario passes
while proving nothing.

## Obstacles worth knowing about

Both of these are handled by the workflow. They are recorded because neither announces itself, and
anyone reproducing this by hand will meet them.

**AppArmor and unprivileged user namespaces.** Ubuntu confines them, and RootlessKit needs a
profile at `/etc/apparmor.d/usr.local.bin.rootlesskit`. Without it, `containerd-rootless-setuptool.sh
install` fails with `fork/exec /proc/self/exe: permission denied`, which says nothing about the
cause.

**`user.home` inside the user namespace.** The demo runs under
`nsenter -U --preserve-credentials`, where the process is uid 0. Java reads `user.home` from the
passwd entry for the effective uid rather than from `HOME`, so it resolves to `/root`, which the
user cannot write, and the demo dies creating its state directory. The workflow passes
`-Duser.home` explicitly.

**Multipass as a snap.** `multipass transfer` cannot read files under a private `/tmp`. Stage
anything you transfer by hand somewhere the snap can see, such as your home directory.

## What the scenarios cover

`src/e2e/java/io/nanofaas/containerd/e2e/E2eRun.java`, nine of them: talking to containerd,
pulling and inspecting an image, two containers on a shared network reaching each other by name
with DNS from the CNI configuration, reading back what a container printed, exec (including stdin
and what the exec'd process inherits from the container), resource limits and the image's own
configuration, events as they happen, host networking, and that nothing is left behind.
