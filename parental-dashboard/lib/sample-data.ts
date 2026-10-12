/**
 * Sample data for SendWise Parental Dashboard
 * For testing and demonstration purposes.
 *
 * Categories reflect the paper's canonical 6:
 *   harassment | threats | hate_speech | sexual_content | self_harm | sensitive_image
 */

import { Incident, Child, DashboardStats, CategoryStats } from './types';

export const sampleChild: Child = {
  id: 'child-001',
  name: 'Alex',
  age: 13,
};

export const sampleIncidents: Incident[] = [
  {
    id: 'inc-001',
    childId: 'child-001',
    timestamp: new Date('2026-01-28T15:45:00'),
    platform: 'instagram',
    category: 'self_harm',
    severity: 'urgent',
    action: 'edited',
    detections: [
      {
        type: 'urgent_suicide_threat',
        matches: ['immediate harm expression']
      }
    ],
    recommendation: '🚨 URGENT: Contact emergency services (988 Suicide Hotline or 911) immediately',
    resources: [
      '988 Suicide & Crisis Lifeline: Call or text 988',
      'Crisis Text Line: Text HOME to 741741',
      'If immediate danger: Call 911'
    ]
  },
  {
    id: 'inc-002',
    childId: 'child-001',
    timestamp: new Date('2026-01-28T14:15:00'),
    platform: 'whatsapp',
    category: 'harassment',
    severity: 'critical',
    action: 'sent_anyway',
    detections: [
      {
        type: 'insulting_language',
        matches: ['name-calling pattern']
      }
    ],
    recommendation: '🚨 CRITICAL: Talk to child about respectful communication',
    resources: [
      'Discuss impact of harassing messages',
      'Review recent conversations',
      'Set clear expectations about kindness online'
    ]
  },
  {
    id: 'inc-003',
    childId: 'child-001',
    timestamp: new Date('2026-01-28T13:30:00'),
    platform: 'discord',
    category: 'threats',
    severity: 'critical',
    action: 'blocked',
    detections: [
      {
        type: 'violent_threat',
        matches: ['explicit threat of harm']
      },
      {
        type: 'intent_pattern',
        matches: ['"I will ___ you" construction']
      }
    ],
    recommendation: '🚨 CRITICAL: Threatening language detected - intervene immediately',
    resources: [
      'Talk to child IMMEDIATELY',
      'Understand the conflict driving the threat',
      'Review all recent messages',
      'Consider reporting to platform trust & safety'
    ]
  },
  {
    id: 'inc-004',
    childId: 'child-001',
    timestamp: new Date('2026-01-28T12:00:00'),
    platform: 'snapchat',
    category: 'sexual_content',
    severity: 'high',
    action: 'edited',
    detections: [
      {
        type: 'sexual_solicitation',
        matches: ['explicit sexual reference']
      }
    ],
    recommendation: '⚠️ HIGH: Sexual content detected - conversation needed',
    resources: [
      'Have an age-appropriate conversation about sexual content online',
      'Discuss consent and boundaries',
      'Review who they are messaging',
      'Consider reporting to CyberTipline (NCMEC): 1-800-843-5678'
    ]
  },
  {
    id: 'inc-005',
    childId: 'child-001',
    timestamp: new Date('2026-01-28T10:30:00'),
    platform: 'tiktok',
    category: 'harassment',
    severity: 'medium',
    action: 'sent_anyway',
    detections: [
      {
        type: 'harassment',
        matches: ['insulting language']
      }
    ],
    recommendation: '⚠️ Talk to child about online kindness',
    resources: []
  },
  {
    id: 'inc-006',
    childId: 'child-001',
    timestamp: new Date('2026-01-27T20:15:00'),
    platform: 'instagram',
    category: 'self_harm',
    severity: 'high',
    action: 'edited',
    detections: [
      {
        type: 'severe_depression',
        matches: ['hopelessness expression']
      }
    ],
    recommendation: '⚠️ HIGH PRIORITY: Schedule professional help within 24-48 hours',
    resources: [
      'Contact therapist or counselor',
      'Talk to child about feelings',
      'Monitor closely'
    ]
  },
  {
    id: 'inc-007',
    childId: 'child-001',
    timestamp: new Date('2026-01-27T18:45:00'),
    platform: 'discord',
    category: 'hate_speech',
    severity: 'high',
    action: 'sent_anyway',
    detections: [
      {
        type: 'slur',
        matches: ['discriminatory term']
      }
    ],
    recommendation: '⚠️ HIGH: Hate speech detected - address immediately',
    resources: [
      'Discuss the harm of slurs and discriminatory language',
      'Review online safety and community rules'
    ]
  },
  {
    id: 'inc-008',
    childId: 'child-001',
    timestamp: new Date('2026-01-27T16:20:00'),
    platform: 'whatsapp',
    category: 'threats',
    severity: 'high',
    action: 'blocked',
    detections: [
      {
        type: 'intimidation',
        matches: ['coercive language']
      }
    ],
    recommendation: '⚠️ CONCERN: Intimidating language toward another user',
    resources: [
      'Talk to child about the situation',
      'Understand who the message was directed at'
    ]
  },
  {
    id: 'inc-009',
    childId: 'child-001',
    timestamp: new Date('2026-01-27T11:05:00'),
    platform: 'snapchat',
    category: 'sensitive_image',
    severity: 'medium',
    action: 'blocked',
    detections: [
      {
        type: 'on_device_nsfw_detection',
        matches: ['image flagged by on-device nudity classifier']
      }
    ],
    recommendation: '⚠️ Image flagged by on-device NSFW detection - conversation needed',
    resources: [
      'Have an age-appropriate conversation about sharing images online',
      'Review who they are messaging',
      'Discuss consent and boundaries around images'
    ]
  }
];

export const sampleStats: DashboardStats = {
  totalIncidents: 45,
  criticalIncidents: 3,
  highPriorityIncidents: 8,
  messagesPrevented: 42,
  lastIncidentTime: new Date('2026-01-28T15:45:00')
};

export const sampleCategoryStats: CategoryStats[] = [
  {
    category: 'harassment',
    count: 18,
    trend: 'down',
    mostRecentSeverity: 'medium'
  },
  {
    category: 'threats',
    count: 8,
    trend: 'stable',
    mostRecentSeverity: 'critical'
  },
  {
    category: 'hate_speech',
    count: 6,
    trend: 'down',
    mostRecentSeverity: 'high'
  },
  {
    category: 'sexual_content',
    count: 4,
    trend: 'up',
    mostRecentSeverity: 'high'
  },
  {
    category: 'self_harm',
    count: 2,
    trend: 'up',
    mostRecentSeverity: 'urgent'
  },
  {
    category: 'sensitive_image',
    count: 3,
    trend: 'up',
    mostRecentSeverity: 'medium'
  }
];
