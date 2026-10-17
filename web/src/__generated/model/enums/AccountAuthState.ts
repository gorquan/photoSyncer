export const AccountAuthState_CONSTANTS = [
    'UNKNOWN',
    'READY',
    'MFA_REQUIRED',
    'SESSION_EXPIRED',
    'CHECK_FAILED'
] as const;
/**
 * Latest recorded provider authentication observation, not a live guarantee.
 */
export type AccountAuthState = typeof AccountAuthState_CONSTANTS[number];
