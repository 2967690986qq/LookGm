import { EventEmitter } from 'events';
import log from 'loglevel';
import storage from '../../utils/Storage';

export type MonitoringMode = 'capture' | 'analysis' | 'recording';
export type RecordingFormat = 'mp4' | 'webm' | 'gif';
export type CaptureQuality = 'low' | 'medium' | 'high';

export interface MonitoringConfig {
  enabled: boolean;
  mode: MonitoringMode;
  fps: number;
  resolution: { width: number; height: number };
  quality: CaptureQuality;
  enableRecording: boolean;
  recordingFormat: RecordingFormat;
  maxRecordingDuration: number;
  enableOCR: boolean;
  enableObjectDetection: boolean;
  enableHealthBarDetection: boolean;
  enableScorePanelDetection: boolean;
  autoSave: boolean;
  savePath: string;
  captureIntervalMs: number;
}

export interface FrameData {
  timestamp: number;
  frameId: string;
  imageData?: any;
  width: number;
  height: number;
  fps: number;
  analysis?: AnalysisResult;
}

export interface AnalysisResult {
  healthPercent?: number;
  manaPercent?: number;
  kills?: number;
  deaths?: number;
  assists?: number;
  gold?: number;
  level?: number;
  detectedObjects?: DetectedObject[];
  ocrText?: string;
  timestamp: number;
}

export interface DetectedObject {
  id: string;
  label: string;
  confidence: number;
  bbox: { x: number; y: number; width: number; height: number };
}

export interface RecordingSession {
  id: string;
  startTime: number;
  endTime?: number;
  duration?: number;
  fileSize?: number;
  format: RecordingFormat;
  filePath?: string;
  frameCount: number;
  status: 'recording' | 'paused' | 'completed' | 'error';
}

const DEFAULT_CONFIG: MonitoringConfig = {
  enabled: false,
  mode: 'capture',
  fps: 15,
  resolution: { width: 1920, height: 1080 },
  quality: 'medium',
  enableRecording: false,
  recordingFormat: 'mp4',
  maxRecordingDuration: 3600,
  enableOCR: true,
  enableObjectDetection: true,
  enableHealthBarDetection: true,
  enableScorePanelDetection: true,
  autoSave: true,
  savePath: 'lookgm/recordings',
  captureIntervalMs: 2000,
};

const QUALITY_MAP: Record<CaptureQuality, { bitrate: number; compression: number }> = {
  low: { bitrate: 1000000, compression: 0.3 },
  medium: { bitrate: 2500000, compression: 0.6 },
  high: { bitrate: 5000000, compression: 0.9 },
};

export class ScreenMonitoringService extends EventEmitter {
  private static instance: ScreenMonitoringService;
  private config: MonitoringConfig;
  private isRunning: boolean = false;
  private isRecording: boolean = false;
  private isPaused: boolean = false;
  private currentSession: RecordingSession | null = null;
  private frameCount: number = 0;
  private lastCaptureTime: number = 0;
  private currentFps: number = 0;
  private analysisResults: AnalysisResult[] = [];
  private recordings: RecordingSession[] = [];
  private captureTimer: ReturnType<typeof setInterval> | null = null;
  private mediaRecorderRef: any = null;
  private recordedChunks: any[] = [];

  private constructor() {
    super();
    this.config = { ...DEFAULT_CONFIG };
    this.loadFromStorage();
  }

  static getInstance(): ScreenMonitoringService {
    if (!ScreenMonitoringService.instance) {
      ScreenMonitoringService.instance = new ScreenMonitoringService();
    }
    return ScreenMonitoringService.instance;
  }

  private getLocalStorage() {
    return storage;
  }

  private async loadFromStorage(): Promise<void> {
    try {
      const ls = this.getLocalStorage();
      const saved = await ls.getItem('lookgm_monitoring_config');
      if (saved) {
        const parsed = JSON.parse(saved);
        this.config = { ...DEFAULT_CONFIG, ...parsed };
      }
      const savedRecordings = await ls.getItem('lookgm_recordings');
      if (savedRecordings) {
        this.recordings = JSON.parse(savedRecordings);
      }
      log.info('[ScreenMonitoring] 配置已加载');
    } catch (err) {
      log.error('[ScreenMonitoring] 加载配置失败:', err);
    }
  }

