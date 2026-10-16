import type {AssetType, RecordingType} from '../enums/';

export type AssetDto = {
    'AssetController/DEFAULT_ASSET': {
        readonly id: string;
        /**
         * 远端文件定位；来源由相册所属账号决定。
         */
        readonly remoteKey: string;
        /**
         * Historical versions remain for download history; only current versions are scheduled.
         */
        readonly currentVersion: boolean;
        /**
         * Confirmed removal from the active provider library, shared by all memberships/versions.
         */
        readonly cloudDeleted: boolean;
        readonly logicalResourceKey: string;
        /**
         * Xiaomi cloud hint only; actual dynamic structure is determined from original bytes.
         */
        readonly motionPhotoHint: boolean;
        readonly fileName: string;
        readonly type: AssetType;
        readonly recordingType?: RecordingType | undefined;
        readonly dateTaken: string;
        /**
         * 来源返回的文件校验值；解码格式由账号来源决定。
         */
        readonly checksum: string;
        readonly mimeType: string;
        readonly title: string;
        readonly size: number;
    }
}
