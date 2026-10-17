import type {AccountAuthState, CloudProvider} from '../enums/';

export type ProviderAccountDto = {
    'ProviderAccountController/DEFAULT_PROVIDER_ACCOUNT': {
        readonly id: number;
        readonly provider: CloudProvider;
        readonly nickname: string;
        readonly userId: string;
        readonly authState: AccountAuthState;
        readonly authCheckedAt?: string | undefined;
    }
}
