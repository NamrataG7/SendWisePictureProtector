// lib/warning-escalator.js - Smarter warning escalation based on violation count

class WarningEscalator {
  /**
   * Get warning configuration based on total violation count
   * @param {number} violationCount - Total violations by this user
   * @returns {Object} Warning configuration
   */
  static getWarningLevel(violationCount) {
    // Level 1: Educational (1-3 violations)
    if (violationCount <= 3) {
      return {
        level: 'educational',
        tone: 'gentle',
        color: '#3b82f6', // Blue - calm
        title: 'Think Before You Send',
        subtitle: 'Your message may contain harmful content',
        showLegalText: true,
        showConsequences: false,
        showViolationCount: false,
        emphasizeLegal: false,
        allowSendAnyway: true,
        cooldownSeconds: 0, // No cooldown
        escalationWarning: false
      };
    }

    // Level 2: Reminder (4-10 violations)
    if (violationCount <= 10) {
      return {
        level: 'reminder',
        tone: 'firm',
        color: '#f59e0b', // Yellow - caution
        title: '⚠️ Warning: Repeated Violations',
        subtitle: `You've been warned ${violationCount} times. Please reconsider your message.`,
        showLegalText: true,
        showConsequences: true,
        showViolationCount: true,
        emphasizeLegal: false,
        allowSendAnyway: true,
        cooldownSeconds: 5, // 5-second delay
        escalationWarning: false
      };
    }

    // Level 3: Strong Warning (11-20 violations)
    if (violationCount <= 20) {
      return {
        level: 'strong',
        tone: 'serious',
        color: '#f97316', // Orange - warning
        title: '🚨 Serious Warning',
        subtitle: `You have ${violationCount} violations. Continued behavior may result in escalation.`,
        showLegalText: true,
        showConsequences: true,
        showViolationCount: true,
        emphasizeLegal: true,
        allowSendAnyway: true,
        cooldownSeconds: 10, // 10-second delay
        escalationWarning: true,
        escalationMessage: 'Your parent or school administrator may be notified if violations continue.'
      };
    }

    // Level 4: Critical Escalation (21+ violations)
    return {
      level: 'escalation',
      tone: 'critical',
      color: '#dc2626', // Red - critical
      title: '🛑 Critical: Pattern of Abuse Detected',
      subtitle: `You have ${violationCount} violations. This is a serious pattern of harmful behavior.`,
      showLegalText: true,
      showConsequences: true,
      showViolationCount: true,
      emphasizeLegal: true,
      allowSendAnyway: true,
      cooldownSeconds: 15, // 15-second delay
      escalationWarning: true,
      escalationMessage: '⚠️ NOTICE: Your parent/school administrator will be notified of this violation.',
      notifyAuthority: true
    };
  }

  /**
   * Get consequence text based on level
   */
  static getConsequenceText(level) {
    const consequences = {
      reminder: `
        <p><strong>Possible Consequences:</strong></p>
        <ul>
          <li>Continued violations may be reported to parents or school</li>
          <li>Pattern of abuse may result in account restrictions</li>
        </ul>
      `,
      strong: `
        <p><strong>Serious Consequences:</strong></p>
        <ul>
          <li>Parent or school notification</li>
          <li>Account suspension or restrictions</li>
          <li>Potential legal action for serious threats</li>
          <li>Impact on school disciplinary record</li>
        </ul>
      `,
      escalation: `
        <p><strong>Critical Consequences:</strong></p>
        <ul>
          <li>⚠️ Parent/school WILL be notified immediately</li>
          <li>Account may be suspended or banned</li>
          <li>Legal action may be pursued for threats or harassment</li>
          <li>Disciplinary action at school (detention, suspension)</li>
          <li>Permanent impact on your digital citizenship record</li>
        </ul>
      `
    };

    return consequences[level] || '';
  }

  /**
   * Get user-friendly message about their progress
   */
  static getProgressMessage(violationCount) {
    if (violationCount <= 3) {
      return 'You can learn from this and make better choices.';
    }
    if (violationCount <= 10) {
      return 'You\'ve had multiple warnings. It\'s time to change this behavior.';
    }
    if (violationCount <= 20) {
      return 'This is becoming a serious pattern. Consider the impact of your words.';
    }
    return 'Your behavior has been flagged as a pattern of abuse. This is your final warning.';
  }
}
