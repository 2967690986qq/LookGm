import { EventEmitter } from 'events';
import log from 'loglevel';
import { GameState, GamePhase, DetectedElement, ScreenRegion } from '@types/game';
import { ScreenCaptureService, CaptureFrame } from '../screen-capture/ScreenCaptureService';

export interface RegionTemplate {
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
  expectedColor?: { r: number; g: number; b: number; tolerance: number };
  containsText?: string;
}

export interface AIConfig {
  modelPath: string;
  enabled: boolean;
  minConfidence: number;
  processingMode: 'local-tflite' | 'api-vision' | 'hybrid';
}

export class AIAnalysisService extends EventEmitter {
  private static instance: AIAnalysisService;
  private config: AIConfig = {
    modelPath: 'models/default.tflite',
    enabled: true,
    minConfidence: 0.5,
    processingMode: 'local-tflite',
  };
  private captureService: ScreenCaptureService;
  private registeredRegions: Map<string, RegionTemplate[]> = new Map();
  private running: boolean = false;

  private constructor() {
    super();
    this.captureService = ScreenCaptureService.getInstance();
    this.captureService.on('frame', frame => this.analyzeFrame(frame));
  }

  static getInstance(): AIAnalysisService {
    if (!AIAnalysisService.instance) {
      AIAnalysisService.instance = new AIAnalysisService();
    }
    return AIAnalysisService.instance;
  }

  configure(config: Partial<AIConfig>): void {
    this.config = { ...this.config, ...config };
  }

  registerRegions(gameId: string, regions: RegionTemplate[]): void {
    this.registeredRegions.set(gameId, regions);
    log.info(`[AIAnalysis] 注册 ${gameId} 屏幕区域: ${regions.length} 个`);
  }

  unregisterRegions(gameId: string): void {
    this.registeredRegions.delete(gameId);
  }

  start(): void {
    this.running = true;
    this.emit('start');
  }

  stop(): void {
    this.running = false;
    this.emit('stop');
  }

  private async analyzeFrame(frame: CaptureFrame): Promise<void> {
    if (!this.running || !this.config.enabled) return;
    const elements: DetectedElement[] = [];
    let detectedState: Partial<GameState> = {};
    for (const regions of this.registeredRegions.values()) {
      const result = this.processRegions(regions, frame);
      elements.push(...result.elements);
      if (result.state) {
        detectedState = { ...detectedState, ...result.state };
      }
    }
    this.emit('analyzed', {
      timestamp: frame.timestamp,
      elements,
      state: detectedState,
    });
  }

  private processRegions(
    regions: RegionTemplate[],
    frame: CaptureFrame
  ): { elements: DetectedElement[]; state: Partial<GameState> | null } {
    const elements: DetectedElement[] = [];
    const state: Partial<GameState> = {};
    for (const region of regions) {
      const element = this.analyzeRegion(region, frame);
      if (element) {
        elements.push(element);
      }
    }
    return { elements, state: Object.keys(state).length > 0 ? state : null };
  }

  private analyzeRegion(template: RegionTemplate, frame: CaptureFrame): DetectedElement | null {
    const confidence = this.calculateConfidence(template, frame);
    if (confidence < this.config.minConfidence) return null;
    return {
      type: template.name,
      value: template.containsText || template.name,
      confidence,
      region: {
        name: template.name,
        x: template.x,
        y: template.y,
        width: template.width,
        height: template.height,
        description: '',
      },
    };
  }

  private calculateConfidence(template: RegionTemplate, frame: CaptureFrame): number {
    let score = 0;
    if (template.expectedColor) {
      score += 0.5;
    }
    if (template.containsText) {
      score += 0.4;
    }
    return Math.min(1, score + Math.random() * 0.1);
  }

  inferPhase(elements: DetectedElement[], matchTime: number): GamePhase {
    if (matchTime < 5) return 'loading';
    if (matchTime < 30) return 'hero_select';
    if (matchTime < 360) return 'early_game';
    if (matchTime < 1200) return 'mid_game';
    return 'late_game';
  }
}
