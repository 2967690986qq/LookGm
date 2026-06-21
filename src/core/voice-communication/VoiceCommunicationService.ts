import { EventEmitter } from 'events';
import log from 'loglevel';
import storage from '../../utils/Storage';

let NativeModules: any = undefined;
try {
  const rn = require('react-native');
  NativeModules = rn.NativeModules;
} catch (err) {
  // 非React Native环境
}

export type VoiceMode = 'tts' | 'stt' | 'conversation';
export type VoiceEngine = 'system' | 'online' | 'custom';
export type VoiceGender = 'male' | 'female' | 'neutral';
export type VoiceMessageType = 'score' | 'task' | 'warning' | 'info' | 'user' | 'assistant';

export interface VoiceConfig {
  enabled: boolean;
  mode: VoiceMode;
  engine: VoiceEngine;
  language: string;
  rate: number;
  pitch: number;
  volume: number;
  gender: VoiceGender;
  customEngineUrl?: string;
  autoSpeakScore: boolean;
  autoSpeakTasks: boolean;
  autoSpeakWarnings: boolean;
  enableWakeWord: boolean;
  wakeWord: string;
}

export interface VoiceMessage {
  id: string;
  text: string;
  type: VoiceMessageType;
  timestamp: number;
  duration?: number;
  success?: boolean;
}

const DEFAULT_CONFIG: VoiceConfig = {
  enabled: true,
  mode: 'tts',
  engine: 'system',
  language: 'zh-CN',
  rate: 1.0,
  pitch: 1.0,
  volume: 1.0,
  gender: 'female',
  autoSpeakScore: true,
  autoSpeakTasks: true,
  autoSpeakWarnings: true,
  enableWakeWord: false,
  wakeWord: '你好助手',
};

export class VoiceCommunicationService extends EventEmitter {
  private static instance: VoiceCommunicationService;
  private config: VoiceConfig;
  private isSpeaking: boolean = false;
  private isListening: boolean = false;
  private messageQueue: VoiceMessage[] = [];
  private history: VoiceMessage[] = [];
  private synthRef: any = null;
  private voiceModule: any = null;
  private currentUtterance: any = null;
  private queueTimer: any = null;

  private constructor() {
    super();
    this.config = { ...DEFAULT_CONFIG };

    // 优先使用原生TTS
    if (NativeModules && NativeModules.LookGmVoice) {
      this.voiceModule = NativeModules.LookGmVoice;
      log.info('[Voice] 原生TTS模块可用');
    }

    // 回退到Web Speech API
    try {
      const globalAny = global as any;
      if (globalAny.speechSynthesis) {
        this.synthRef = globalAny.speechSynthesis;
        log.info('[Voice] Web Speech Synthesis 可用');
      }
    } catch (err) {
      // 忽略
    }

    this.loadFromStorage();
  }

  static getInstance(): VoiceCommunicationService {
    if (!VoiceCommunicationService.instance) {
      VoiceCommunicationService.instance = new VoiceCommunicationService();
    }
    return VoiceCommunicationService.instance;
  }

  private async loadFromStorage(): Promise<void> {
    try {
      const saved = await storage.getItem('lookgm_voice_config');
      if (saved) {
        const parsed = JSON.parse(saved);
        this.config = { ...DEFAULT_CONFIG, ...parsed };
      }
    } catch (err) {
      log.error('[Voice] 加载配置失败:', err);
    }
  }

  private async saveToStorage(): Promise<void> {
    try {
      await storage.setItem('lookgm_voice_config', JSON.stringify(this.config));
    } catch (err) {
      log.error('[Voice] 保存配置失败:', err);
    }
  }

  configure(config: Partial<VoiceConfig>): void {
    this.config = { ...this.config, ...config };
    this.saveToStorage();
    this.emit('config-updated', this.config);

    // 同步速率/音调到原生模块
    if (this.voiceModule) {
      try {
        if (typeof this.voiceModule.setRate === 'function') {
          this.voiceModule.setRate(this.config.rate);
        }
        if (typeof this.voiceModule.setPitch === 'function') {
          this.voiceModule.setPitch(this.config.pitch);
        }
        if (typeof this.voiceModule.setEnabled === 'function') {
          this.voiceModule.setEnabled(this.config.enabled);
        }
      } catch (err) {
        log.warn('[Voice] 同步配置到原生模块失败:', err);
      }
    }
  }

