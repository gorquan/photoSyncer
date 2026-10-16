import type {MediaDeletionResult} from './';

/**
 * Cleanup evidence, independent of whether execution has ended.
 */
export interface CloudDeletionReport {
    readonly status: string;
    readonly checkedGroups: number;
    readonly newlyDeletedGroups: number;
    readonly restoredGroups: number;
    readonly results: ReadonlyArray<MediaDeletionResult>;
    readonly message?: string | undefined;
    readonly exportsDeleted: number;
    readonly originalsDeleted: number;
    readonly deferredFiles: number;
    readonly conflictCount: number;
}
