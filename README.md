# java-watch

a java file watcher that works across platforms and supports recursion, single file watches, and tries to make sure no events are missed. Where possible it uses Java's NIO WatchService.

## Features

Features:

- monitor a single file (or directory) for changes
- monitor a directory for changes to its direct descendants
- monitor a directory for changes for all its descendants (aka recursive directory watch)
- edge cases dealt with:
  - recursive watches will also continue in new directories
  - multiple watches for the same directory are merged to avoid overloading the kernel
  - events are processed in a configurable worker pool
  - when an overflow happens, automatically approximate the events that were
    missed using a configurable auto-handler

Planned features:

- Avoid poll based watcher in macOS/OSX that only detects changes every 2 seconds (see [#4](https://github.com/SWAT-engineering/java-watch/issues/4))
- Support single file watches natively in linux (see [#11](https://github.com/SWAT-engineering/java-watch/issues/11))
- Monitor only specific events (such as only CREATE events)

## Usage

Import dependency in pom.xml:

```xml
<dependency>
    <groupId>engineering.swat</groupId>
    <artifactId>java-watch</artifactId>
    <version>${java-watch-version}</version>
</dependency>
```

Start using java-watch:

```java
var directory = Path.of("tmp", "test-dir");
var watcherSetup = Watcher.watch(directory, WatchScope.PATH_AND_CHILDREN)
    .withExecutor(Executors.newCachedThreadPool()) // optionally configure a custom thread pool
    .approximate(OnOverflow.DIRTY) // optionally configure an auto-handler for overflows
    .on(watchEvent -> {
        System.err.println(watchEvent);
    });

try(var active = watcherSetup.start()) {
    System.out.println("Monitoring files, press any key to stop");
    System.in.read();
}
// after active.close(), the watch is stopped and
// no new events will be scheduled on the threadpool
```

## Related work

Before starting this library, we wanted to use existing libraries, but they all lacked proper support for recursive file watches, single file watches or lacked configurability. This library now has a growing collection of tests and a small API that should allow for future improvements without breaking compatibility.

The following section describes the related work research on the libraries and underlying limitations.

After reading the documentation of the following discussion on file system watches:

- [Paul Millr's nodejs chokidar](https://github.com/paulmillr/chokidar)
- [Enrico Maria Crisostomo's c++ fswatch/libfswatch](https://github.com/emcrisostomo/fswatch)
- [work by rjeczalik in the go notify package](https://pkg.go.dev/github.com/rjeczalik/notify)
- [Greg Methvin's java library directory-watcher](https://github.com/gmethvin/directory-watcher)
- [Pathikrit Bhowmick's scala library better-files](https://github.com/pathikrit/better-files)
- [Java's documentation on a recursive file watcher](https://docs.oracle.com/javase/tutorial/displayCode.html?code=https://docs.oracle.com/javase/tutorial/essential/io/examples/WatchDir.java)
- [openjdk's PR 10140/8293067 to try and use FSEventStream on macOS](https://github.com/openjdk/jdk/pull/10140)
- [nodejs watch function caveats](https://nodejs.org/docs/latest/api/fs.html#caveats)
- [Overview in 2012 by regedit](https://lists.qt-project.org/pipermail/development/2012-July/005279.html)

We can come to the following conclusion: file system watches are hard and have platform specific limitations.
In summary:

| OS | API | file | directory | recursive directory | overflow | network | notes|
| -- | -- | -- | --- | -- |---|--| -- |
| Windows | [`ReadDirectoryChangesW`](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-readdirectorychangesw) | ❌ | ✅ | ✅ | generic error marker | some, error in case not supported | hard to correctly setup (there are multiple ways to get updates), can be chatty with it's events. can lock the directory it's monitoring. |
| Linux | [inotify](https://man7.org/linux/man-pages/man7/inotify.7.html) | ✅ | ✅ | ❌ | generic error marker | only local changes, no error | note that the new [fanotify](https://man7.org/linux/man-pages/man7/fanotify.7.html) supports recursive watches, but only at mount points, not for arbitrary directories. |
| macOS & BSD | [kqueue](https://man.freebsd.org/cgi/man.cgi?kqueue) | ? | ✅ | ❌ | can quickly run out of file descriptors | ? | implementing recursive directory watches this way will quickly run out of file descriptors |
| macOS | [FSEvents](https://developer.apple.com/documentation/coreservices/file_system_events) | ✅ |✅ | ✅ | generic error marker | ? | Some report it works great, but openjdk stopped doing this direction of the implementation as it consistently failed a test with a lot of IO operations and register and unregisters of watches. Reporting that the API would just stop reporting any events |

To avoid licensing conflicts we have not read the source code of any of these libraries/frameworks. The related work study is based purely on public documentation and discussions.
