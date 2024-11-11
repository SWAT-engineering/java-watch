package engineering.swat.watch;

/**
 * Configure the depth of the events you want to receive for a given path
 */
public enum WatchScope {
    /**
     * <p>Watch changes to a single file or (metadata of) a single directory. </p>
     *
     * <p>Note, depending on the platform you can receive events for a directory
     * in case of these events: </p>
     * <ul>
     *   <li>a MODIFIED caused by the creation of a nested file/directory </li>
     *   <li>a MODIFIED caused by the deletion of a nested file/directory </li>
     *   <li>a MODIFIED of its own metadata</li>
     * </ul>
     *
     * <p>In most cases when Path is a Directory you're interested in which nested entries changes, in that case use {@link #PATH_AND_CHILDREN} or {@link #PATH_AND_ALL_DESCENDANTS}. </p>
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
