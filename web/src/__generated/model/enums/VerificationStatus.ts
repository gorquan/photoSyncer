export const VerificationStatus_CONSTANTS = [
    'LEGACY',
    'PENDING',
    'SKIPPED',
    'SOURCE_VERIFIED',
    'TRANSPORT_VERIFIED',
    'FAILED'
] as const;
/**
 * Verification evidence for the downloaded original, before optional metadata changes.
 */
export type VerificationStatus = typeof VerificationStatus_CONSTANTS[number];
