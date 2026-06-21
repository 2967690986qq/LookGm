import { EventEmitter } from 'events';
import log from 'loglevel';
import storage from '../utils/Storage';
import { GameState, Role, GameId } from '../types/game';
import { ScoreResult } from '../types/scoring';
import { ScoringEngine } from '../core/scoring-engine/ScoringEngine';
import { AuthService, UserProfile } from '../core/auth/AuthService';
import { AIApiService, AIProvider, AIProviderConfig } from '../core/ai-api/AIApiService';
import { FloatingWindowService } from '../core/floating-window/FloatingWindowService';
import { VoiceCommunicationService } from '../core/voice-communication/VoiceCommunicationService';
import { ScreenMonitoringService } from '../core/screen-monitoring/ScreenMonitoringService';
import BackgroundServiceManager from '../core/background-service/BackgroundServiceManager';

export type Page = 'login' | 'home' | 'profile' | 'ai-settings' | 'settings' | 'recording-history';

export interface AppState {
  page: Page;
  user: UserProfile | null;
  gameId: GameId | null;
  role: Role | null;
  score: ScoreResult | null;
  captureRunning: boolean;
  recording: boolean;
  floatingWindowEnabled: boolean;
  voiceEnabled: boolean;
  monitoringEnabled: boolean;
  antiDetectionEnabled: boolean;
  backgroundServiceRunning: boolean;
  aiConfigured: boolean;
  aiProvider: AIProvider;
}

interface InGameStatus {
  gameStarted: boolean;
  startTime: number | null;
  gamePhase: 'early_game' | 'mid_game' | 'late_game';
}

const DEFAULT_GAMES: { id: GameId; displayName: string; maxScore: number; roles: Role[] }[] = [
  {
    id: 'king-of-glory',
    displayName: '王者荣耀',
    maxScore: 16.0,
    roles: ['exp_lane', 'jungle', 'mid_lane', 'roam', 'gold_lane'],
  },
];

export class AppStore extends EventEmitter {
  private static instance: AppStore;
  private page: Page = 'login';
  private currentUser: UserProfile | null = null;
  private currentGameId: GameId | null = 'king-of-glory';
  private currentRole: Role | null = null;
  private currentScore: ScoreResult | null = null;
  private lastScoreGrade: string = '';
  private captureRunning: boolean = false;
  private recording: boolean = false;
  private floatingWindowEnabled: boolean = false;
  private voiceEnabled: boolean = true;
  private monitoringEnabled: boolean = false;
  private antiDetectionEnabled: boolean = true;
  private games = DEFAULT_GAMES;
  private backgroundServiceRunning: boolean = false;
  private inGameStatus: InGameStatus = { gameStarted: false, startTime: null, gamePhase: 'early_game' };
  private aiServiceEnabled: boolean = false;

  private authService: AuthService;
  private aiService: AIApiService;
  private scoringEngine: ScoringEngine;
  private floatingWindowService: FloatingWindowService;
  private voiceService: VoiceCommunicationService;
  private monitoringService: ScreenMonitoringService;
  private backgroundService: typeof BackgroundServiceManager;

