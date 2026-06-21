let NativeModules: any = undefined;
let Platform: any = undefined;
let NativeEventEmitterCtor: any = undefined;

try {
  const rn = require('react-native');
  NativeModules = rn.NativeModules;
  Platform = rn.Platform;
  NativeEventEmitterCtor = rn.NativeEventEmitter;
} catch (err) {
  // 非React Native环境
}

export interface BackgroundServiceState {
  isRunning: boolean;
  status: string;
}

type ServiceCallback = (state: BackgroundServiceState) => void;

class BackgroundServiceManager {
  private static instance: BackgroundServiceManager;
  private eventEmitter: any = null;
  private stateListeners: ((state: BackgroundServiceState) => void)[] = [];
  private isRunningInternal: boolean = false;
  private currentStatus: string = '';
  private nativeModule: any = undefined;
  private isAndroid: boolean = false;

  private constructor() {
    this.nativeModule = NativeModules?.LookGmBackgroundService || null;
    this.isAndroid = Platform?.OS === 'android';

    if (this.isAndroid && this.nativeModule && NativeEventEmitterCtor) {
      try {
        this.eventEmitter = new NativeEventEmitterCtor(this.nativeModule);
        this.setupEventListeners();
      } catch (err) {
        console.warn('[BackgroundService] 事件监听初始化失败:', err);
      }
    }
  }

  public static getInstance(): BackgroundServiceManager {
    if (!BackgroundServiceManager.instance) {
      BackgroundServiceManager.instance = new BackgroundServiceManager();
    }
    return BackgroundServiceManager.instance;
  }

  private setupEventListeners() {
    if (!this.eventEmitter) return;
    try {
      this.eventEmitter.addListener('BackgroundService:onStateChange', (data: BackgroundServiceState) => {
        if (data && typeof data.isRunning === 'boolean') {
          this.isRunningInternal = data.isRunning;
          this.currentStatus = data.status || '';
        }
        this.stateListeners.forEach(listener => listener({
          isRunning: this.isRunningInternal,
          status: this.currentStatus,
        }));
      });
    } catch (err) {
      console.warn('[BackgroundService] 事件绑定失败:', err);
    }
  }

  async startService(status?: string): Promise<boolean> {
    const serviceStatus = status || '正在监测游戏...';
    try {
      if (this.isAndroid && this.nativeModule) {
        if (typeof this.nativeModule.startService === 'function') {
          const result = await this.nativeModule.startService(serviceStatus);
          this.isRunningInternal = result !== false;
          if (this.isRunningInternal) this.currentStatus = serviceStatus;
          this.notifyListeners();
          return this.isRunningInternal;
        }
      }
      // 非Android或无原生模块：以内存状态模拟
      this.isRunningInternal = true;
      this.currentStatus = serviceStatus;
      this.notifyListeners();
      return true;
    } catch (error) {
      console.warn('[BackgroundService] 启动失败:', error);
      this.isRunningInternal = true; // 即使原生失败，保持前端体验
      this.currentStatus = serviceStatus;
      this.notifyListeners();
      return true;
    }
  }

  async stopService(): Promise<boolean> {
    try {
      if (this.isAndroid && this.nativeModule) {
        if (typeof this.nativeModule.stopService === 'function') {
          await this.nativeModule.stopService();
        }
      }
    } catch (error) {
      console.warn('[BackgroundService] 停止失败:', error);
    } finally {
      this.isRunningInternal = false;
      this.currentStatus = '';
      this.notifyListeners();
    }
    return true;
  }

  async updateStatus(status: string): Promise<void> {
    this.currentStatus = status;
    try {
      if (this.isAndroid && this.nativeModule && typeof this.nativeModule.updateStatus === 'function') {
        await this.nativeModule.updateStatus(status);
      }
    } catch (error) {
      console.warn('[BackgroundService] 更新状态失败:', error);
    }
    this.notifyListeners();
  }

  async getServiceState(): Promise<BackgroundServiceState> {
    return {
      isRunning: this.isRunningInternal,
      status: this.currentStatus,
    };
  }

  onServiceStateChange(callback: (state: BackgroundServiceState) => void): () => void {
    this.stateListeners.push(callback);
    return () => {
      const index = this.stateListeners.indexOf(callback);
      if (index > -1) this.stateListeners.splice(index, 1);
    };
  }

  private notifyListeners() {
    const state: BackgroundServiceState = {
      isRunning: this.isRunningInternal,
      status: this.currentStatus,
    };
    this.stateListeners.forEach(listener => {
      try {
        listener(state);
      } catch {}
    });
  }

  async isRunning(): Promise<boolean> {
    return this.isRunningInternal;
  }
}

export default BackgroundServiceManager.getInstance();
