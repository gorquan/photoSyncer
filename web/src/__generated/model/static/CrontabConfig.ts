export interface CrontabConfig {
    readonly expression: string;
    readonly timeZone: string;
    readonly targetPath: string;
    readonly downloadImages: boolean;
    readonly downloadVideos: boolean;
    readonly rewriteExifTime: boolean;
    readonly rewriteExifTimeZone?: string | undefined;
    readonly skipExistingFile: boolean;
    readonly rewriteFileSystemTime: boolean;
    readonly checkSha1: boolean;
    readonly fetchFromDbSize: number;
    readonly downloaders: number;
    readonly verifiers: number;
    readonly exifProcessors: number;
    readonly fileTimeWorkers: number;
    readonly notify: boolean;
    readonly downloadLivePhotos: boolean;
    /**
     * Use targetPath as the shared export root. Every layout remains isolated by provider/account/library.
     */
    readonly synologyPhotos: boolean;
    /**
     * Permanently remove owned exports and originals after authoritative cloud deletion.
     */
    readonly syncCloudDeletes: boolean;
}
