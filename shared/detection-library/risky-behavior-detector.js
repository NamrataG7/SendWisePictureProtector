/**
 * RiskyBehaviorDetector - Detects dangerous or illegal activities
 *
 * Identifies when children discuss:
 * - Drug use or purchase
 * - Alcohol consumption (underage)
 * - Sexual content (minors)
 * - Illegal activities (theft, vandalism, etc.)
 * - Dangerous challenges/stunts
 *
 * Used by Parental Dashboard to alert parents of risky behavior.
 *
 * Version: 1.0.0
 */

(function(root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.RiskyBehaviorDetector = factory();
  }
}(typeof self !== 'undefined' ? self : this, function() {
  'use strict';

  const RiskyBehaviorDetector = {

    // Drug-related patterns
    drugPatterns: [
      // Usage
      /\b(smoke|smoking|smoked|hit|vape|vaping)\s+(weed|pot|marijuana|mj|grass|dope)\b/gi,
      /\b(get|got|getting|buy|buying)\s+(high|stoned|baked|blazed)\b/gi,
      /\b(smoke|vape)\s+with\s+me\b/gi,
      // Specific drugs
      /\b(cocaine|coke|crack|heroin|meth|molly|ecstasy|mdma|lsd|acid|shrooms|pills|xanax|adderall|oxy)\b/gi,
      // Purchase/dealing
      /\b(buy|sell|deal|cop|score)\s+(weed|drugs|pills)\b/gi,
      /\bhit the plug\b/gi,
      /\bplug (me|us) up\b/gi
    ],

    // Alcohol patterns (underage)
    alcoholPatterns: [
      // Consumption
      /\b(drunk|wasted|hammered|trashed|plastered|shitfaced|buzzed)\b/gi,
      /\b(drinking|drink|drank)\s+(beer|vodka|whiskey|tequila|alcohol|liquor)\b/gi,
      /\b(get|got|getting) (drunk|wasted|hammered)\b/gi,
      /\bpregame|pregaming\b/gi,
      // Sneaking/hiding
      /\b(sneak|steal|hide)\s+(alcohol|beer|vodka|whiskey|liquor)\b/gi,
      /\b(parents|mom|dad)('s)?\s+(alcohol|liquor|wine|beer)\b/gi,
      // Party context
      /\bbring (alcohol|beer|vodka) to (the )?party\b/gi
    ],

    // Sexual content (minors)
    sexualPatterns: [
      // Explicit requests
      /\b(send|share|post)\s+(nudes|naked (pics|pictures|photos)|dick pic|pussy pic)\b/gi,
      /\bwanna (fuck|hookup|have sex|bang|smash)\b/gi,
      /\b(netflix and chill|dtf|down to fuck)\b/gi,
      // Sexting
      /\bsext(ing)? (me|with me)\b/gi,
      /\b(show me|let me see)\s+your\s+(body|boobs|tits|ass|dick|pussy)\b/gi,
      // Pressure
      /\bif you (loved|liked) me you('d| would) (send|show)\b/gi
    ],

    // Illegal activities
    illegalPatterns: [
      // Theft
      /\b(steal|shoplift|shoplifting|rob|robbing|theft)\b/gi,
      /\b(gonna|going to) steal (from|at)\b/gi,
      /\btake without paying\b/gi,
      // Vandalism
      /\b(vandalize|vandalism|graffiti|tag|tagging|break|smash|destroy)\s+(property|car|building|window|school)\b/gi,
      /\b(egg|tp|toilet paper)\s+(house|car)\b/gi,
      // Trespassing
      /\b(break into|sneak into|trespass)\b/gi,
      // Weapons
      /\b(bring|bringing|got)\s+(gun|knife|weapon)\s+to\s+school\b/gi,
      // School violations
      /\b(skip|ditch|cut)\s+(school|class)\b/gi,
      /\bcheat on (the |a )?test\b/gi
    ],

    // Dangerous challenges/stunts
    dangerousPatterns: [
      // Viral challenges
      /\b(tide pod|cinnamon|salt and ice|choking|blackout|fire) challenge\b/gi,
      /\btrain surfing\b/gi,
      /\bcar surfing\b/gi,
      // Dangerous stunts
      /\bjump (off|from) (the )?(roof|bridge|building)\b/gi,
      /\b(dare|dared) (me )?to (jump|climb)\b/gi
    ],

    /**
     * Analyze text for risky behavior
     * @param {string} text - Message text
     * @returns {Object} Risky behavior analysis result
     */
    analyze: function(text) {
      const detections = [];
      let riskScore = 0;
      let severity = 'none';

      // Check drugs
      const drugDetection = this.checkCategory(text, this.drugPatterns, 'drugs');
      if (drugDetection.found) {
        detections.push(drugDetection);
        riskScore = Math.max(riskScore, 0.9); // High
      }

      // Check alcohol
      const alcoholDetection = this.checkCategory(text, this.alcoholPatterns, 'alcohol');
      if (alcoholDetection.found) {
        detections.push(alcoholDetection);
        riskScore = Math.max(riskScore, 0.85); // High
      }

      // Check sexual content
      const sexualDetection = this.checkCategory(text, this.sexualPatterns, 'sexual');
      if (sexualDetection.found) {
        detections.push(sexualDetection);
        riskScore = Math.max(riskScore, 0.95); // Critical
      }

      // Check illegal activities
      const illegalDetection = this.checkCategory(text, this.illegalPatterns, 'illegal');
      if (illegalDetection.found) {
        detections.push(illegalDetection);
        riskScore = Math.max(riskScore, 0.8); // High
      }

      // Check dangerous stunts
      const dangerousDetection = this.checkCategory(text, this.dangerousPatterns, 'dangerous');
      if (dangerousDetection.found) {
        detections.push(dangerousDetection);
        riskScore = Math.max(riskScore, 0.9); // High
      }

      // Determine severity
      if (riskScore >= 0.9) severity = 'critical';
      else if (riskScore >= 0.7) severity = 'high';
      else if (riskScore >= 0.5) severity = 'medium';
      else if (riskScore > 0) severity = 'low';

      return {
        hasRiskyBehavior: riskScore > 0,
        riskScore: riskScore,
        severity: severity,
        detections: detections,
        // Auxiliary detector — does not map to the canonical 5-category
        // taxonomy (harassment/threats/hate_speech/sexual_content/self_harm).
        category: 'none'
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
      if (!analysis.hasRiskyBehavior) {
        return {
          action: 'none',
          message: null
        };
      }

      const types = analysis.detections.map(d => d.type);

      // Critical: Sexual content (minors)
      if (types.includes('sexual')) {
        return {
          action: 'immediate_conversation',
          message: '🚨 CRITICAL: Sexual content detected - talk to child immediately',
          recommendations: [
            'Have an age-appropriate conversation about online safety',
            'Discuss consent and healthy relationships',
            'Consider reporting to platform if receiving unwanted content',
            'May need to involve authorities if exploitation suspected'
          ]
        };
      }

      // High: Drugs or alcohol
      if (types.includes('drugs') || types.includes('alcohol')) {
        return {
          action: 'serious_conversation',
          message: '⚠️ HIGH: Substance use detected - intervention needed',
          recommendations: [
            'Have a serious conversation about substance abuse',
            'Consider drug/alcohol counseling',
            'Discuss legal consequences',
            'Monitor social circles',
            'Set clear boundaries and consequences'
          ]
        };
      }

      // High: Dangerous activities
      if (types.includes('dangerous')) {
        return {
          action: 'serious_conversation',
          message: '⚠️ HIGH: Dangerous activity detected',
          recommendations: [
            'Discuss physical safety',
            'Explain risks and consequences',
            'Monitor online challenges/trends',
            'Set clear rules about dangerous behavior'
          ]
        };
      }

      // Medium: Illegal activities
      if (types.includes('illegal')) {
        return {
          action: 'conversation_and_monitoring',
          message: '⚠️ CONCERN: Illegal activity discussed',
          recommendations: [
            'Talk about legal consequences',
            'Discuss ethics and values',
            'Monitor behavior closely',
            'May need to involve school or authorities depending on severity'
          ]
        };
      }

      return {
        action: 'monitor',
        message: 'Monitor situation and have conversation if pattern continues'
      };
    },

    /**
     * Get parent notification
     */
    getParentNotification: function(analysis) {
      if (!analysis.hasRiskyBehavior) {
        return null;
      }

      const action = this.getRecommendedAction(analysis);
      const types = analysis.detections.map(d => d.type).join(', ');

      return {
        title: '⚠️ Risky Behavior Alert',
        message: `Your child discussed risky behavior (${types})`,
        severity: analysis.severity,
        urgency: analysis.severity === 'critical' ? 'high' : 'medium',
        action: action.action,
        recommendation: action.message,
        resources: action.recommendations || []
      };
    },

    /**
     * Should message be blocked?
     */
    shouldBlock: function(analysis) {
      if (!analysis.hasRiskyBehavior) {
        return false;
      }

      // Block critical sexual content
      const hasSexual = analysis.detections.some(d => d.type === 'sexual');
      return hasSexual;
    }

  };

  return RiskyBehaviorDetector;
}));
