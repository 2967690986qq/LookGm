import { EventEmitter } from 'events';
import log from 'loglevel';
import storage from '../../utils/Storage';

export type LoginProvider = 'local';

export interface UserProfile {
  id: string;
  nickname: string;
  password?: string;
  avatar?: string;
  avatarColor?: string;
  provider: LoginProvider;
  loginTime: string;
  lastLoginTime?: string;
  level: number;
  totalScore: number;
  gamesPlayed: number;
  preferences: UserPreferences;
}

export interface UserPreferences {
  theme: 'light';
  language: 'zh-CN' | 'en-US';
  autoLogin: boolean;
  enableVoice: boolean;
  enableFloatingWindow: boolean;
  enableRealTimeMonitoring: boolean;
  enableAIAnalysis: boolean;
  enableWakeWord: boolean;
  wakeWord: string;
  voiceRate: number;
  voicePitch: number;
  voiceVolume: number;
  fps: number;
  captureInterval: number;
}

export interface AccountRecord {
  id: string;
  nickname: string;
  password: string;
  avatar?: string;
  avatarColor?: string;
  createdAt: string;
}

export interface LoginResult {
  success: boolean;
  user?: UserProfile;
  token?: string;
  error?: string;
}

const AVATAR_COLORS = [
  '#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4',
  '#FFEAA7', '#DDA0DD', '#98D8C8', '#F7DC6F',
  '#BB8FCE', '#85C1E2', '#F8B500', '#00CED1',
  '#FF8C42', '#6C5CE7', '#26DE81', '#FD79A8',
];

const DEFAULT_PREFERENCES: UserPreferences = {
  theme: 'light',
  language: 'zh-CN',
  autoLogin: true,
  enableVoice: true,
  enableFloatingWindow: false,
  enableRealTimeMonitoring: false,
  enableAIAnalysis: true,
  enableWakeWord: false,
  wakeWord: '你好助手',
  voiceRate: 1.0,
  voicePitch: 1.0,
  voiceVolume: 0.9,
  fps: 15,
  captureInterval: 2000,
};

function getAvatarColor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

function getAvatarInitial(name: string): string {
  if (!name) return '👤';
  const trimmed = name.trim();
  if (trimmed.length === 0) return '👤';
  return trimmed.charAt(0).toUpperCase();
}

function createDefaultAvatar(name: string, overrideColor?: string): { color: string; initial: string } {
  return {
    color: overrideColor || getAvatarColor(name),
    initial: getAvatarInitial(name),
  };
}

export class AuthService extends EventEmitter {
  private static instance: AuthService;
  private currentUser: UserProfile | null = null;
  private sessionToken: string | null = null;
  private accounts: AccountRecord[] = [];
  private readyPromise: Promise<void>;
  private readyResolve?: () => void;

  private constructor() {
    super();
    this.readyPromise = new Promise((resolve) => {
      this.readyResolve = resolve;
    });
    this.loadSession();
    this.loadAccounts();
  }

  async waitForReady(): Promise<void> {
    await this.readyPromise;
  }

  private markReady(): void {
    if (this.readyResolve) {
      this.readyResolve();
      this.readyResolve = undefined;
    }
  }

  static getInstance(): AuthService {
    if (!AuthService.instance) {
      AuthService.instance = new AuthService();
    }
    return AuthService.instance;
  }

