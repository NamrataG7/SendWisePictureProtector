/**
 * Zod schemas for API request validation.
 *
 * Privacy rule: violation ingest MUST NEVER include message content.
 * The API route additionally rejects any payload containing `text`,
 * `message`, or `content` fields as a defense-in-depth guard.
 */

import { z } from 'zod';

// Mirrors IncidentCategory in lib/types.ts
export const IncidentCategoryEnum = z.enum([
  'harassment',
  'threats',
  'hate_speech',
  'sexual_content',
  'self_harm',
  'sensitive_image',
]);
export type IncidentCategoryT = z.infer<typeof IncidentCategoryEnum>;

export const SeverityEnum = z.enum(['low', 'medium', 'high']);
export type SeverityT = z.infer<typeof SeverityEnum>;

export const ActionEnum = z.enum(['edited', 'sent_anyway', 'blocked', 'cancelled']);
export type ActionT = z.infer<typeof ActionEnum>;

const HEX64 = /^[a-f0-9]{64}$/i;

export const ViolationIngestSchema = z
  .object({
    user_id_hash: z
      .string()
      .regex(HEX64, 'user_id_hash must be 64 hex characters (SHA-256)'),
    timestamp: z
      .string()
      .refine((s) => !Number.isNaN(Date.parse(s)), 'timestamp must be ISO 8601'),
    category: IncidentCategoryEnum,
    severity: SeverityEnum,
    action: ActionEnum,
    session_id: z.string().min(1),
  })
  .strict();

export type ViolationIngest = z.infer<typeof ViolationIngestSchema>;

// Note: pairing redeem body schema is now inlined in the redeem route
// because parent_id is derived server-side from the session, not the client body.
// See parental-dashboard/app/api/pairing/redeem/route.ts.

export const PairingGenerateSchema = z
  .object({
    user_id_hash: z.string().regex(HEX64, 'user_id_hash must be 64 hex characters'),
  })
  .strict();

export type PairingGenerate = z.infer<typeof PairingGenerateSchema>;

/**
 * Fields that must never appear in a violation ingest payload.
 * Enforced by the route handler before schema validation.
 */
export const FORBIDDEN_CONTENT_FIELDS = ['text', 'message', 'content'] as const;
