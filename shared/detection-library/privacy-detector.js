/**
 * PrivacyDetector - Detects Personal Identifiable Information (PII)
 *
 * Identifies when children are sharing sensitive personal information:
 * - Phone numbers
 * - Email addresses
 * - Physical addresses
 * - School names
 * - Full names (combined with age/location)
 * - Social Security Numbers
 * - Credit card numbers
 *
 * Used by Parental Dashboard to alert parents when child exposes PII.
 *
 * Version: 1.0.0
 */

(function(root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.PrivacyDetector = factory();
  }
}(typeof self !== 'undefined' ? self : this, function() {
  'use strict';

  const PrivacyDetector = {

    /**
     * Analyze text for privacy risks
     * @param {string} text - Message text
     * @returns {Object} Privacy analysis result
     */
    analyze: function(text) {
      const detections = [];
      let riskScore = 0;
      let severity = 'none';

      // Check for phone numbers
      const phoneDetection = this.detectPhoneNumber(text);
      if (phoneDetection.found) {
        detections.push(phoneDetection);
        riskScore = Math.max(riskScore, 0.9); // Critical
      }

      // Check for email addresses
      const emailDetection = this.detectEmail(text);
      if (emailDetection.found) {
        detections.push(emailDetection);
        riskScore = Math.max(riskScore, 0.8); // High
      }

      // Check for physical addresses
      const addressDetection = this.detectAddress(text);
      if (addressDetection.found) {
        detections.push(addressDetection);
        riskScore = Math.max(riskScore, 0.95); // Critical
      }

      // Check for school names
      const schoolDetection = this.detectSchool(text);
      if (schoolDetection.found) {
        detections.push(schoolDetection);
        riskScore = Math.max(riskScore, 0.7); // Medium-High
      }

      // Check for SSN
      const ssnDetection = this.detectSSN(text);
      if (ssnDetection.found) {
        detections.push(ssnDetection);
        riskScore = Math.max(riskScore, 1.0); // Critical!
      }

      // Check for credit card
      const cardDetection = this.detectCreditCard(text);
      if (cardDetection.found) {
        detections.push(cardDetection);
        riskScore = Math.max(riskScore, 1.0); // Critical!
      }

      // Determine severity
      if (riskScore >= 0.9) severity = 'critical';
      else if (riskScore >= 0.7) severity = 'high';
      else if (riskScore >= 0.4) severity = 'medium';
      else if (riskScore > 0) severity = 'low';

      return {
        hasPrivacyRisk: riskScore > 0,
        riskScore: riskScore,
        severity: severity,
        detections: detections,
        // Auxiliary detector — does not map to the canonical 5-category
        // taxonomy (harassment/threats/hate_speech/sexual_content/self_harm).
        // Callers should treat this as a signal, not a final classification.
        category: 'none'
      };
    },

    /**
     * Detect phone numbers
     */
    detectPhoneNumber: function(text) {
      const patterns = [
        // US formats
        /\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b/g,
        /\(\d{3}\)\s?\d{3}[-.\s]?\d{4}/g,
        /\b\d{10}\b/g,
        // International
        /\+\d{1,3}[-.\s]?\d{1,14}/g,
        // Written out
        /my (number|phone|cell) is\s*:?\s*\d/gi
      ];

      for (const pattern of patterns) {
        const matches = text.match(pattern);
        if (matches) {
          return {
            found: true,
            type: 'phone_number',
            matched: matches,
            severity: 'critical',
            message: 'Phone number detected',
            recommendation: 'Never share your phone number with strangers online'
          };
        }
      }

      return { found: false };
    },

    /**
     * Detect email addresses
     */
    detectEmail: function(text) {
      const pattern = /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b/g;
      const matches = text.match(pattern);

      if (matches) {
        return {
          found: true,
          type: 'email',
          matched: matches,
          severity: 'high',
          message: 'Email address detected',
          recommendation: 'Be careful sharing your email with people you don\'t know'
        };
      }

      return { found: false };
    },

    /**
     * Detect physical addresses
     */
    detectAddress: function(text) {
      const patterns = [
        // Street address patterns
        /\b\d{1,5}\s+[\w\s]{1,50}(street|st|avenue|ave|road|rd|lane|ln|drive|dr|court|ct|boulevard|blvd|way|place|pl)\b/gi,
        // Phrases
        /(i live at|my address is|i'm at|meet me at)\s+\d{1,5}\s+[\w\s]+/gi,
        // Zip codes in context
        /(live in|from)\s+\d{5}(-\d{4})?/gi
      ];

      for (const pattern of patterns) {
        const matches = text.match(pattern);
        if (matches) {
          return {
            found: true,
            type: 'address',
            matched: matches,
            severity: 'critical',
            message: 'Physical address detected',
            recommendation: 'NEVER share your home address online - this is dangerous'
          };
        }
      }

      return { found: false };
    },

    /**
     * Detect school names
     */
    detectSchool: function(text) {
      const patterns = [
        // School mentions
        /(i go to|i attend|my school is|at)\s+[\w\s]+(high school|middle school|elementary|academy|prep|college|university)/gi,
        // School type alone in context
        /(lincoln|washington|jefferson|roosevelt|kennedy|madison|hamilton)\s+(high|middle|elementary)/gi,
        /\b[\w\s]+(high|middle|elementary)\s*school\b/gi
      ];

      for (const pattern of patterns) {
        const matches = text.match(pattern);
        if (matches) {
          return {
            found: true,
            type: 'school',
            matched: matches,
            severity: 'high',
            message: 'School name detected',
            recommendation: 'Don\'t share your school name with people you don\'t know'
          };
        }
      }

      return { found: false };
    },

    /**
     * Detect Social Security Numbers
     */
    detectSSN: function(text) {
      const patterns = [
        /\b\d{3}[-\s]?\d{2}[-\s]?\d{4}\b/g,
        /(ssn|social security|ss number)\s*:?\s*\d{3}/gi
      ];

      for (const pattern of patterns) {
        const matches = text.match(pattern);
        if (matches) {
          return {
            found: true,
            type: 'ssn',
            matched: ['[REDACTED]'], // Don't show actual SSN
            severity: 'critical',
            message: 'Social Security Number detected',
            recommendation: 'STOP! Never share SSN online - this can lead to identity theft'
          };
        }
      }

      return { found: false };
    },

    /**
     * Detect credit card numbers
     */
    detectCreditCard: function(text) {
      const patterns = [
        // Credit card formats (13-19 digits with optional spaces/dashes)
        /\b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b/g,
        /(card number|credit card|debit card)\s*:?\s*\d{4}/gi,
        // Parent's card
        /(parent|mom|dad)('s)?\s+(card|credit card)/gi
      ];

      for (const pattern of patterns) {
        const matches = text.match(pattern);
        if (matches) {
          return {
            found: true,
            type: 'credit_card',
            matched: ['[REDACTED]'], // Don't show actual card
            severity: 'critical',
            message: 'Credit card information detected',
            recommendation: 'NEVER share credit card information online - this is theft'
          };
        }
      }

      return { found: false };
    },

    /**
     * Get recommended action based on detection
     */
    getRecommendedAction: function(analysis) {
      if (!analysis.hasPrivacyRisk) {
        return 'none';
      }

      // Check for critical types
      const criticalTypes = ['ssn', 'credit_card', 'address', 'phone_number'];
      const hasCritical = analysis.detections.some(d =>
        criticalTypes.includes(d.type)
      );

      if (hasCritical) {
        return 'block'; // Should be blocked immediately
      }

      return 'warn'; // Show strong warning
    },

    /**
     * Get parent notification message
     */
    getParentNotification: function(analysis) {
      if (!analysis.hasPrivacyRisk) {
        return null;
      }

      const types = analysis.detections.map(d => d.type).join(', ');
      const severity = analysis.severity;

      return {
        title: '🚨 Privacy Risk Detected',
        message: `Your child attempted to share personal information (${types})`,
        severity: severity,
        urgency: severity === 'critical' ? 'immediate' : 'high',
        recommendation: 'Please talk to your child about online privacy and safety'
      };
    }

  };

  return PrivacyDetector;
}));
