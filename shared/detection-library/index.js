/**
 * SafeKeyboard Detection Library - Universal Entry Point
 *
 * Works in:
 * - Browser (Chrome Extension, vanilla JS)
 * - Node.js (Dashboards, backend)
 * - React Native (Mobile apps)
 * - Android WebView (Android app)
 *
 * Version: 1.0.0
 */

// Universal Module Definition (UMD) pattern
(function (root, factory) {
  if (typeof define === 'function' && define.amd) {
    // AMD (RequireJS)
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    // Node.js / CommonJS
    module.exports = factory();
  } else {
    // Browser globals
    root.SafeKeyboardDetection = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // Load individual modules
  // In browser, these are loaded separately via script tags
  // In Node.js, these are required from separate files

  let ToxicityAnalyzer, EmojiAnalyzer, SarcasmDetector, ContextDetector, WarningEscalator;
  let PrivacyDetector, SelfHarmDetector, RiskyBehaviorDetector, MeetingDetector;

  // Check if running in Node.js
  if (typeof module !== 'undefined' && module.exports) {
    // Node.js environment - require modules
    try {
      ToxicityAnalyzer = require('./analyzer.js').ToxicityAnalyzer || require('./analyzer.js');
      EmojiAnalyzer = require('./emoji-analyzer.js').EmojiAnalyzer || require('./emoji-analyzer.js');
      SarcasmDetector = require('./sarcasm-detector.js').SarcasmDetector || require('./sarcasm-detector.js');
      ContextDetector = require('./context-detector.js').ContextDetector || require('./context-detector.js');
      WarningEscalator = require('./warning-escalator.js').WarningEscalator || require('./warning-escalator.js');

      // Parental Dashboard modules
      PrivacyDetector = require('./privacy-detector.js').PrivacyDetector || require('./privacy-detector.js');
      SelfHarmDetector = require('./self-harm-detector.js').SelfHarmDetector || require('./self-harm-detector.js');
      RiskyBehaviorDetector = require('./risky-behavior-detector.js').RiskyBehaviorDetector || require('./risky-behavior-detector.js');
      MeetingDetector = require('./meeting-detector.js').MeetingDetector || require('./meeting-detector.js');
    } catch (e) {
      console.warn('[SafeKeyboard] Could not load modules in Node.js:', e.message);
    }
  } else {
    // Browser environment - modules loaded via script tags
    ToxicityAnalyzer = (typeof self !== 'undefined' && self.ToxicityAnalyzer) || (typeof window !== 'undefined' && window.ToxicityAnalyzer);
    EmojiAnalyzer = (typeof self !== 'undefined' && self.EmojiAnalyzer) || (typeof window !== 'undefined' && window.EmojiAnalyzer);
    SarcasmDetector = (typeof self !== 'undefined' && self.SarcasmDetector) || (typeof window !== 'undefined' && window.SarcasmDetector);
    ContextDetector = (typeof self !== 'undefined' && self.ContextDetector) || (typeof window !== 'undefined' && window.ContextDetector);
    WarningEscalator = (typeof self !== 'undefined' && self.WarningEscalator) || (typeof window !== 'undefined' && window.WarningEscalator);

    // Parental Dashboard modules
    PrivacyDetector = (typeof self !== 'undefined' && self.PrivacyDetector) || (typeof window !== 'undefined' && window.PrivacyDetector);
    SelfHarmDetector = (typeof self !== 'undefined' && self.SelfHarmDetector) || (typeof window !== 'undefined' && window.SelfHarmDetector);
    RiskyBehaviorDetector = (typeof self !== 'undefined' && self.RiskyBehaviorDetector) || (typeof window !== 'undefined' && window.RiskyBehaviorDetector);
    MeetingDetector = (typeof self !== 'undefined' && self.MeetingDetector) || (typeof window !== 'undefined' && window.MeetingDetector);
  }

  /**
   * Complete detection pipeline with all enhancements
   * @param {string} text - Text to analyze
   * @param {number} sensitivity - Detection threshold (0.0-1.0)
   * @param {string} platform - Platform hostname (optional)
   * @returns {Object} Complete analysis result
   */
  function analyzeComplete(text, sensitivity = 0.5, platform = '') {
    if (!ToxicityAnalyzer) {
      throw new Error('[SafeKeyboard] ToxicityAnalyzer not loaded');
    }

    // Base analysis
    const analysis = ToxicityAnalyzer.analyze(text, sensitivity);
    let adjustedScore = analysis.score;
    const originalScore = adjustedScore;

    // Apply emoji adjustment
    if (EmojiAnalyzer) {
      adjustedScore = EmojiAnalyzer.adjustScore(adjustedScore, text);
    }

    // Apply sarcasm detection
    if (SarcasmDetector) {
      adjustedScore = SarcasmDetector.adjustScore(adjustedScore, text);
    }

    // Apply platform context
    if (ContextDetector && platform) {
      adjustedScore = ContextDetector.adjustScoreByContext(adjustedScore, text, platform);
    }

    // Recalculate severity based on adjusted score
    let severity = 'low';
    if (adjustedScore >= 0.7) {
      severity = 'high';
    } else if (adjustedScore >= 0.4) {
      severity = 'medium';
    }

    // Determine if still toxic after adjustments
    const isToxic = adjustedScore >= sensitivity;

    return {
      ...analysis,
      isToxic,
      score: adjustedScore,
      originalScore,
      severity,
      adjustments: {
        emoji: EmojiAnalyzer ? true : false,
        sarcasm: SarcasmDetector ? true : false,
        context: ContextDetector && platform ? true : false
      }
    };
  }

  /**
   * Get warning escalation level
   * @param {number} violationCount - Total violations
   * @returns {Object} Warning level configuration
   */
  function getWarningLevel(violationCount) {
    if (!WarningEscalator) {
      // Fallback if escalator not loaded
      return {
        level: 'educational',
        tone: 'gentle',
        title: 'Think Before You Send',
        cooldownSeconds: 0
      };
    }

    return WarningEscalator.getWarningLevel(violationCount);
  }

  // Public API
  return {
    // Main analyzer
    analyze: analyzeComplete,

    // Cyberbullying detection modules
    ToxicityAnalyzer,
    EmojiAnalyzer,
    SarcasmDetector,
    ContextDetector,
    WarningEscalator,

    // Parental Dashboard safety modules
    PrivacyDetector,
    SelfHarmDetector,
    RiskyBehaviorDetector,
    MeetingDetector,

    // Helper methods
    getWarningLevel,

    // Version
    version: '2.0.0'
  };
}));
