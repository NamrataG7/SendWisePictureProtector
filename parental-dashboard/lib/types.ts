/**
 * Type definitions for SendWise Parental Dashboard
 */

export type SeverityLevel = 'urgent' | 'critical' | 'high' | 'medium' | 'low' | 'none';

export type IncidentCategory =
  | 'harassment'
  | 'threats'
  | 'hate_speech'
  | 'sexual_content'
  | 'self_harm'
  | 'sensitive_image';

export type Platform =
  | 'instagram'
  | 'discord'
  | 'whatsapp'
  | 'facebook'
  | 'twitter'
  | 'tiktok'
  | 'snapchat'
  | 'reddit'
  | 'other';

export type ActionTaken =
  | 'blocked'
  | 'edited'
  | 'sent_anyway'
  | 'cancelled';

export interface Incident {
  id: string;
  childId: string;
  timestamp: Date;
  platform: Platform;
  category: IncidentCategory;
  severity: SeverityLevel;
  // Privacy guarantee (SendWise paper §Privacy by Design):
  // Message content is analyzed on-device only and NEVER leaves the child's device.
  // This includes both text (toxicity detection) and images (on-device NSFW/nudity
  // scanning for the 'sensitive_image' category). Therefore no `detectedText` /
  // message-content / image-content field exists on Incident — metadata only.
  action: ActionTaken;
  /**
   * True when the parent has "Mark Reviewed"-ed this incident.
   * Reviewed incidents are hidden from the home incident feed but preserved
   * in Insights / CSV export / historical trends.
   */
  reviewed?: boolean;
  detections: {
    type: string;
    matches: string[];
  }[];
  recommendation: string;
  resources?: string[];
}

export interface Child {
  id: string;
  name: string;
  age: number;
  avatarUrl?: string;
}

export interface DashboardStats {
  totalIncidents: number;
  criticalIncidents: number;
  highPriorityIncidents: number;
  messagesPrevented: number;
  lastIncidentTime?: Date;
}

export interface CategoryStats {
  category: IncidentCategory;
  count: number;
  trend: 'up' | 'down' | 'stable';
  mostRecentSeverity: SeverityLevel;
}
