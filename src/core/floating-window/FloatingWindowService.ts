import { EventEmitter } from 'events';
import log from 'loglevel';
import storage from '../../utils/Storage';

// React Native 桥接（在浏览器环境可能为 undefined）
let NativeModules: any = undefined;
let DeviceEventEmitter: any = undefined;
try {
  const rn = require('react-native');
  NativeModules = rn.NativeModules;
  DeviceEventEmitter = rn.DeviceEventEmitter;
} catch (err) {
  // 浏览器/Node 环境：使用空实现
}

export type WindowPosition = 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right' | 'center';
export type WindowSize = 'small' | 'medium' | 'large';
export type WindowMode = 'score-display' | 'task-guide' | 'chat' | 'minimized';

export interface WindowConfig {
  enabled: boolean;
  position: WindowPosition;
  size: WindowSize;
  mode: WindowMode;
  opacity: number;
  draggable: boolean;
  showScore: boolean;
  showTasks: boolean;
  showChat: boolean;
  autoHide: boolean;
  autoHideDelay: number;
  colorScheme: 'dark' | 'light' | 'system';
}

const DEFAULT_CONFIG: WindowConfig = {
  enabled: false,
  position: 'top-right',
  size: 'medium',
  mode: 'score-display',
  opacity: 0.9,
  draggable: true,
  showScore: true,
  showTasks: true,
  showChat: false,
  autoHide: false,
  autoHideDelay: 5000,
  colorScheme: 'light',
};

export interface WindowState {
  isVisible: boolean;
  isMinimized: boolean;
  position: { x: number; y: number };
  size: { width: number; height: number };
}

const WINDOW_SIZE_MAP: Record<WindowSize, { width: number; height: number }> = {
  small: { width: 180, height: 120 },
  medium: { width: 280, height: 180 },
  large: { width: 380, height: 260 },
};

const WINDOW_POSITION_MAP: Record<WindowPosition, { x: number; y: number }> = {
  'top-left': { x: 20, y: 80 },
  'top-right': { x: -20, y: 80 },
  'bottom-left': { x: 20, y: -20 },
  'bottom-right': { x: -20, y: -20 },
  'center': { x: 0, y: 0 },
};

export class FloatingWindowService extends EventEmitter {
  private static instance: FloatingWindowService;
  private config: WindowConfig;
  private state: WindowState;
  private isRunning: boolean = false;
  private hideTimer: ReturnType<typeof setTimeout> | null = null;
  private floatingWindowModule: any;

  private constructor() {
    super();
    this.config = { ...DEFAULT_CONFIG };
    this.state = {
      isVisible: false,
      isMinimized: false,
      position: { x: WINDOW_POSITION_MAP[this.config.position].x, y: WINDOW_POSITION_MAP[this.config.position].y },
      size: { ...WINDOW_SIZE_MAP[this.config.size] },
    };

    // 获取原生悬浮窗模块
    if (NativeModules && NativeModules.LookGmFloatingWindow) {
      this.floatingWindowModule = NativeModules.LookGmFloatingWindow;
      log.info('[FloatingWindow] 原生模块可用');
    } else {
      this.floatingWindowModule = null;
      log.warn('[FloatingWindow] 原生模块不可用（非React Native环境）');
    }

    this.loadFromStorage();
    this.bindNativeEvents();
  }

  static getInstance(): FloatingWindowService {
    if (!FloatingWindowService.instance) {
      FloatingWindowService.instance = new FloatingWindowService();
    }
    return FloatingWindowService.instance;
  }

  private bindNativeEvents(): void {
    if (!DeviceEventEmitter) return;
    try {
      DeviceEventEmitter.addListener('FloatingWindow:onShow', () => {
        this.isRunning = true;
        this.state.isVisible = true;
        this.emit('started', this.state);
      });
      DeviceEventEmitter.addListener('FloatingWindow:onHide', () => {
        this.isRunning = false;
        this.state.isVisible = false;
        this.emit('stopped');
      });
    } catch (err) {
      log.warn('[FloatingWindow] 事件绑定失败:', err);
    }
  }

  private async loadFromStorage(): Promise<void> {
    try {
      const saved = await storage.getItem('lookgm_window_config');
      if (saved) {
        const parsed = JSON.parse(saved);
        this.config = { ...DEFAULT_CONFIG, ...parsed };
      }
    } catch (err) {
      log.error('[FloatingWindow] 加载配置失败:', err);
    }
  }

  private async saveToStorage(): Promise<void> {
    try {
      await storage.setItem('lookgm_window_config', JSON.stringify(this.config));
    } catch (err) {
      log.error('[FloatingWindow] 保存配置失败:', err);
    }
  }

  configure(config: Partial<WindowConfig>): void {
    this.config = { ...this.config, ...config };
    this.state.size = { ...WINDOW_SIZE_MAP[this.config.size] };
    this.saveToStorage();
    this.emit('config-updated', this.config);
  }