  private async saveToStorage(): Promise<void> {
    try {
      const ls = this.getLocalStorage();
      await ls.setItem('lookgm_monitoring_config', JSON.stringify(this.config));
      await ls.setItem('lookgm_recordings', JSON.stringify(this.recordings.slice(-20)));
    } catch (err) {
      log.error('[ScreenMonitoring] 保存配置失败:', err);
    }
  }

  configure(config: Partial<MonitoringConfig>): void {
    this.config = { ...this.config, ...config };
    this.saveToStorage();
    this.emit('config-updated', this.config);
    log.info('[ScreenMonitoring] 配置已更新');
  }

  getConfig(): MonitoringConfig {
    return { ...this.config };
  }

  async start(): Promise<boolean> {
    if (this.isRunning) {
      log.warn('[ScreenMonitoring] 已在运行中');
      return true;
    }

    try {
      this.isRunning = true;
      this.isPaused = false;
      this.frameCount = 0;
      this.lastCaptureTime = Date.now();

      this.captureTimer = setInterval(() => {
        if (this.isRunning && !this.isPaused) {
          this.captureFrame();
        }
      }, this.config.captureIntervalMs);

      this.emit('started', {
        timestamp: Date.now(),
        config: this.config,
      });
      log.info('[ScreenMonitoring] 已启动');
      return true;
    } catch (err) {
      log.error('[ScreenMonitoring] 启动失败:', err);
      this.isRunning = false;
      return false;
    }
  }

  async stop(): Promise<void> {
    if (!this.isRunning) return;

    if (this.captureTimer) {
      clearInterval(this.captureTimer);
      this.captureTimer = null;
    }

    if (this.isRecording) {
      await this.stopRecording();
    }

    this.isRunning = false;
    this.isPaused = false;
    this.emit('stopped', { frameCount: this.frameCount });
    log.info(`[ScreenMonitoring] 已停止，共采集 ${this.frameCount} 帧`);
  }

  pause(): void {
    if (!this.isRunning) return;
    this.isPaused = true;
    this.emit('paused');
    log.info('[ScreenMonitoring] 已暂停');
  }

  resume(): void {
    if (!this.isRunning) return;
    this.isPaused = false;
    this.emit('resumed');
    log.info('[ScreenMonitoring] 已恢复');
  }

  private captureFrame(): void {
    const now = Date.now();
    const frame: FrameData = {
      timestamp: now,
      frameId: `frame_${now}_${this.frameCount}`,
      width: this.config.resolution.width,
      height: this.config.resolution.height,
      fps: this.currentFps,
    };

    if (this.config.mode === 'analysis' || this.config.enableOCR) {
      frame.analysis = this.analyzeFrame(frame);
    }

    this.frameCount++;
    this.lastCaptureTime = now;

    if (this.frameCount % 30 === 0) {
      this.currentFps = 30000 / (now - this.lastCaptureTime);
    }

    if (this.analysisResults.length > 100) {
      this.analysisResults.shift();
    }
    if (frame.analysis) {
      this.analysisResults.push(frame.analysis);
    }

    this.emit('frame-captured', frame);

    if (this.isRecording && this.mediaRecorderRef) {
      this.recordedChunks.push({
        frameId: frame.frameId,
        timestamp: frame.timestamp,
        data: frame,
      });
    }
  }

  private analyzeFrame(frame: FrameData): AnalysisResult {
    const analysis: AnalysisResult = {
      timestamp: frame.timestamp,
    };

    if (this.config.enableHealthBarDetection) {
      analysis.healthPercent = 80 + Math.random() * 20;
      analysis.manaPercent = 70 + Math.random() * 30;
    }

    if (this.config.enableScorePanelDetection) {
      analysis.kills = Math.floor(Math.random() * 5);
      analysis.deaths = Math.floor(Math.random() * 3);
      analysis.assists = Math.floor(Math.random() * 8);
      analysis.gold = Math.floor(5000 + Math.random() * 10000);
      analysis.level = Math.floor(1 + Math.random() * 15);
    }

    if (this.config.enableOCR) {
      analysis.ocrText = '';
    }

    if (this.config.enableObjectDetection) {
      analysis.detectedObjects = [];
    }

    this.emit('analysis-complete', analysis);
    return analysis;
  }

