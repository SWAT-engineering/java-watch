# java-watch
a java file watcher that works across platforms and supports recursion and single file watches


## Related work
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