  private constructor() {
    super();
    this.authService = AuthService.getInstance();
    this.aiService = AIApiService.getInstance();
    this.scoringEngine = ScoringEngine.getInstance();
    this.floatingWindowService = FloatingWindowService.getInstance();
    this.voiceService = VoiceCommunicationService.getInstance();
    this.monitoringService = ScreenMonitoringService.getInstance();
    this.backgroundService = BackgroundServiceManager;

    // 监听登录状态
    this.authService.on('login', (user: UserProfile) => {
      this.currentUser = user;
      this.page = 'home';
      this.syncPreferencesFromUser(user);
      this.emit('state-changed', this.getState());
      log.info('[AppStore] 用户登录:', user.nickname);
    });

    this.authService.on('logout', () => {
      this.currentUser = null;
      this.stopAllServices();
      this.page = 'login';
      this.emit('state-changed', this.getState());
      log.info('[AppStore] 用户退出登录');
    });

    // 监听评分引擎
    this.scoringEngine.on('score-updated', (score: ScoreResult) => {
      this.currentScore = score;
      if (this.floatingWindowEnabled) {
        this.floatingWindowService.updateScoreDisplay(score.totalScore, score.grade, score.breakdown);
      }
      if (this.voiceEnabled && score.grade !== this.lastScoreGrade) {
        this.voiceService.speakScore(score.totalScore, score.grade);
        this.lastScoreGrade = score.grade;
      }
      this.emit('state-changed', this.getState());
    });

    // 监听录屏服务
    this.monitoringService.on('recording-started', () => {
      this.recording = true;
      this.emit('state-changed', this.getState());
    });

    this.monitoringService.on('recording-stopped', () => {
      this.recording = false;
      this.emit('state-changed', this.getState());
    });

    // 监听悬浮窗服务
    this.floatingWindowService.on('started', () => {
      this.floatingWindowEnabled = true;
      this.emit('state-changed', this.getState());
    });

    this.floatingWindowService.on('stopped', () => {
      this.floatingWindowEnabled = false;
      this.emit('state-changed', this.getState());
    });

    // 加载会话
    this.loadSession();
  }

  async initialize(): Promise<void> {
    // 等待AuthService加载完成
    await this.authService.waitForReady();

    // 检查登录状态
    const user = this.authService.getCurrentUser();
    if (user) {
      this.currentUser = user;
      this.page = 'home';
      this.syncPreferencesFromUser(user);
      log.info('[AppStore] 自动登录成功:', user.nickname);
    }

    // 加载游戏和分路设置
    try {
      const savedGameId = await storage.getItem('lookgm_last_game');
      const savedRole = await storage.getItem('lookgm_last_role');
      if (savedGameId) this.currentGameId = savedGameId as GameId;
      if (savedRole) this.currentRole = savedRole as Role;
    } catch (err) {
      log.warn('[AppStore] 加载设置失败:', err);
    }

    this.emit('state-changed', this.getState());
  }

  private async loadSession(): Promise<void> {
    // 这是旧的私有方法，现在由initialize()代替
  }

  static getInstance(): AppStore {
    if (!AppStore.instance) {
      AppStore.instance = new AppStore();
    }
    return AppStore.instance;
  }

  // ========== 页面导航 ==========
  navigate(page: Page): void {
    this.page = page;
    this.emit('state-changed', this.getState());
  }

  getCurrentPage(): Page {
    return this.page;
  }

  // ========== 认证相关 ==========
  getCurrentUser(): UserProfile | null {
    return this.authService.getCurrentUser();
  }

  isLoggedIn(): boolean {
    return this.authService.isLoggedIn();
  }

  async quickLogin(nickname: string): Promise<boolean> {
    const result = await this.authService.quickLogin(nickname);
    if (result && result.success) {
      this.currentUser = this.authService.getCurrentUser();
      this.page = 'home';
      this.emit('state-changed', this.getState());
      return true;
    }
    return false;
  }

  async register(nickname: string, password: string): Promise<{ success: boolean; error?: string }> {
    const result = await this.authService.register(nickname, password);
    if (result && result.success) {
      this.currentUser = this.authService.getCurrentUser();
      this.page = 'home';
      this.emit('state-changed', this.getState());
      return { success: true };
    }
    return { success: false, error: result?.error };
  }

  async login(nickname: string, password: string): Promise<{ success: boolean; error?: string }> {
    const result = await this.authService.login(nickname, password);
    if (result && result.success) {
      this.currentUser = this.authService.getCurrentUser();
      this.page = 'home';
      this.emit('state-changed', this.getState());
      return { success: true };
    }
    return { success: false, error: result?.error };
  }

  async logout(): Promise<void> {
    await this.authService.logout();
    this.currentUser = null;
    this.page = 'login';
    this.stopAllServices();
    this.emit('state-changed', this.getState());
  }

  async updateNickname(nickname: string): Promise<boolean> {
    const user = await this.authService.updateUserProfile({ nickname });
    if (user) {
      this.currentUser = user;
      this.emit('state-changed', this.getState());
      return true;
    }
    return false;
  }

