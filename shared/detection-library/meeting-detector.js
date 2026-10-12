/**
 * MeetingDetector - Detects when child agrees to meet strangers
 *
 * Identifies dangerous in-person meeting arrangements:
 * - Agreeing to meet someone from online
 * - Sharing location for meetups
 * - Planning to sneak out
 * - Meeting without parent knowledge
 * - Time/place arrangements
 *
 * CRITICAL for preventing child predation and kidnapping.
 *
 * Used by Parental Dashboard to alert parents immediately.
 *
 * Version: 1.0.0
 */

(function(root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.MeetingDetector = factory();
  }
}(typeof self !== 'undefined' ? self : this, function() {
  'use strict';

  const MeetingDetector = {

    // Agreement to meet patterns
    agreementPatterns: [
      /\b(ok|okay|sure|yes|yeah|yea|alright)\s+(let's|lets|we can|i'll|ill)\s+meet\b/gi,
      /\b(i'll|ill|i can)\s+meet\s+you\b/gi,
      /\b(see you|meet you)\s+(there|at|tomorrow|tonight|today)\b/gi,
      /\b(down to|want to|wanna)\s+meet\s+(up|you|irl|in person)\b/gi,
      /\blet's meet (up|irl|in person)\b/gi
    ],

    // Location sharing patterns
    locationPatterns: [
      /\b(meet me|meet you|i'll be|ill be)\s+at\s+(the )?(mall|park|store|coffee shop|starbucks|mcdonalds)\b/gi,
      /\b(here's|heres|my)\s+(location|address|where i am)\b/gi,
      /\bpick me up (at|from)\b/gi,
      /\b(meet|see you) (behind|near|at)\s+the\b/gi,
      /\bi live (at|near|by)\b/gi
    ],

    // Sneaking out patterns
    sneakingPatterns: [
      /\b(sneak out|sneaking out)\b/gi,
      /\bmy (parents|mom|dad) (won't|wont|don't|dont) know\b/gi,
      /\b(when|after) (my parents|they) (sleep|go to bed|leave)\b/gi,
      /\b(climb|sneak) out (my |the )?window\b/gi,
      /\bleave without (them|my parents) knowing\b/gi
    ],

    // Time arrangements
    timePatterns: [
      /\bmeet (at|around)\s+\d{1,2}(:\d{2})?\s*(am|pm|o'clock)?\b/gi,
      /\b(tonight|tomorrow|this weekend|after school|saturday|sunday)\s+(at|around|@)\s+\d/gi,
      /\b(see you|meet you)\s+(tonight|tomorrow|later|after school)\b/gi
    ],

    // Hiding from parents
    hidingPatterns: [
      /\bdon't tell (my parents|anyone|them)\b/gi,
      /\bkeep (it|this) (secret|between us)\b/gi,
      /\b(nobody|no one) (needs to|has to|should) know\b/gi,
      /\bdon't let (my parents|them|anyone) find out\b/gi
    ],

    // Getting in vehicle
    vehiclePatterns: [
      /\b(pick me up|drive me|give me a ride)\b/gi,
      /\bget in (your |the )?(car|truck|van)\b/gi,
      /\b(meet|wait) (in|at) (your |the )?(car|parking lot)\b/gi
    ],

    /**
     * Analyze text for meeting arrangements
     * @param {string} text - Message text
     * @returns {Object} Meeting detection analysis result
     */
    analyze: function(text) {
      const detections = [];
      let riskScore = 0;
      let severity = 'none';

      // Check agreement patterns
      const agreementDetection = this.checkCategory(text, this.agreementPatterns, 'meeting_agreement');
      if (agreementDetection.found) {
        detections.push(agreementDetection);
        riskScore = Math.max(riskScore, 0.8);
      }

      // Check location sharing
      const locationDetection = this.checkCategory(text, this.locationPatterns, 'location_sharing');
      if (locationDetection.found) {
        detections.push(locationDetection);
        riskScore = Math.max(riskScore, 0.9);
      }

      // Check sneaking out
      const sneakingDetection = this.checkCategory(text, this.sneakingPatterns, 'sneaking_out');
      if (sneakingDetection.found) {
        detections.push(sneakingDetection);
        riskScore = Math.max(riskScore, 0.95); // Very serious
      }

      // Check time arrangements
      const timeDetection = this.checkCategory(text, this.timePatterns, 'time_arrangement');
      if (timeDetection.found) {
        detections.push(timeDetection);
        riskScore = Math.max(riskScore, 0.75);
      }

      // Check hiding from parents
      const hidingDetection = this.checkCategory(text, this.hidingPatterns, 'hiding_from_parents');
      if (hidingDetection.found) {
        detections.push(hidingDetection);
        riskScore = Math.max(riskScore, 0.95); // Very serious - predator behavior
      }

      // Check vehicle patterns
      const vehicleDetection = this.checkCategory(text, this.vehiclePatterns, 'vehicle_arrangement');
      if (vehicleDetection.found) {
        detections.push(vehicleDetection);
        riskScore = Math.max(riskScore, 0.95); // Critical - kidnapping risk
      }

      // Amplify score if multiple indicators present
      if (detections.length >= 2) {
        riskScore = Math.min(riskScore * 1.2, 1.0);
      }

      // Determine severity
      if (riskScore >= 0.9) severity = 'critical';
      else if (riskScore >= 0.7) severity = 'high';
      else if (riskScore >= 0.5) severity = 'medium';
      else if (riskScore > 0) severity = 'low';

      return {
        hasMeetingRisk: riskScore > 0,
        riskScore: riskScore,
        severity: severity,
        detections: detections,
        // Auxiliary detector — does not map to the canonical 5-category
        // taxonomy (harassment/threats/hate_speech/sexual_content/self_harm).
        category: 'none',
        requiresImmediateAction: riskScore >= 0.9
      };
    },

    /**
     * Check text against category patterns
     */
    checkCategory: function(text, patterns, categoryName) {
      const matches = [];

      for (const pattern of patterns) {
        const result = text.match(pattern);
        if (result) {
          matches.push(...result);
        }
      }

      if (matches.length > 0) {
        return {
          found: true,
          type: categoryName,
          matches: matches,
          count: matches.length
        };
      }

      return { found: false };
    },

    /**
     * Get recommended parent action
     */
    getRecommendedAction: function(analysis) {
      if (!analysis.hasMeetingRisk) {
        return {
          action: 'none',
          message: null
        };
      }

      const types = analysis.detections.map(d => d.type);

      // Critical: Sneaking out or hiding from parents
      if (types.includes('sneaking_out') || types.includes('hiding_from_parents')) {
        return {
          action: 'immediate_intervention',
          message: '🚨 CRITICAL: Child planning to meet stranger secretly',
          recommendations: [
            'Talk to child IMMEDIATELY',
            'Ask who they\'re talking to online',
            'Review all recent messages',
            'Contact local authorities if predator suspected',
            'Prevent child from leaving house unsupervised',
            'Consider reporting to CyberTipline (NCMEC): 1-800-843-5678'
          ],
          urgency: 'immediate'
        };
      }

      // Critical: Vehicle arrangements (kidnapping risk)
      if (types.includes('vehicle_arrangement')) {
        return {
          action: 'immediate_intervention',
          message: '🚨 CRITICAL: Child arranging vehicle meetup with stranger',
          recommendations: [
            'Intervene IMMEDIATELY',
            'Contact authorities if adult predator suspected',
            'Review all communications',
            'Talk about stranger danger',
            'Report to CyberTipline if appropriate'
          ],
          urgency: 'immediate'
        };
      }

      // High: Location sharing
      if (types.includes('location_sharing')) {
        return {
          action: 'urgent_conversation',
          message: '⚠️ HIGH: Child sharing location for meetup',
          recommendations: [
            'Talk to child immediately',
            'Verify who they\'re meeting',
            'Discuss online safety',
            'Set clear rules about meeting online contacts',
            'Monitor online activity'
          ],
          urgency: 'high'
        };
      }

      // Medium: Meeting agreement
      if (types.includes('meeting_agreement') || types.includes('time_arrangement')) {
        return {
          action: 'prompt_conversation',
          message: '⚠️ CONCERN: Child agreeing to meet someone',
          recommendations: [
            'Talk to child about who they\'re meeting',
            'Verify it\'s a known, safe person',
            'Discuss online stranger danger',
            'Require parent supervision for meetups',
            'Monitor situation'
          ],
          urgency: 'medium'
        };
      }

      return {
        action: 'monitor',
        message: 'Monitor situation and have conversation'
      };
    },

    /**
     * Get parent notification
     */
    getParentNotification: function(analysis) {
      if (!analysis.hasMeetingRisk) {
        return null;
      }

      const action = this.getRecommendedAction(analysis);
      const types = analysis.detections.map(d => d.type).join(', ');

      return {
        title: '🚨 Meeting Stranger Alert',
        message: `Your child may be planning to meet someone (${types})`,
        severity: analysis.severity,
        urgency: action.urgency || 'medium',
        action: action.action,
        recommendation: action.message,
        resources: action.recommendations || []
      };
    },

    /**
     * Should message be blocked?
     */
    shouldBlock: function(analysis) {
      if (!analysis.hasMeetingRisk) {
        return false;
      }

      // Block critical meeting arrangements
      return analysis.requiresImmediateAction;
    }

  };

  return MeetingDetector;
}));
