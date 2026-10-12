/**
 * SelfHarmDetector - Detects self-harm and suicide ideation
 *
 * CRITICAL MODULE for teen safety. Identifies:
 * - Suicide ideation and threats
 * - Self-injury references
 * - Depression indicators
 * - Hopelessness expressions
 *
 * Severity Levels:
 * - URGENT: Immediate suicide threat (contact authorities)
 * - CRITICAL: Strong suicidal ideation (immediate intervention)
 * - HIGH: Self-harm or severe depression (prompt action)
 * - MEDIUM: Depression indicators (monitor closely)
 *
 * IMPORTANT: This module is for ALERTING parents/counselors, not diagnosis.
 * Always recommend professional help for mental health concerns.
 *
 * Version: 1.0.0
 */

(function(root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.SelfHarmDetector = factory();
  }
}(typeof self !== 'undefined' ? self : this, function() {
  'use strict';

  const SelfHarmDetector = {

    // Urgent suicide threats (immediate danger)
    urgentPatterns: [
      /\b(gonna|going to|will|plan to)\s+(kill myself|end (it|my life)|commit suicide)\b/gi,
      /\b(tonight|today|tomorrow|this weekend)\b.*\b(kill myself|end (it|my life)|commit suicide)\b/gi,
      /\bsuicide note\b/gi,
      /\bready to die\b/gi,
      /\b(goodbye|farewell|last (time|message|words))\b.*\b(everyone|world|life)\b/gi,
      /\bgoodbye (cruel )?world\b/gi
    ],

    // Critical suicidal ideation
    criticalPatterns: [
      /\b(want to|wanna) (die|kill myself|end (it|my life))\b/gi,
      /\b(wish|hope) i (was|were) dead\b/gi,
      /\blife (isn't|is not|ain't) worth (living|it)\b/gi,
      /\beveryone (would be|is) better (off )?without me\b/gi,
      /\b(can't|cannot|cant) (take|do|handle) (this|it) anymore\b/gi,
      /\b(no|don't have|dont have) (reason|point) to live\b/gi,
      /\b(tired|sick) of (living|life|being alive)\b/gi,
      /\bjust want (it|everything) to end\b/gi
    ],

    // Self-harm references
    selfHarmPatterns: [
      /\b(cutting|cut) myself\b/gi,
      /\b(gonna|going to) (cut|harm|hurt) myself\b/gi,
      /\bself(-| )?harm(ing)?\b/gi,
      /\b(razor|blade|knife).*\b(wrist|arm|leg|skin)\b/gi,
      /\b(burn|burning) myself\b/gi,
      /\bhurt myself (again|tonight|today)\b/gi
    ],

    // Severe depression indicators
    depressionPatterns: [
      /\b(i'm|i am|im) (so |really )?(worthless|useless|pathetic|a failure)\b/gi,
      /\bnobody (cares|loves|likes) (about )?me\b/gi,
      /\beveryone hates me\b/gi,
      /\b(i hate|hate being) (myself|alive|me)\b/gi,
      /\b(feel|feeling) (so |really )?(empty|numb|hopeless|broken)\b/gi,
      /\bnothing matters (anymore)?\b/gi,
      /\b(can't|cannot|cant) (feel|do) anything\b/gi,
      /\bdisappear forever\b/gi,
      /\b(i wish|wish) i (was|were) (never born|dead)\b/gi
    ],

    // General mental health concerns
    concernPatterns: [
      /\b(depressed|depression|sad|miserable) (all the time|every day|always)\b/gi,
      /\b(can't|cannot|cant) (sleep|eat|get out of bed)\b/gi,
      /\blost (all |my )?hope\b/gi,
      /\bgiving up on (everything|life)\b/gi,
      /\bno one understands( me)?\b/gi,
      /\b(i'm|im) (so |really )?alone\b/gi
    ],

    /**
     * Analyze text for self-harm indicators
     * @param {string} text - Message text
     * @returns {Object} Self-harm analysis result
     */
    analyze: function(text) {
      const detections = [];
      let riskScore = 0;
      let severity = 'none';
      let urgencyLevel = 'none';

      const lowerText = text.toLowerCase();

      // Check urgent patterns (highest priority)
      const urgentMatches = this.checkPatterns(text, this.urgentPatterns);
      if (urgentMatches.length > 0) {
        detections.push({
          category: 'urgent_suicide_threat',
          patterns: urgentMatches,
          severity: 'urgent'
        });
        riskScore = 1.0;
        severity = 'urgent';
        urgencyLevel = 'immediate';
      }

      // Check critical patterns
      const criticalMatches = this.checkPatterns(text, this.criticalPatterns);
      if (criticalMatches.length > 0) {
        detections.push({
          category: 'suicidal_ideation',
          patterns: criticalMatches,
          severity: 'critical'
        });
        riskScore = Math.max(riskScore, 0.95);
        if (severity === 'none') {
          severity = 'critical';
          urgencyLevel = 'urgent';
        }
      }

      // Check self-harm patterns
      const selfHarmMatches = this.checkPatterns(text, this.selfHarmPatterns);
      if (selfHarmMatches.length > 0) {
        detections.push({
          category: 'self_harm',
          patterns: selfHarmMatches,
          severity: 'high'
        });
        riskScore = Math.max(riskScore, 0.9);
        if (severity === 'none' || severity === 'high') {
          severity = 'high';
          urgencyLevel = 'high';
        }
      }

      // Check depression patterns
      const depressionMatches = this.checkPatterns(text, this.depressionPatterns);
      if (depressionMatches.length > 0) {
        detections.push({
          category: 'severe_depression',
          patterns: depressionMatches,
          severity: 'high'
        });
        riskScore = Math.max(riskScore, 0.8);
        if (severity === 'none') {
          severity = 'high';
          urgencyLevel = 'high';
        }
      }

      // Check general concerns
      const concernMatches = this.checkPatterns(text, this.concernPatterns);
      if (concernMatches.length > 0) {
        detections.push({
          category: 'mental_health_concern',
          patterns: concernMatches,
          severity: 'medium'
        });
        riskScore = Math.max(riskScore, 0.6);
        if (severity === 'none') {
          severity = 'medium';
          urgencyLevel = 'moderate';
        }
      }

      return {
        hasSelfHarmRisk: riskScore > 0,
        riskScore: riskScore,
        severity: severity,
        urgencyLevel: urgencyLevel,
        detections: detections,
        category: 'self_harm',
        requiresImmediateAction: urgencyLevel === 'immediate'
      };
    },

    /**
     * Check text against pattern list
     */
    checkPatterns: function(text, patterns) {
      const matches = [];
      for (const pattern of patterns) {
        const result = text.match(pattern);
        if (result) {
          matches.push({
            pattern: pattern.source,
            matched: result
          });
        }
      }
      return matches;
    },

    /**
     * Get recommended action for parents
     */
    getRecommendedAction: function(analysis) {
      if (!analysis.hasSelfHarmRisk) {
        return {
          action: 'none',
          message: null
        };
      }

      switch (analysis.urgencyLevel) {
        case 'immediate':
          return {
            action: 'contact_authorities',
            message: '🚨 URGENT: Contact emergency services (988 Suicide Hotline or 911) immediately',
            resources: [
              '988 Suicide & Crisis Lifeline: Call or text 988',
              'Crisis Text Line: Text HOME to 741741',
              'If immediate danger: Call 911'
            ]
          };

        case 'urgent':
          return {
            action: 'immediate_intervention',
            message: '🚨 CRITICAL: Talk to your child immediately and contact a mental health professional',
            resources: [
              '988 Suicide & Crisis Lifeline',
              'Schedule emergency therapy appointment',
              'Contact school counselor',
              'Stay with child, remove dangerous items'
            ]
          };

        case 'high':
          return {
            action: 'prompt_intervention',
            message: '⚠️ HIGH PRIORITY: Schedule professional help within 24-48 hours',
            resources: [
              'Contact therapist or counselor',
              'Talk to child about feelings',
              'Monitor closely',
              'Remove access to harmful items'
            ]
          };

        case 'moderate':
          return {
            action: 'monitor_and_support',
            message: '⚠️ CONCERN: Talk to your child and monitor mental health',
            resources: [
              'Have a supportive conversation',
              'Consider scheduling therapy',
              'Watch for escalation',
              'Provide emotional support'
            ]
          };

        default:
          return {
            action: 'none',
            message: null
          };
      }
    },

    /**
     * Get parent notification
     */
    getParentNotification: function(analysis) {
      if (!analysis.hasSelfHarmRisk) {
        return null;
      }

      const action = this.getRecommendedAction(analysis);
      const categories = analysis.detections.map(d => d.category).join(', ');

      return {
        title: analysis.urgencyLevel === 'immediate' ? '🚨 EMERGENCY ALERT' : '🚨 Mental Health Alert',
        message: `Self-harm risk detected (${categories})`,
        severity: analysis.severity,
        urgency: analysis.urgencyLevel,
        action: action.action,
        recommendation: action.message,
        resources: action.resources || []
      };
    },

    /**
     * Should message be blocked?
     */
    shouldBlock: function(analysis) {
      // Block urgent suicide threats to prevent harm
      // But still send notification to parent
      return analysis.urgencyLevel === 'immediate';
    }

  };

  return SelfHarmDetector;
}));
