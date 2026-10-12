// lib/sarcasm-detector.js - Advanced sarcasm detection

class SarcasmDetector {
  /**
   * Detect sarcasm using pattern matching
   * @param {string} text - Message text
   * @returns {Object} Sarcasm analysis result
   */
  static analyze(text) {
    const lowerText = text.toLowerCase();

    // Sarcasm indicators
    const sarcasmPatterns = [
      // Classic sarcasm phrases
      { pattern: /oh (really|sure|great|wonderful|perfect|nice)/i, confidence: 0.8 },
      { pattern: /yeah (right|sure|okay|ok)/i, confidence: 0.8 },
      { pattern: /totally/i, confidence: 0.6 },
      { pattern: /absolutely/i, confidence: 0.5 },
      { pattern: /obviously/i, confidence: 0.5 },

      // Exaggeration/Hyperbole
      { pattern: /so (brave|smart|clever|wise|genius|brilliant)/i, confidence: 0.7 },
      { pattern: /very (smart|clever|wise|bright)/i, confidence: 0.6 },
      { pattern: /what a (genius|mastermind|expert)/i, confidence: 0.8 },

      // Congratulatory sarcasm
      { pattern: /congrats on being/i, confidence: 0.7 },
      { pattern: /congratulations on your/i, confidence: 0.6 },
      { pattern: /good job being/i, confidence: 0.6 },

      // Slow clap sarcasm
      { pattern: /slow clap/i, confidence: 0.9 },
      { pattern: /standing ovation/i, confidence: 0.7 },
      { pattern: /round of applause/i, confidence: 0.7 },

      // Question sarcasm
      { pattern: /did you think of that all by yourself/i, confidence: 0.9 },
      { pattern: /how long did it take you/i, confidence: 0.6 },
      { pattern: /must have been hard/i, confidence: 0.6 },

      // Thanks sarcasm
      { pattern: /thanks for (nothing|that|the help)/i, confidence: 0.8 },
      { pattern: /gee thanks/i, confidence: 0.8 },
      { pattern: /wow thanks/i, confidence: 0.7 }
    ];

    // Check for matches
    let maxConfidence = 0;
    let matchedPatterns = 0;

    for (const { pattern, confidence } of sarcasmPatterns) {
      if (pattern.test(text)) {
        matchedPatterns++;
        maxConfidence = Math.max(maxConfidence, confidence);
      }
    }

    // Check for punctuation indicators
    const hasExcessiveExclamation = (text.match(/!/g) || []).length >= 2;
    const hasExcessiveQuestion = (text.match(/\?/g) || []).length >= 2;
    const hasEllipsis = /\.{3,}/.test(text);
    const hasCapitalization = /[A-Z]{2,}/.test(text) && text !== text.toUpperCase();

    const punctuationScore = (
      (hasExcessiveExclamation ? 0.2 : 0) +
      (hasExcessiveQuestion ? 0.2 : 0) +
      (hasEllipsis ? 0.1 : 0) +
      (hasCapitalization ? 0.1 : 0)
    );

    // Combine scores
    const finalConfidence = Math.min(maxConfidence + punctuationScore, 1.0);

    // Determine if sarcastic
    const isSarcastic = finalConfidence >= 0.5 || matchedPatterns >= 2;

    return {
      isSarcastic,
      confidence: finalConfidence,
      matchedPatterns,
      indicators: {
        hasExcessiveExclamation,
        hasExcessiveQuestion,
        hasEllipsis,
        hasCapitalization
      }
    };
  }

  /**
   * Adjust toxicity score based on sarcasm detection
   * @param {number} baseScore - Original toxicity score
   * @param {string} text - Message text
   * @returns {number} Adjusted score
   */
  static adjustScore(baseScore, text) {
    const analysis = this.analyze(text);

    if (analysis.isSarcastic) {
      // Reduce toxicity score for sarcastic messages
      // Higher confidence = more reduction
      const reduction = analysis.confidence * 0.5; // Max 50% reduction
      const adjustedScore = baseScore * (1 - reduction);

      console.log(`[SafeKeyboard Sarcasm] Detected sarcasm (confidence: ${analysis.confidence.toFixed(2)})`);
      console.log(`[SafeKeyboard Sarcasm] Score adjusted: ${baseScore.toFixed(2)} → ${adjustedScore.toFixed(2)}`);

      return adjustedScore;
    }

    return baseScore;
  }

  /**
   * Check if message contains sarcasm indicators
   * @param {string} text - Message text
   * @returns {boolean} True if sarcasm detected
   */
  static isSarcastic(text) {
    const analysis = this.analyze(text);
    return analysis.isSarcastic;
  }

  /**
   * Get detailed sarcasm analysis for debugging
   * @param {string} text - Message text
   * @returns {Object} Detailed analysis
   */
  static getDetailedAnalysis(text) {
    return this.analyze(text);
  }
}
