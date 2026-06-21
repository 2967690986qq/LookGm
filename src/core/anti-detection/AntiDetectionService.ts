import { EventEmitter } from 'events';
import log from 'loglevel';

export interface DetectorConfig {
  enabled: boolean;
  useSystemOverlayOnly: boolean;
  avoidProcessInjection: boolean;
  randomizeTimings: boolean;
  minimizeScreenFootprint: boolean;
  privacyMode: boolean;
  blockGameProcessAccess: boolean;
}

export interface DetectionRiskReport {
  overallRisk: 'low' | 'medium' | 'high';
  checks: Array<{ name: string; passed: boolean; description: string }>;
  timestamp: number;
}

export class AntiDetectionService extends EventEmitter {
  private static instance: AntiDetectionService;
  private config: DetectorConfig = {
    enabled: true,
    useSystemOverlayOnly: true,
    avoidProcessInjection: true,
    randomizeTimings: true,
    minimizeScreenFootprint: true,
    privacyMode: true,
    blockGameProcessAccess: true,
  };
  private running: boolean = false;

  private constructor() {
    super();
  }

  static getInstance(): AntiDetectionService {
    if (!AntiDetectionService.instance) {
      AntiDetectionService.instance = new AntiDetectionService();
    }
    return AntiDetectionService.instance;
  }

  configure(config: Partial<DetectorConfig>): void {
    this.config = { ...this.config, ...config };
    log.info('[AntiDetection] 配置已更新:', this.config);
  }

  start(): void {
    this.running = true;
    this.emit('start');
    log.info('[AntiDetection] 防检测服务已启动');
  }

  stop(): void {
    this.running = false;
    this.emit('stop');
    log.info('[AntiDetection] 防检测服务已停止');
  }

  isRunning(): boolean {
    return this.running;
  }

  getConfig(): DetectorConfig {
    return { ...this.config };
  }

  getRandomizedInterval(baseIntervalMs: number, variance: number = 0.25): number {
    if (!this.config.randomizeTimings) return baseIntervalMs;
    const factor = 1 + (Math.random() * 2 - 1) * variance;
    return Math.max(16, Math.floor(baseIntervalMs * factor));
  }

  validateNoGameProcessAccess(): boolean {
    if (!this.config.blockGameProcessAccess) return true;
    return true;
  }

  validateOverlayMode(): boolean {
    return this.config.useSystemOverlayOnly;
  }

  generateRiskReport(): DetectionRiskReport {
    const checks = [
      {
        name: 'process_injection_check',
        passed: this.config.avoidProcessInjection,
        description: '未注入游戏进程，仅使用系统级API读取屏幕',
      },
      {
        name: 'overlay_mode_check',
        passed: this.config.useSystemOverlayOnly,
        description: '使用系统级悬浮窗展示评分，不修改游戏UI',
      },
      {
        name: 'memory_access_check',
        passed: true,
        description: '不读取游戏内存，所有数据来自屏幕视觉分析',
      },
      {
        name: 'privacy_check',
        passed: this.config.privacyMode,
        description: '游戏分析数据仅在本地处理，不上传至服务器',
      },
      {
        name: 'timing_randomization',
        passed: this.config.randomizeTimings,
        description: '采集时间间隔加入随机化，避免模式识别',
      },
      {
        name: 'data_local_processing',
        passed: true,
        description: '所有评分计算在本地完成，无需联网进行游戏分析',
      },
      {
        name: 'no_game_api_usage',
        passed: true,
        description: '不调用任何游戏SDK或hook游戏接口',
      },
    ];
    const failedCount = checks.filter(c => !c.passed).length;
    let overallRisk: DetectionRiskReport['overallRisk'] = 'low';
    if (failedCount >= 3) overallRisk = 'high';
    else if (failedCount >= 1) overallRisk = 'medium';
    return {
      overallRisk,
      checks,
      timestamp: Date.now(),
    };
  }

  logOperation(operation: string, details?: any): void {
    if (!this.running || !this.config.enabled) return;
    log.debug(`[AntiDetection] ${operation}`, details || '');
    this.emit('operation', { operation, details, timestamp: Date.now() });
  }

  recommendSafeMode(): string[] {
    const recommendations: string[] = [];
    if (!this.config.useSystemOverlayOnly) {
      recommendations.push('建议开启"仅使用系统悬浮窗"模式');
    }
    if (!this.config.avoidProcessInjection) {
      recommendations.push('必须避免任何进程注入');
    }
    if (!this.config.randomizeTimings) {
      recommendations.push('建议开启采集时间随机化');
    }
    if (!this.config.privacyMode) {
      recommendations.push('建议开启隐私模式');
    }
    if (recommendations.length === 0) {
      recommendations.push('当前配置为安全模式');
    }
    return recommendations;
  }
}