  async startRecording(): Promise<RecordingSession | null> {
    if (!this.isRunning) {
      log.warn('[ScreenMonitoring] 请先启动监控');
      return null;
    }

    if (this.isRecording) {
      log.warn('[ScreenMonitoring] 正在录制中');
      return this.currentSession;
    }

    try {
      this.isRecording = true;
      this.recordedChunks = [];
      this.currentSession = {
        id: `session_${Date.now()}`,
        startTime: Date.now(),
        format: this.config.recordingFormat,
        frameCount: 0,
        status: 'recording',
      };

      this.emit('recording-started', this.currentSession);
      log.info(`[ScreenMonitoring] 开始录制: ${this.currentSession.id}`);
      return this.currentSession;
    } catch (err) {
      log.error('[ScreenMonitoring] 录制启动失败:', err);
      this.isRecording = false;
      return null;
    }
  }

  async stopRecording(): Promise<RecordingSession | null> {
    if (!this.isRecording || !this.currentSession) return null;

    try {
      this.isRecording = false;
      const duration = Date.now() - this.currentSession.startTime;

      this.currentSession.endTime = Date.now();
      this.currentSession.duration = duration;
      this.currentSession.frameCount = this.recordedChunks.length;
      this.currentSession.status = 'completed';
      this.currentSession.fileSize = this.recordedChunks.length * 100;

      this.recordings.push({ ...this.currentSession });
      if (this.recordings.length > 20) {
        this.recordings.shift();
      }
      this.saveToStorage();

      this.emit('recording-stopped', this.currentSession);
      log.info(`[ScreenMonitoring] 录制停止，时长 ${(duration / 1000).toFixed(1)} 秒，${this.currentSession.frameCount} 帧`);

      const finishedSession = this.currentSession;
      this.currentSession = null;
      this.recordedChunks = [];
      return finishedSession;
    } catch (err) {
      log.error('[ScreenMonitoring] 录制停止失败:', err);
      return null;
    }
  }

  pauseRecording(): void {
    if (!this.isRecording || !this.currentSession) return;
    this.currentSession.status = 'paused';
    this.emit('recording-paused', this.currentSession);
  }

  resumeRecording(): void {
    if (!this.isRecording || !this.currentSession) return;
    this.currentSession.status = 'recording';
    this.emit('recording-resumed', this.currentSession);
  }

  getLatestAnalysis(): AnalysisResult | null {
    if (this.analysisResults.length === 0) return null;
    return this.analysisResults[this.analysisResults.length - 1];
  }

  getAllAnalysis(): AnalysisResult[] {
    return [...this.analysisResults];
  }

  getRecordings(): RecordingSession[] {
    return [...this.recordings];
  }

  getCurrentSession(): RecordingSession | null {
    return this.currentSession;
  }

  isRunning_(): boolean {
    return this.isRunning;
  }

  isRecording_(): boolean {
    return this.isRecording;
  }

  getStats(): {
    frameCount: number;
    fps: number;
    isRunning: boolean;
    isRecording: boolean;
    analysisCount: number;
    recordingCount: number;
  } {
    return {
      frameCount: this.frameCount,
      fps: this.currentFps,
      isRunning: this.isRunning,
      isRecording: this.isRecording,
      analysisCount: this.analysisResults.length,
      recordingCount: this.recordings.length,
    };
  }

  clearAnalysisHistory(): void {
    this.analysisResults = [];
    this.emit('analysis-history-cleared');
  }

  clearRecordings(): void {
    this.recordings = [];
    this.saveToStorage();
    this.emit('recordings-cleared');
  }

  getRecordingById(id: string): RecordingSession | undefined {
    return this.recordings.find(r => r.id === id);
  }

  async clearAllData(): Promise<void> {
    if (this.isRunning) {
      await this.stop();
    }
    const ls = this.getLocalStorage();
    await ls.removeItem('lookgm_monitoring_config');
    await ls.removeItem('lookgm_recordings');
    this.config = { ...DEFAULT_CONFIG };
    this.analysisResults = [];
    this.recordings = [];
    this.recordedChunks = [];
    this.frameCount = 0;
    this.currentSession = null;
    this.emit('all-data-cleared');
    log.info('[ScreenMonitoring] 所有数据已清除');
  }
}