  getConfig(): WindowConfig {
    return { ...this.config };
  }

  getState(): WindowState {
    return { ...this.state };
  }

  async canDrawOverlays(): Promise<boolean> {
    if (this.floatingWindowModule && typeof this.floatingWindowModule.canDrawOverlays === 'function') {
      try {
        return await this.floatingWindowModule.canDrawOverlays();
      } catch (err) {
        log.error('[FloatingWindow] 权限检查失败:', err);
      }
    }
    return true; // 非Android环境默认返回true
  }

  async requestOverlayPermission(): Promise<boolean> {
    if (this.floatingWindowModule && typeof this.floatingWindowModule.requestOverlayPermission === 'function') {
      try {
        return await this.floatingWindowModule.requestOverlayPermission();
      } catch (err) {
        log.error('[FloatingWindow] 权限请求失败:', err);
      }
    }
    return true;
  }

  async start(): Promise<boolean> {
    if (this.isRunning) {
      return true;
    }

    // 有原生模块：调用原生实现
    if (this.floatingWindowModule) {
      try {
        const ok = await this.floatingWindowModule.show();
        if (ok) {
          this.isRunning = true;
          this.state.isVisible = true;
          this.config.enabled = true;
          this.saveToStorage();
          this.emit('started', this.state);
          log.info('[FloatingWindow] 原生悬浮窗已启动');
          return true;
        } else {
          // 尝试请求权限
          const perm = await this.requestOverlayPermission();
          log.warn('[FloatingWindow] 需要悬浮窗权限:', perm);
          this.emit('permission-required');
          return false;
        }
      } catch (err) {
        log.error('[FloatingWindow] 启动失败:', err);
        return false;
      }
    }

    // 非原生环境（浏览器等）：仅内存状态
    this.isRunning = true;
    this.state.isVisible = true;
    this.config.enabled = true;
    this.saveToStorage();
    this.emit('started', this.state);
    return true;
  }

  async stop(): Promise<boolean> {
    if (this.floatingWindowModule && this.isRunning) {
      try {
        await this.floatingWindowModule.hide();
      } catch (err) {
        log.error('[FloatingWindow] 停止失败:', err);
      }
    }

    if (this.hideTimer) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }

    this.isRunning = false;
    this.state.isVisible = false;
    this.saveToStorage();
    this.emit('stopped');
    return true;
  }

  show(): void {
    this.start();
  }

  hide(): void {
    this.stop();
  }

  updateScoreDisplay(score: number, grade: string, breakdown?: any): void {
    if (!this.config.showScore) return;
    this.emit('score-updated', { score, grade, breakdown });

    if (this.floatingWindowModule && typeof this.floatingWindowModule.updateScore === 'function') {
      try {
        this.floatingWindowModule.updateScore(
          Number(score).toFixed(1),
          grade || '暂无评级'
        );
      } catch (err) {
        log.warn('[FloatingWindow] 更新评分显示失败:', err);
      }
    }
  }

  updateStatus(text: string): void {
    if (this.floatingWindowModule && typeof this.floatingWindowModule.updateStatus === 'function') {
      try {
        this.floatingWindowModule.updateStatus(text || 'LookGm');
      } catch (err) {
        log.warn('[FloatingWindow] 更新状态失败:', err);
      }
    }
  }

  updateTasksDisplay(tasks: string[]): void {
    this.emit('tasks-updated', tasks);
  }

  updateChatDisplay(message: string, isUser: boolean): void {
    this.emit('chat-updated', { message, isUser, timestamp: Date.now() });
  }

  setPosition(position: WindowPosition): void {
    this.config.position = position;
    const pos = WINDOW_POSITION_MAP[position];
    this.state.position = { ...pos };
    this.saveToStorage();
    this.emit('position-changed', this.state.position);
  }

  setSize(size: WindowSize): void {
    this.config.size = size;
    this.state.size = { ...WINDOW_SIZE_MAP[size] };
    this.saveToStorage();
    this.emit('size-changed', this.state.size);
  }

  setMode(mode: WindowMode): void {
    this.config.mode = mode;
    this.saveToStorage();
    this.emit('mode-changed', mode);
  }

  isEnabled(): boolean {
    return this.config.enabled;
  }

  isRunning(): boolean {
    return this.isRunning;
  }

  async clearAllData(): Promise<void> {
    await storage.removeItem('lookgm_window_config');
    await storage.removeItem('lookgm_window_state');
    this.config = { ...DEFAULT_CONFIG };
    this.state = {
      isVisible: false,
      isMinimized: false,
      position: { x: WINDOW_POSITION_MAP[this.config.position].x, y: WINDOW_POSITION_MAP[this.config.position].y },
      size: { ...WINDOW_SIZE_MAP[this.config.size] },
    };
    this.emit('all-data-cleared');
  }
}