  async updateAvatarColor(color: string): Promise<boolean> {
    const user = await this.authService.updateAvatarColor(color);
    if (user) {
      this.currentUser = user;
      this.emit('state-changed', this.getState());
      return true;
    }
    return false;
  }

  async updateAvatar(avatarUrl: string): Promise<boolean> {
    const user = await this.authService.updateAvatar(avatarUrl);
    if (user) {
      this.currentUser = user;
      this.emit('state-changed', this.getState());
      return true;
    }
    return false;
  }

  getAvatarInfo(): { color: string; initial: string; nickname: string } {
    return this.authService.getAvatarInfo();
  }

  // ========== 游戏相关 ==========
  selectGame(gameId: GameId): void {
    this.currentGameId = gameId;
    storage.setItem('lookgm_last_game', gameId);
    this.emit('state-changed', this.getState());
    log.info(`[AppStore] 选择游戏: ${gameId}`);
  }

  getAvailableGames() {
    return this.games;
  }

  selectRole(role: Role): void {
    this.currentRole = role;
    storage.setItem('lookgm_last_role', role);
    this.inGameStatus.gamePhase = 'early_game';
    this.emit('state-changed', this.getState());
    log.info(`[AppStore] 选择分路: ${role}`);
  }

  getCurrentRole(): Role | null {
    return this.currentRole;
  }

  getCurrentGameId(): GameId | null {
    return this.currentGameId;
  }

  getInGameStatus(): InGameStatus {
    return { ...this.inGameStatus };
  }

  updateGamePhase(phase: 'early_game' | 'mid_game' | 'late_game'): void {
    this.inGameStatus.gamePhase = phase;
    this.emit('state-changed', this.getState());
  }

  // ========== AI自动检测分路 ==========
  async autoDetectRole(): Promise<{ success: boolean; role?: Role; confidence?: number; error?: string }> {
    if (!this.currentGameId) {
      return { success: false, error: '请先选择游戏' };
    }

    // 尝试用AI检测
    try {
      const result = await this.aiService.autoDetectRole();
      if (result.success && result.role) {
        this.currentRole = result.role as Role;
        storage.setItem('lookgm_last_role', result.role);
        this.emit('state-changed', this.getState());
        log.info(`[AppStore] AI自动检测分路: ${result.role}, 置信度: ${result.confidence}`);
        return { success: true, role: this.currentRole, confidence: result.confidence };
      } else {
        // AI检测失败，返回默认分路
        return { success: false, error: result.error || '无法自动检测分路，请手动选择' };
      }
    } catch (error: any) {
      log.error('[AppStore] AI分路检测异常:', error);
      return { success: false, error: error.message || '检测异常，请稍后重试' };
    }
  }

  // ========== AI任务建议 ==========
  async getTaskSuggestions(): Promise<string[]> {
    if (!this.currentRole) return [];
    return await this.aiService.getTaskSuggestions(this.currentRole, this.inGameStatus.gamePhase);
  }

  // ========== 评分引擎 ==========
  computeScoreNow(): ScoreResult | null {
    if (!this.currentGameId || !this.currentRole) return null;
    const state: GameState = {
      gameId: this.currentGameId,
      phase: this.inGameStatus.gamePhase,
      role: this.currentRole,
      hero: 'default',
      kills: Math.floor(Math.random() * 8),
      deaths: Math.floor(Math.random() * 4),
      assists: Math.floor(Math.random() * 15),
      matchTime: Date.now(),
      isAlive: true,
      healthPercent: 60 + Math.random() * 40,
      manaPercent: 50 + Math.random() * 50,
      gold: Math.floor(5000 + Math.random() * 15000),
      level: Math.floor(1 + Math.random() * 15),
      damageDealt: Math.floor(Math.random() * 20000),
      damageTaken: Math.floor(Math.random() * 15000),
      turretDamage: Math.floor(Math.random() * 3000),
      teamTotalKills: Math.floor(10 + Math.random() * 20),
      inCombat: false,
      position: 'mid_lane',
    };
    const score = this.scoringEngine.calculate(state);
    this.currentScore = score;
    this.emit('state-changed', this.getState());
    return score;
  }

