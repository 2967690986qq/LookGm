import { EventEmitter } from 'events';
import log from 'loglevel';
import { VoiceMessage, VoiceConfig } from '@types/voice';
import { ScoreResult } from '@types/scoring';

export class VoiceGuideService extends EventEmitter {
  private static instance: VoiceGuideService;
  private config: VoiceConfig = {
    enabled: true,
    volume: 0.8,
    rate: 1.0,
    pitch: 1.0,
    language: 'zh-CN',
    cooldownMs: 3000,
    silentMode: false,
  };
  private messageQueue: VoiceMessage[] = [];
  private lastSpeakTimeMap: Map<string, number> = new Map();
  private speaking: boolean = false;

  private constructor() {
    super();
  }

  static getInstance(): VoiceGuideService {
    if (!VoiceGuideService.instance) {
      VoiceGuideService.instance = new VoiceGuideService();
    }
    return VoiceGuideService.instance;
  }

  configure(config: Partial<VoiceConfig>): void {
    this.config = { ...this.config, ...config };
    log.info('[VoiceGuide] 语音配置更新:', this.config);
  }

  getConfig(): VoiceConfig {
    return { ...this.config };
  }

  speak(message: Omit<VoiceMessage, 'id' | 'timestamp'>): void {
    if (!this.config.enabled || this.config.silentMode) return;
    const lastSpeak = this.lastSpeakTimeMap.get(message.text) || 0;
    const now = Date.now();
    if (now - lastSpeak < message.cooldown) return;

    const fullMessage: VoiceMessage = {
      ...message,
      id: `msg_${now}_${Math.random().toString(36).slice(2, 7)}`,
      timestamp: now,
    };
    this.messageQueue.push(fullMessage);
    this.lastSpeakTimeMap.set(message.text, now);
    this.processQueue();
    this.emit('speak', fullMessage);
  }

  speakTask(description: string, priority: VoiceMessage['priority'] = 'normal'): void {
    this.speak({
      text: description,
      priority,
      type: 'task',
      cooldown: this.config.cooldownMs * 2,
    });
  }

  speakWarning(text: string): void {
    this.speak({
      text,
      priority: 'high',
      type: 'warning',
      cooldown: 5000,
    });
  }

  speakInfo(text: string): void {
    this.speak({
      text,
      priority: 'normal',
      type: 'info',
      cooldown: 4000,
    });
  }

  speakScoreUpdate(score: ScoreResult): void {
    this.speak({
      text: `当前评分 ${score.totalScore}`,
      priority: 'low',
      type: 'score_update',
      cooldown: 15000,
    });
  }

  speakPhaseChange(phase: string): void {
    const phaseTextMap: Record<string, string> = {
      loading: '游戏加载中',
      hero_select: '英雄选择阶段',
      early_game: '游戏前期',
      mid_game: '游戏中期',
      late_game: '游戏后期',
      battle: '战斗中',
    };
    this.speak({
      text: `${phaseTextMap[phase] || phase}`,
      priority: 'normal',
      type: 'phase_change',
      cooldown: 30000,
    });
  }

  setEnabled(enabled: boolean): void {
    this.config.enabled = enabled;
    if (!enabled) {
      this.messageQueue = [];
      this.stop();
    }
  }

  stop(): void {
    this.speaking = false;
    this.emit('stopped');
  }

  getQueue(): VoiceMessage[] {
    return [...this.messageQueue];
  }

  private processQueue(): void {
    if (this.speaking || this.messageQueue.length === 0) return;
    const sortedQueue = this.messageQueue.sort((a, b) => {
      const priorityRank = { critical: 0, high: 1, normal: 2, low: 3 };
      return priorityRank[a.priority] - priorityRank[b.priority] || a.timestamp - b.timestamp;
    });
    const message = sortedQueue.shift();
    if (message) {
      this.messageQueue = sortedQueue;
      this.speakText(message.text);
    }
  }

  private async speakText(text: string): Promise<void> {
    this.speaking = true;
    log.info(`[VoiceGuide] 播报: ${text}`);
    const duration = Math.min(5000, Math.max(800, text.length * 180));
    await new Promise(resolve => setTimeout(resolve, duration));
    this.speaking = false;
    this.processQueue();
  }
}
