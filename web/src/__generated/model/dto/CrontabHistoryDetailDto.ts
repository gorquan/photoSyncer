import type {VerificationStatus} from '../enums/';

export type CrontabHistoryDetailDto = {
    'CrontabController/CRONTAB_HISTORY_DETAIL_FETCHER': {
        readonly id: number;
        readonly downloadTime: string;
        readonly filePath: string;
        readonly downloadCompleted: boolean;
        readonly sha1Verified: boolean;
        readonly verificationStatus: VerificationStatus;
        readonly localSha256?: string | undefined;
        readonly verifiedSize?: number | undefined;
        readonly publicationStatus: string;
        readonly archivePath?: string | undefined;
        readonly outputSha256?: string | undefined;
        readonly exportVersion: number;
        readonly exifFilled: boolean;
        readonly fsTimeUpdated: boolean;
        readonly message?: string | undefined;
        readonly asset: {
            readonly id: string;
            readonly album: {
                readonly id: number;
                readonly name: string;
            };
        };
    }
}
