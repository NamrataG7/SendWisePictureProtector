// lib/emoji-analyzer.js - Emoji context analysis for false positive reduction

class EmojiAnalyzer {
  /**
   * Analyze emoji context in message
   * @param {string} text - Message text
   * @returns {Object} Emoji analysis result
   */
  static analyze(text) {
    const positiveEmojis = [
      '😂', '🤣', '😁', '😄', '😃', '😀', '😊', '☺️', '😌',
      '😅', '🙂', '🙃', '😉', '😋', '😛', '😝', '😜',
      '❤️', '💕', '💖', '💗', '💙', '💚', '💛', '🧡', '💜',
      '🥰', '😍', '🤩', '😘', '💋', '💪', '👍', '👏', '🙌',
      '✌️', '🤝', '🤗', '🥳', '🎉', '🎊', '💯'
    ];

    const negativeEmojis = [
      '😠', '😡', '🤬', '😤', '😒', '🙄', '😑',
      '💢', '💥', '👊', '🤛', '🤜', '🖕',
      '😈', '👿', '💀', '☠️', '👎', '🔪', '🗡️',
      '🤷', '😶', '😐'
    ];

    const sadEmojis = [
      '😢', '😭', '😿', '😞', '😔', '😟', '🙁', '☹️',
      '😩', '😫', '😖', '😣', '💔', '🥺'
    ];

    // Count emoji types
    let positiveCount = 0;
    let negativeCount = 0;
    let sadCount = 0;

    for (const emoji of positiveEmojis) {
      if (text.includes(emoji)) positiveCount++;
    }

    for (const emoji of negativeEmojis) {
      if (text.includes(emoji)) negativeCount++;
    }

    for (const emoji of sadEmojis) {
      if (text.includes(emoji)) sadCount++;
    }

    // Determine sentiment
    let sentiment = 'neutral';
    let confidence = 0;

    if (positiveCount > negativeCount + sadCount) {
      sentiment = 'positive';
      confidence = Math.min(positiveCount * 0.3, 0.6); // Max 60% reduction
    } else if (negativeCount > positiveCount) {
      sentiment = 'negative';
      confidence = Math.min(negativeCount * 0.2, 0.4); // Max 40% increase
    } else if (sadCount > positiveCount) {
      sentiment = 'sad';
      confidence = 0.1; // Slight reduction (might be venting)
    }

    return {
      sentiment,
      confidence,
      positiveCount,
      negativeCount,
      sadCount,
      hasEmojis: (positiveCount + negativeCount + sadCount) > 0
    };
  }

  /**
   * Adjust toxicity score based on emoji context
   * @param {number} baseScore - Original toxicity score
   * @param {string} text - Message text
   * @returns {number} Adjusted score
   */
  static adjustScore(baseScore, text) {
    const analysis = this.analyze(text);

    if (!analysis.hasEmojis) {
      return baseScore; // No emojis, no adjustment
    }

    if (analysis.sentiment === 'positive') {
      // Positive emojis suggest sarcasm/joking
      // Example: "you're so stupid 😂" → likely joking
      const reduction = analysis.confidence;
      return Math.max(baseScore * (1 - reduction), 0);
    }

    if (analysis.sentiment === 'negative') {
      // Negative emojis reinforce hostility
      // Example: "you're stupid 😠" → clearly hostile
      const increase = analysis.confidence;
      return Math.min(baseScore * (1 + increase), 1.0);
    }

    if (analysis.sentiment === 'sad') {
      // Sad emojis might indicate venting, slight reduction
      // Example: "I hate this 😭" → venting, not attack
      return baseScore * 0.9; // 10% reduction
    }

    return baseScore;
  }

  /**
   * Check if message is likely sarcasm based on emojis
   * @param {string} text - Message text
   * @returns {boolean} True if likely sarcastic
   */
  static isSarcasm(text) {
    const analysis = this.analyze(text);

    // Sarcasm indicators:
    // 1. Positive emojis with negative words
    // 2. Laughing emojis (😂, 🤣)
    // 3. Rolling eyes emoji (🙄)

    if (analysis.sentiment === 'positive' && analysis.positiveCount > 0) {
      // Check for laughing emojis specifically
      const laughingEmojis = ['😂', '🤣', '😅'];
      for (const emoji of laughingEmojis) {
        if (text.includes(emoji)) {
          return true; // Likely joking
        }
      }
    }

    // Rolling eyes emoji often indicates sarcasm
    if (text.includes('🙄')) {
      return true;
    }

    return false;
  }
}
