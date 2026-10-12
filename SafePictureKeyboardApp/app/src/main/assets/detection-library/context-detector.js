// lib/context-detector.js - Context-aware pattern detection

class ContextDetector {
  /**
   * Detect platform context
   * @param {string} hostname - Website hostname
   * @returns {string} Context type
   */
  static detectPlatformContext(hostname) {
    const contexts = {
      'discord.com': 'gaming',
      'twitch.tv': 'gaming',
      'linkedin.com': 'professional',
      'github.com': 'technical',
      'stackoverflow.com': 'technical',
      'instagram.com': 'social',
      'twitter.com': 'social',
      'x.com': 'social',
      'facebook.com': 'social',
      'messenger.com': 'social',
      'whatsapp.com': 'social',
      'reddit.com': 'social',
      'youtube.com': 'social',
      'tiktok.com': 'social'
    };

    for (const [domain, context] of Object.entries(contexts)) {
      if (hostname.includes(domain)) {
        return context;
      }
    }

    return 'social'; // Default
  }

  /**
   * Adjust toxicity score based on context
   * @param {number} baseScore - Original toxicity score
   * @param {string} text - Message text
   * @param {string} platform - Platform hostname
   * @returns {number} Adjusted score
   */
  static adjustScoreByContext(baseScore, text, platform) {
    const context = this.detectPlatformContext(platform);
    const lowerText = text.toLowerCase();

    // Gaming Context (Discord, Twitch)
    if (context === 'gaming') {
      const gamingTerms = [
        'noob', 'newb', 'n00b', 'pwned', 'rekt', 'wrecked',
        'gg', 'ez', 'easy', 'git gud', 'skill issue',
        'trash', 'bot', 'owned', 'destroyed', 'demolished'
      ];

      let gamingTermCount = 0;
      for (const term of gamingTerms) {
        if (lowerText.includes(term)) {
          gamingTermCount++;
        }
      }

      if (gamingTermCount > 0) {
        // Reduce severity in gaming context
        // More gaming terms = more reduction
        const reduction = Math.min(gamingTermCount * 0.2, 0.6); // Max 60% reduction
        return baseScore * (1 - reduction);
      }
    }

    // Professional Context (LinkedIn)
    if (context === 'professional') {
      const professionalTerms = [
        'meeting', 'project', 'deadline', 'work', 'team',
        'manager', 'client', 'business', 'schedule'
      ];

      for (const term of professionalTerms) {
        if (lowerText.includes(term)) {
          // Professional context: give benefit of doubt
          return baseScore * 0.8; // 20% reduction
        }
      }
    }

    // Technical Context (GitHub, Stack Overflow)
    if (context === 'technical') {
      const technicalPhrases = [
        'kill process', 'kill thread', 'kill task', 'kill job',
        'terminate process', 'abort', 'crash', 'dump',
        'force quit', 'force close', 'force stop',
        'stupid bug', 'dumb error', 'crazy issue',
        'insane problem', 'ugly code', 'dirty hack'
      ];

      for (const phrase of technicalPhrases) {
        if (lowerText.includes(phrase)) {
          // Technical jargon, not toxic
          return 0; // No toxicity
        }
      }

      // Technical terms that reduce severity
      const technicalTerms = [
        'bug', 'error', 'exception', 'issue', 'problem',
        'code', 'function', 'method', 'variable', 'class',
        'api', 'database', 'server', 'client', 'request'
      ];

      for (const term of technicalTerms) {
        if (lowerText.includes(term)) {
          // Likely technical discussion
          return baseScore * 0.5; // 50% reduction
        }
      }
    }

    return baseScore; // No adjustment for social context
  }

  /**
   * Check if message is technical discussion
   * @param {string} text - Message text
   * @returns {boolean} True if technical
   */
  static isTechnicalDiscussion(text) {
    const lowerText = text.toLowerCase();

    const technicalIndicators = [
      'function', 'method', 'class', 'object', 'array',
      'variable', 'parameter', 'return', 'loop', 'if',
      'const', 'let', 'var', 'import', 'export',
      'async', 'await', 'promise', 'callback',
      'api', 'endpoint', 'request', 'response',
      'database', 'query', 'table', 'index',
      'bug', 'error', 'exception', 'stacktrace'
    ];

    let count = 0;
    for (const indicator of technicalIndicators) {
      if (lowerText.includes(indicator)) {
        count++;
      }
    }

    // 2+ technical terms = likely technical discussion
    return count >= 2;
  }

  /**
   * Check if message is gaming banter
   * @param {string} text - Message text
   * @returns {boolean} True if gaming banter
   */
  static isGamingBanter(text) {
    const lowerText = text.toLowerCase();

    const gamingPhrases = [
      'gg', 'good game', 'well played', 'wp',
      'git gud', 'skill issue', 'diff', 'carried',
      'clutch', 'ace', 'penta', 'quad', 'triple'
    ];

    for (const phrase of gamingPhrases) {
      if (lowerText.includes(phrase)) {
        return true;
      }
    }

    return false;
  }
}