  private generateId(): string {
    return `local_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  }

  private createUserFromAccount(account: AccountRecord): UserProfile {
    const avatar = createDefaultAvatar(account.nickname);
    return {
      id: account.id,
      nickname: account.nickname,
      password: account.password,
      avatar: account.avatar || avatar.color,
      avatarColor: account.avatarColor || avatar.color,
      provider: 'local' as LoginProvider,
      loginTime: new Date().toISOString(),
      level: 1,
      totalScore: 0,
      gamesPlayed: 0,
      preferences: { ...DEFAULT_PREFERENCES },
    };
  }

  private async saveToStorage(key: string, value: any): Promise<void> {
    try {
      await storage.setJson(`lookgm_${key}`, value);
    } catch (err) {
      log.error('[Auth] 保存失败:', err);
    }
  }

  private async loadFromStorage<T>(key: string, defaultValue: T | null = null): Promise<T | null> {
    try {
      const result = await storage.getJson<T>(`lookgm_${key}`);
      return result ?? defaultValue;
    } catch (err) {
      log.error('[Auth] 加载失败:', err);
      return defaultValue;
    }
  }

  private async clearFromStorage(key: string): Promise<void> {
    try {
      await storage.removeItem(`lookgm_${key}`);
    } catch (err) {
      log.error('[Auth] 清除失败:', err);
    }
  }

  private async loadSession(): Promise<void> {
    try {
      const user = await this.loadFromStorage<UserProfile>('currentUser');
      const token = await this.loadFromStorage<string>('sessionToken');
      if (user) {
        this.currentUser = user;
        if (token) {
          this.sessionToken = token;
        }
        log.info('[Auth] 已加载会话:', user.nickname);
      }
    } catch (err) {
      log.error('[Auth] 加载会话失败:', err);
    }
    this.markReady();
  }

  private async loadAccounts(): Promise<void> {
    try {
      const accounts = await this.loadFromStorage<AccountRecord[]>('accounts');
      if (accounts && Array.isArray(accounts)) {
        this.accounts = accounts;
        log.info('[Auth] 已加载账号数量:', this.accounts.length);
      }
    } catch (err) {
      log.error('[Auth] 加载账号失败:', err);
    }
  }

  private async saveAccounts(): Promise<void> {
    await this.saveToStorage('accounts', this.accounts);
  }

  private async finalizeLogin(user: UserProfile): Promise<LoginResult> {
    this.sessionToken = `token_${Date.now()}_${Math.random().toString(36).slice(2, 16)}`;
    this.currentUser = user;
    await this.saveToStorage('currentUser', user);
    await this.saveToStorage('sessionToken', this.sessionToken);
    this.emit('login', user);
    log.info('[Auth] 登录成功:', user.nickname);
    return { success: true, user, token: this.sessionToken };
  }

  async register(nickname: string, password: string, avatar?: string, avatarColor?: string): Promise<LoginResult> {
    log.info('[Auth] 注册新账号:', nickname);
    const cleanName = (nickname || '').trim();
    if (cleanName.length === 0) {
      return { success: false, error: '昵称不能为空' };
    }
    if (!password || password.length < 1) {
      return { success: false, error: '请设置密码' };
    }

    const existing = this.accounts.find((a) => a.nickname === cleanName);
    if (existing) {
      return { success: false, error: '该昵称已存在，请换一个昵称' };
    }

    const id = this.generateId();
    const avatarData = createDefaultAvatar(cleanName);
    const account: AccountRecord = {
      id,
      nickname: cleanName,
      password,
      avatar,
      avatarColor: avatarColor || avatarData.color,
      createdAt: new Date().toISOString(),
    };

    this.accounts.push(account);
    await this.saveAccounts();

    const user = this.createUserFromAccount(account);
    return this.finalizeLogin(user);
  }

  async login(nickname: string, password: string): Promise<LoginResult> {
    log.info('[Auth] 账号登录:', nickname);
    const cleanName = (nickname || '').trim();
    if (cleanName.length === 0) {
      return { success: false, error: '请输入昵称' };
    }

    const account = this.accounts.find((a) => a.nickname === cleanName);
    if (!account) {
      return { success: false, error: '账号不存在，请先注册' };
    }

    if (account.password !== password) {
      return { success: false, error: '密码错误' };
    }

    const user = this.createUserFromAccount(account);
    return this.finalizeLogin(user);
  }

  async quickLogin(nickname: string): Promise<LoginResult> {
    log.info('[Auth] 快速登录（无密码）:', nickname);
    const cleanName = (nickname || '').trim();
    const finalNickname = cleanName.length > 0 ? cleanName : '游戏玩家';

    let account = this.accounts.find((a) => a.nickname === finalNickname);
    if (!account) {
      const avatar = createDefaultAvatar(finalNickname);
      account = {
        id: this.generateId(),
        nickname: finalNickname,
        password: '',
        avatar: avatar.color,
        avatarColor: avatar.color,
        createdAt: new Date().toISOString(),
      };
      this.accounts.push(account);
      await this.saveAccounts();
    }

    const user = this.createUserFromAccount(account);
    return this.finalizeLogin(user);
  }

  async logout(): Promise<boolean> {
    log.info('[Auth] 用户退出登录:', this.currentUser?.nickname);
    this.currentUser = null;
    this.sessionToken = null;
    await this.clearFromStorage('currentUser');
    await this.clearFromStorage('sessionToken');
    this.emit('logout');
    return true;
  }

  isLoggedIn(): boolean {
    return this.currentUser !== null;
  }

  getCurrentUser(): UserProfile | null {
    return this.currentUser;
  }

  getAccounts(): AccountRecord[] {
    return [...this.accounts];
  }

  async updateUserProfile(updates: Partial<Omit<UserProfile, 'id' | 'provider' | 'password'>>): Promise<UserProfile | null> {
    if (!this.currentUser) return null;
    this.currentUser = { ...this.currentUser, ...updates };

    if (updates.nickname) {
      const avatar = createDefaultAvatar(updates.nickname);
      if (!this.currentUser.avatarColor) {
        this.currentUser.avatar = avatar.color;
        this.currentUser.avatarColor = avatar.color;
      }
    }

    await this.saveToStorage('currentUser', this.currentUser);

    const accountIndex = this.accounts.findIndex((a) => a.id === this.currentUser!.id);
    if (accountIndex >= 0) {
      const acc = this.accounts[accountIndex];
      if (updates.nickname) acc.nickname = updates.nickname;
      if (updates.avatar) acc.avatar = updates.avatar;
      if (updates.avatarColor) acc.avatarColor = updates.avatarColor;
      await this.saveAccounts();
    }

    this.emit('profile-updated', this.currentUser);
    return this.currentUser;
  }

  async updateNickname(nickname: string): Promise<UserProfile | null> {
    const cleanName = (nickname || '').trim();
    if (cleanName.length === 0) return null;
    return this.updateUserProfile({ nickname: cleanName });
  }

  async updateAvatarColor(color: string): Promise<UserProfile | null> {
    if (!this.currentUser) return null;
    return this.updateUserProfile({ avatar: color, avatarColor: color });
  }

  async updateAvatar(avatarUrl: string): Promise<UserProfile | null> {
    if (!this.currentUser) return null;
    return this.updateUserProfile({ avatar: avatarUrl });
  }

  async updatePreferences(preferences: Partial<UserPreferences>): Promise<UserPreferences> {
    if (this.currentUser) {
      this.currentUser.preferences = { ...this.currentUser.preferences, ...preferences };
      await this.saveToStorage('currentUser', this.currentUser);
      this.emit('preferences-updated', this.currentUser.preferences);
      return this.currentUser.preferences;
    }
    return { ...DEFAULT_PREFERENCES, ...preferences };
  }

  getPreferences(): UserPreferences {
    if (this.currentUser) {
      return this.currentUser.preferences;
    }
    return { ...DEFAULT_PREFERENCES };
  }

  getSessionToken(): string | null {
    return this.sessionToken;
  }

  getAvatarInfo(): { color: string; initial: string; avatarUrl?: string } {
    const user = this.currentUser;
    if (!user) {
      return { color: '#6C5CE7', initial: 'L' };
    }
    const name = user.nickname || '用户';
    return {
      color: user.avatarColor || '#6C5CE7',
      initial: getAvatarInitial(name),
      avatarUrl: user.avatar && user.avatar.startsWith('http') ? user.avatar : undefined,
    };
  }

  getAvailableAvatarColors(): string[] {
    return AVATAR_COLORS;
  }

  async clearLocalData(): Promise<boolean> {
    try {
      await this.clearFromStorage('currentUser');
      await this.clearFromStorage('sessionToken');
      await this.clearFromStorage('accounts');
      this.currentUser = null;
      this.sessionToken = null;
      this.accounts = [];
      return true;
    } catch (err) {
      log.error('[Auth] 清除本地数据失败:', err);
      return false;
    }
  }
}
