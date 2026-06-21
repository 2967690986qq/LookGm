import { EventEmitter } from 'events';
import { Mutex } from 'async-mutex';
import log from 'loglevel';

export interface CaptureFrame {
  timestamp: number;
  width: number;
  height: number;
  data: ArrayBuffer;
  format: 'rgba' | 'yuv';
}

export interface CaptureConfig {
  frameRate: number;
  width: number;
  height: number;
  enabled: boolean;
  source: 'native-api' | 'camera-readback' | 'screen-projection';
}

export class ScreenCaptureService extends EventEmitter {
  private static instance: ScreenCaptureService;
  private config: CaptureConfig = {
    frameRate: 5,
    width: 720,
    height: 1280,
    enabled: false,
    source: 'native-api',
  };
  private running: boolean = false;
  private frameMutex: Mutex = new Mutex();
  private lastFrameTime: number = 0;

  private constructor() {
    super();
  }

  static getInstance(): ScreenCaptureService {
    if (!ScreenCaptureService.instance) {
      ScreenCaptureService.instance = new ScreenCaptureService();
    }
    return ScreenCaptureService.instance;
  }

  configure(config: Partial<CaptureConfig>): void {
    this.config = { ...this.config, ...config };
    log.info('[ScreenCapture] 配置已更新:', this.config);
  }

  async start(): Promise<boolean> {
    if (this.running) {
      log.warn('[ScreenCapture] 已在运行中');
      return true;
    }
    this.running = true;
    this.emit('start');
    log.info('[ScreenCapture] 启动采集服务');
    this.loop();
    return true;
  }

  stop(): void {
    this.running = false;
    this.emit('stop');
    log.info('[ScreenCapture] 停止采集服务');
  }

  isRunning(): boolean {
    return this.running;
  }

  getConfig(): CaptureConfig {
    return { ...this.config };
  }

  private async loop(): Promise<void> {
    while (this.running) {
      const release = await this.frameMutex.acquire();
      try {
        const now = Date.now();
        const interval = 1000 / this.config.frameRate;
        if (now - this.lastFrameTime >= interval) {
          const frame = await this.captureFrame();
          if (frame) {
            this.lastFrameTime = now;
            this.emit('frame', frame);
          }
        }
        await this.sleep(Math.max(16, Math.floor(1000 / this.config.frameRate) - 2));
      } catch (err) {
        log.error('[ScreenCapture] 采集循环错误:', err);
      } finally {
        release();
      }
    }
  }

  private async captureFrame(): Promise<CaptureFrame | null> {
    return {
      timestamp: Date.now(),
      width: this.config.width,
      height: this.config.height,
      data: new ArrayBuffer(0),
      format: 'rgba',
    };
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