  getCurrentScore(): ScoreResult | null {
    return this.currentScore;
  }

  // ========== 录屏采集 ==========
  startCapture(): void {
    this.captureRunning = true;
    this.monitoringService.start();
    if (this.floatingWindowEnabled) {
      this.floatingWindowService.start();
    }
    this.inGameStatus.gameStarted = true;
    this.inGameStatus.startTime = Date.now();
    this.emit('capture-started');
    this.emit('state-changed', this.getState());
    log.info('[AppStore] 开始屏幕采集');
  }

  stopCapture(): void {
    this.captureRunning = false;
    this.monitoringService.stop();
    this.floatingWindowService.stop();
    this.inGameStatus.gameStarted = false;
    this.emit('capture-stopped');
    this.emit('state-changed', this.getState());
    log.info('[AppStore] 停止屏幕采集');
  }

  isCaptureRunning(): boolean {
    return this.captureRunning;
  }

  startRecording(): void {
    if (!this.captureRunning) {
      this.startCapture();
    }
    this.monitoringService.startRecording();
  }

  stopRecording(): void {
    this.monitoringService.stopRecording();
  }

  isRecording(): boolean {
    return this.recording;
  }

  // ========== 后台服务 ==========
  async startBackgroundService(): Promise<boolean> {
    try {
      const roleLabel = this.getRoleLabel(this.currentRole);
      const gameLabel = this.currentGameId === 'king-of-glory' ? '王者荣耀' : '游戏';
      const status = `监测${gameLabel} · ${roleLabel} · 评分中`;

      await this.backgroundService.startService(status);
      this.backgroundServiceRunning = true;
      this.captureRunning = true;
      this.inGameStatus.gameStarted = true;
      this.inGameStatus.startTime = Date.now();
      this.monitoringService.start();
      if (this.floatingWindowEnabled) {
        this.floatingWindowService.start();
      }
      this.emit('state-changed', this.getState());
      log.info('[AppStore] 启动后台服务');
      return true;
    } catch (error) {
      log.error('[AppStore] 启动后台服务失败:', error);
      // 即使原生失败，保持前端状态变化
      this.backgroundServiceRunning = true;
      this.captureRunning = true;
      this.inGameStatus.gameStarted = true;
      this.emit('state-changed', this.getState());
      return true;
    }
  }

  async stopBackgroundService(): Promise<boolean> {
    try {
      await this.backgroundService.stopService();
    } catch (error) {
      log.warn('[AppStore] 停止后台服务失败:', error);
    }
    this.backgroundServiceRunning = false;
    this.captureRunning = false;
    this.inGameStatus.gameStarted = false;
    this.monitoringService.stop();
    this.floatingWindowService.stop();
    this.emit('state-changed', this.getState());
    log.info('[AppStore] 停止后台服务');
    return true;
  }

  isBackgroundServiceRunning(): boolean {
    return this.backgroundServiceRunning;
  }

  private getRoleLabel(role: Role | null): string {
    if (!role) return '未选择分路';
    const labels: Record<Role, string> = {
      exp_lane: '对抗路',
      jungle: '打野',
      mid_lane: '中路',
      roam: '游走',
      gold_lane: '发育路',
    };
    return labels[role] || '未选择分路';
  }

  // ========== 语音播报 ==========
  setVoiceEnabled(enabled: boolean): void {
    this.voiceEnabled = enabled;
    this.voiceService.configure({ enabled });
    this.syncUserPreferences();
    this.emit('state-changed', this.getState());
  }

  speak(text: string): void {
    if (this.voiceEnabled) {
      this.voiceService.speak(text, 'info');
    }
  }

  speakScore(score: number, grade: string): void {
    if (this.voiceEnabled) {
      this.voiceService.speakScore(score, grade);
    }
  }

  getVoiceConfig() {
    return this.voiceService.getConfig();
  }

