// lib/analyzer.js - Client-side toxicity detection

const ToxicityAnalyzer = {
  harassmentTerms: new Set([
    'stupid', 'idiot', 'dumb', 'loser', 'worthless', 'pathetic',
    'nobody likes you', 'everyone hates you', 'kill yourself',
    'die', 'ugly', 'fat', 'gross', 'disgusting', 'freak',
    'weirdo', 'creep', 'psycho', 'crazy', 'insane'
  ]),

  hateTerms: new Set([
    // Comprehensive hate speech terms would go here
    'hate', 'racist', 'bigot'
  ]),

  threatTerms: new Set([
    'going to kill', 'will kill', 'going to hurt', 'will hurt',
    'beat you up', 'going to find you', 'watch your back',
    'bring a weapon', 'bring a knife', 'bring a gun',
    'going to get you', 'you\'re dead', 'you\'re finished'
  ]),

  sexualTerms: new Set([
    'send nudes', 'send pics', 'sexy pic', 'dick pic'
  ]),

  analyze(text, sensitivity = 0.5) {
    if (!text || text.trim().length === 0) {
      return { isToxic: false, score: 0 };
    }

    const normalizedText = text.toLowerCase().trim();

    const harassmentScore = this.checkCategory(normalizedText, this.harassmentTerms);
    const hateScore = this.checkCategory(normalizedText, this.hateTerms);
    const threatScore = this.checkCategory(normalizedText, this.threatTerms);
    const sexualScore = this.checkCategory(normalizedText, this.sexualTerms);

    const scores = [
      { category: 'harassment', score: harassmentScore },
      { category: 'hate', score: hateScore },
      { category: 'threat', score: threatScore },
      { category: 'sexual', score: sexualScore }
    ];

    scores.sort((a, b) => b.score - a.score);
    const primaryCategory = scores[0];

    // Get base score before adjustments
    let adjustedScore = primaryCategory.score;
    const originalScore = adjustedScore;

    // Apply emoji context adjustment (false positive reduction)
    if (typeof EmojiAnalyzer !== 'undefined') {
      adjustedScore = EmojiAnalyzer.adjustScore(adjustedScore, text);
    }

    // Apply sarcasm detection adjustment
    if (typeof SarcasmDetector !== 'undefined') {
      adjustedScore = SarcasmDetector.adjustScore(adjustedScore, text);
    }

    // Apply platform context adjustment (context-aware detection)
    if (typeof ContextDetector !== 'undefined') {
      adjustedScore = ContextDetector.adjustScoreByContext(
        adjustedScore,
        text,
        window.location.hostname
      );
    }

    // Determine severity based on adjusted score
    let severity = 'low';
    if (adjustedScore >= 0.7) {
      severity = 'high';
    } else if (adjustedScore >= 0.4) {
      severity = 'medium';
    }

    const isToxic = adjustedScore >= sensitivity;

    return {
      isToxic,
      category: primaryCategory.category,
      severity,
      score: adjustedScore,
      originalScore: originalScore,
      allScores: {
        harassment: harassmentScore,
        hate: hateScore,
        threat: threatScore,
        sexual: sexualScore
      }
    };
  },

  checkCategory(text, lexicon) {
    let matchCount = 0;
    let weightedScore = 0;

    for (const term of lexicon) {
      if (text.includes(term)) {
        matchCount++;
        const termWeight = Math.min(term.split(' ').length / 5, 1.0);
        weightedScore += termWeight;
      }
    }

    if (matchCount === 0) {
      return 0;
    }

    const baseScore = Math.min(weightedScore / 2, 1.0);
    const matchBoost = Math.min(matchCount * 0.15, 0.3);

    return Math.min(baseScore + matchBoost, 1.0);
  }
};
