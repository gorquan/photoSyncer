/**
 * Counts refer to files removed during this invocation, including interrupted-operation retries.
 */
export interface MediaDeletionResult {
    readonly groupKey: string;
    readonly status: string;
    readonly exportsDeleted: number;
    readonly originalsDeleted: number;
    readonly deferredFiles: number;
    readonly conflicts: ReadonlyArray<string>;
}
