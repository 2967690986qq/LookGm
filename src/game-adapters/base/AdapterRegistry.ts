import log from 'loglevel';
import { GameAdapter } from './GameAdapter';
import { GameInfo, Platform } from '@types/game';

export class AdapterRegistry {
  private static instance: AdapterRegistry;
  private adapters: Map<string, GameAdapter> = new Map();
  private activeAdapterId: string | null = null;

  private constructor() {}

  static getInstance(): AdapterRegistry {
    if (!AdapterRegistry.instance) {
      AdapterRegistry.instance = new AdapterRegistry();
    }
    return AdapterRegistry.instance;
  }

  register(adapter: GameAdapter): void {
    const id = adapter.getGameInfo().id;
    this.adapters.set(id, adapter);
    log.info(`[AdapterRegistry] 注册游戏适配: ${id}`);
  }

  unregister(gameId: string): void {
    this.adapters.delete(gameId);
  }

  get(gameId: string): GameAdapter | undefined {
    return this.adapters.get(gameId);
  }

  getAll(): GameAdapter[] {
    return Array.from(this.adapters.values());
  }

  listGames(): GameInfo[] {
    return this.getAll().map(a => a.getGameInfo());
  }

  listByPlatform(platform: Platform): GameInfo[] {
    return this.getAll()
      .filter(a => a.getGameInfo().supportedPlatforms.includes(platform))
      .map(a => a.getGameInfo());
  }

  activate(gameId: string, config?: any): boolean {
    if (this.activeAdapterId) {
      const active = this.adapters.get(this.activeAdapterId);
      if (active) active.deactivate();
    }
    const adapter = this.adapters.get(gameId);
    if (!adapter) {
      log.warn(`[AdapterRegistry] 未找到游戏适配: ${gameId}`);
      return false;
    }
    adapter.activate(config);
    this.activeAdapterId = gameId;
    return true;
  }

  deactivate(): void {
    if (this.activeAdapterId) {
      const adapter = this.adapters.get(this.activeAdapterId);
      if (adapter) adapter.deactivate();
      this.activeAdapterId = null;
    }
  }

  getActive(): GameAdapter | null {
    return this.activeAdapterId ? this.adapters.get(this.activeAdapterId) || null : null;
  }
}