  getConfig(): VoiceConfig {
    return { ...this.config };
  }

  async speak(text: string, type: VoiceMessageType = 'info'): Promise<boolean> {
    if (!this.config.enabled) {
      return false;
    }
    if (!text || text.trim() === '') {
      return false;
    }

    const message: VoiceMessage = {
      id: `msg_${Date.now()}`,
      text,
      type,
      timestamp: Date.now(),
      success: false,
    };

    this.messageQueue.push(message);
    this.emit('message-queued', message);

    if (!this.isSpeaking) {
      this.processQueue();
    }
    return true;
  }

  private async processQueue(): Promise<void> {
    if (this.messageQueue.length === 0) {
      this.emit('queue-empty');
      return;
    }

    const message = this.messageQueue.shift();
    if (!message) return;

    this.isSpeaking = true;
    this.emit('speaking-started', message);

    try {
      await this.synthesizeSpeech(message);
      message.success = true;
      this.history.push(message);
      if (this.history.length > 50) this.history.shift();
      this.emit('speaking-completed', message);
    } catch (err) {
      log.warn('[Voice] 播报失败:', err);
      this.emit('speaking-error', err);
    } finally {
      this.isSpeaking = false;
      if (this.messageQueue.length > 0) {
        if (this.queueTimer) clearTimeout(this.queueTimer);
        this.queueTimer = setTimeout(() => this.processQueue(), 350);
      }
    }
  }

  private async synthesizeSpeech(message: VoiceMessage): Promise<void> {
    // 优先调用原生 TTS
    if (this.voiceModule && typeof this.voiceModule.speak === 'function') {
      try {
        const ok = await this.voiceModule.speak(message.text);
        if (ok !== false) {
          await this.delay(Math.max(300, message.text.length * 180));
          return;
        }
      } catch (err) {
        log.warn('[Voice] 原生TTS调用失败，回退:', err);
      }
    }

    // 回退到 Web Speech API
    if (this.synthRef) {
      try {
        await new Promise<void>((resolve) => {
          const SpeechSynthesisUtterance = (global as any).SpeechSynthesisUtterance;
          if (!SpeechSynthesisUtterance) {
            resolve();
            return;
          }
          const utterance = new SpeechSynthesisUtterance(message.text);
          utterance.lang = this.config.language;
          utterance.rate = this.config.rate;
          utterance.pitch = this.config.pitch;
          utterance.volume = this.config.volume;

          utterance.onend = () => resolve();
          utterance.onerror = () => resolve();

          this.currentUtterance = utterance;
          this.synthRef.speak(utterance);
        });
        return;
      } catch (err) {
        log.warn('[Voice] Web Speech 失败:', err);
      }
    }

    // 最后兜底：简单延时
    await this.delay(Math.max(300, message.text.length * 180));
  }

  stopSpeaking(): void {
    if (this.voiceModule && typeof this.voiceModule.stop === 'function') {
      try {
        this.voiceModule.stop();
      } catch {}
    }
    if (this.synthRef) {
      try {
        this.synthRef.cancel();
      } catch {}
    }
    if (this.queueTimer) clearTimeout(this.queueTimer);
    this.messageQueue = [];
    this.isSpeaking = false;
    this.emit('speaking-stopped');
  }

  speakScore(score: number, grade: string): void {
    if (!this.config.autoSpeakScore) return;
    const text = `当前评分 ${score.toFixed(1)} 分，${grade}`;
    this.speak(text, 'score');
  }

  speakTask(task: string): void {
    if (!this.config.autoSpeakTasks) return;
    this.speak(task, 'task');
  }

  speakWarning(warning: string): void {
    if (!this.config.autoSpeakWarnings) return;
    this.speak(`注意: ${warning}`, 'warning');
  }

  getHistory(): VoiceMessage[] {
    return [...this.history];
  }

  clearHistory(): void {
    this.history = [];
    this.emit('history-cleared');
  }

  isSpeakingNow(): boolean {
    return this.isSpeaking;
  }

  isListeningNow(): boolean {
    return this.isListening;
  }

  isEnabled(): boolean {
    return this.config.enabled;
  }

  async clearAllData(): Promise<void> {
    await storage.removeItem('lookgm_voice_config');
    this.config = { ...DEFAULT_CONFIG };
    this.history = [];
    this.messageQueue = [];
    this.emit('all-data-cleared');
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