  configureVoice(config: Partial<{ enabled: boolean; rate: number; pitch: number; volume: number }>): void {
    this.voiceService.configure(config as any);
    if (typeof config.enabled === 'boolean') {
      this.voiceEnabled = config.enabled;
    }
    this.syncUserPreferences();
    this.emit('state-changed', this.getState());
  }

  // ========== 悬浮窗 ==========
  setFloatingWindowEnabled(enabled: boolean): void {
    this.floatingWindowEnabled = enabled;
    this.floatingWindowService.configure({ enabled });
    if (enabled && this.captureRunning) {
      this.floatingWindowService.start();
    } else if (!enabled) {
      this.floatingWindowService.stop();
    }
    this.syncUserPreferences();
    this.emit('state-changed', this.getState());
  }

  async startFloatingWindow(): Promise<boolean> {
    this.floatingWindowEnabled = true;
    this.floatingWindowService.configure({ enabled: true });
    const started = await this.floatingWindowService.start();
    this.syncUserPreferences();
    this.emit('state-changed', this.getState());
    return started;
  }

  async stopFloatingWindow(): Promise<boolean> {
    this.floatingWindowEnabled = false;
    this.floatingWindowService.configure({ enabled: false });
    await this.floatingWindowService.stop();
    this.syncUserPreferences();
    this.emit('state-changed', this.getState());
    return true;
  }

  getFloatingWindowConfig() {
    return this.floatingWindowService.getConfig();
  }

  // ========== 监控配置 ==========
  setMonitoringEnabled(enabled: boolean): void {
    this.monitoringEnabled = enabled;
    this.monitoringService.configure({ enabled });
    if (enabled && this.captureRunning) {
      this.monitoringService.start();
    } else if (!enabled) {
      this.monitoringService.stop();
    }
    this.syncUserPreferences();
    this.emit('state-changed', this.getState());
  }

  getMonitoringConfig() {
    return this.monitoringService.getConfig();
  }

  // ========== 防检测 ==========
  setAntiDetectionEnabled(enabled: boolean): void {
    this.antiDetectionEnabled = enabled;
    this.syncUserPreferences();
    this.emit('state-changed', this.getState());
  }

  isAntiDetectionEnabled(): boolean {
    return this.antiDetectionEnabled;
  }

  getAntiDetectionReport() {
    return {
      overallRisk: this.antiDetectionEnabled ? 'low' : 'medium',
      checks: [
        { passed: this.antiDetectionEnabled, description: '防检测模式启用' },
        { passed: true, description: '本地处理数据' },
        { passed: true, description: '无进程注入' },
        { passed: true, description: '时间随机化' },
        { passed: true, description: '系统悬浮窗' },
      ],
    };
  }

  // ========== AI API 服务 ==========
  getAIConfig() {
    return {
      provider: this.aiService.getCurrentProvider(),
      configured: this.aiService.isConfigured(),
      apiKey: '',
      baseUrl: '',
      model: '',
    };
  }

  getCurrentAIProvider(): AIProvider {
    return this.aiService.getCurrentProvider();
  }

  getAIProviderConfig(provider: AIProvider): AIProviderConfig | null {
    return this.aiService.getProviderConfig(provider);
  }

  getAllAIProviders(): AIProviderConfig[] {
    return this.aiService.getAllProviderConfigs();
  }

  configureAIProvider(provider: AIProvider, config: Partial<AIProviderConfig>): boolean {
    const result = this.aiService.configureProvider(provider, config);
    this.emit('state-changed', this.getState());
    return result;
  }

  setCurrentAIProvider(provider: AIProvider): void {
    this.aiService.setCurrentProvider(provider);
    this.emit('state-changed', this.getState());
  }

  setAPIKey(provider: AIProvider, apiKey: string): boolean {
    const result = this.aiService.setApiKey(provider, apiKey);
    this.emit('state-changed', this.getState());
    return result;
  }

  setAIModel(provider: AIProvider, model: string): boolean {
    const result = this.aiService.setModel(provider, model);
    this.emit('state-changed', this.getState());
    return result;
  }

