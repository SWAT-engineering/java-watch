package engineering.swat.watch;

/**
 * Configure the depth of the events you want to receive for a given path
 */
public enum WatchScope {
    /**
     *  Watch changes to a single file or (metadata of) a directory
     */
    PATH_ONLY,
    /**
     * Watch changes to (metadata of) a directory and its content,
     * non-recursively. That is, changes to the content of nested directories
     * are not watched.
     */
    PATH_AND_CHILDREN,
    /**
     * Watch changes to (metadata of) a directory and its content, recursively.
     * That is, changes to the content of nested directories are also watched.
     */
    PATH_AND_ALL_DESCENDANTS
}