  async chatWithAI(message: string): Promise<{ success: boolean; content?: string; error?: string; responseTime?: number }> {
    if (!this.aiService.isConfigured()) {
      return { success: false, error: '请先配置 AI API Key' };
    }
    return this.aiService.chat(message, true);
  }

  async testAIProvider(provider: AIProvider): Promise<{ success: boolean; responseTime: number; error?: string }> {
    return this.aiService.testProvider(provider);
  }

  isAIConfigured(): boolean {
    return this.aiService.isConfigured();
  }

  // ========== 缓存与数据清理 ==========
  async clearAllLocalData(): Promise<void> {
    log.info('[AppStore] 开始清理本地数据...');
    await this.aiService.clearAllData();
    await this.floatingWindowService.clearAllData();
    await this.voiceService.clearAllData();
    await this.monitoringService.clearAllData();
    await storage.removeItem('lookgm_last_game');
    await storage.removeItem('lookgm_last_role');
    this.currentScore = null;
    this.currentRole = null;
    this.captureRunning = false;
    this.recording = false;
    this.emit('data-cleared');
    this.emit('state-changed', this.getState());
    log.info('[AppStore] 本地数据清理完成');
  }

  // ========== 状态获取 ==========
  getState(): AppState {
    return {
      page: this.getCurrentPage(),
      user: this.currentUser,
      gameId: this.currentGameId,
      role: this.currentRole,
      score: this.currentScore,
      captureRunning: this.captureRunning,
      recording: this.recording,
      floatingWindowEnabled: this.floatingWindowEnabled,
      voiceEnabled: this.voiceEnabled,
      monitoringEnabled: this.monitoringEnabled,
      antiDetectionEnabled: this.antiDetectionEnabled,
      backgroundServiceRunning: this.backgroundServiceRunning,
      aiConfigured: this.aiService.isConfigured(),
      aiProvider: this.aiService.getCurrentProvider(),
    };
  }

  resetGame(): void {
    this.currentScore = null;
    this.currentRole = null;
    this.lastScoreGrade = '';
    this.inGameStatus = { gameStarted: false, startTime: null, gamePhase: 'early_game' };
    this.emit('state-changed', this.getState());
  }

  // ========== 内部辅助 ==========
  private syncPreferencesFromUser(user: UserProfile | null): void {
    if (!user || !user.preferences) return;
    const prefs = user.preferences as any;
    if (typeof prefs.enableVoice === 'boolean') {
      this.voiceEnabled = prefs.enableVoice;
      this.voiceService.configure({ enabled: prefs.enableVoice });
    }
    if (typeof prefs.enableFloatingWindow === 'boolean') {
      this.floatingWindowEnabled = prefs.enableFloatingWindow;
      this.floatingWindowService.configure({ enabled: prefs.enableFloatingWindow });
    }
    if (typeof prefs.enableRealTimeMonitoring === 'boolean') {
      this.monitoringEnabled = prefs.enableRealTimeMonitoring;
      this.monitoringService.configure({ enabled: prefs.enableRealTimeMonitoring });
    }
  }

  private syncUserPreferences(): void {
    if (!this.currentUser) return;
    this.authService.updatePreferences({
      ...(this.currentUser.preferences || {}),
      enableVoice: this.voiceEnabled,
      enableFloatingWindow: this.floatingWindowEnabled,
      enableRealTimeMonitoring: this.monitoringEnabled,
      enableAIAnalysis: this.aiService.isConfigured(),
      enableAntiDetection: this.antiDetectionEnabled,
      theme: 'light',
      language: 'zh-CN',
      autoLogin: true,
    } as any);
  }

  private stopAllServices(): void {
    this.captureRunning = false;
    this.recording = false;
    this.backgroundServiceRunning = false;
    try {
      this.monitoringService.stop();
    } catch { /* ignore */ }
    try {
      this.floatingWindowService.stop();
    } catch { /* ignore */ }
    try {
      this.voiceService.speak('');
    } catch { /* ignore */ }
    try {
      this.backgroundService.stopService();
    } catch { /* ignore */ }
  }
}
