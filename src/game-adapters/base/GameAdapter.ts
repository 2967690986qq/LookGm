import { EventEmitter } from 'events';
import log from 'loglevel';
import { GameInfo, GameState, Role } from '@types/game';
import { ScoringRule, TaskItem } from '@types/scoring';
import { RegionTemplate } from '../../core/ai-analysis/AIAnalysisService';
import { ScoringEngine, RuleSet } from '../../core/scoring-engine/ScoringEngine';
import { AIAnalysisService } from '../../core/ai-analysis/AIAnalysisService';
import { VoiceGuideService } from '../../core/voice-guide/VoiceGuideService';

export interface AdapterConfig {
  autoStart: boolean;
  enableVoice: boolean;
  selectedRole: Role | null;
}

export abstract class GameAdapter extends EventEmitter {
  protected gameInfo: GameInfo;
  protected regions: RegionTemplate[] = [];
  protected scoringRules: ScoringRule[] = [];
  protected tasks: TaskItem[] = [];
  protected state: GameState | null = null;
  protected config: AdapterConfig = {
    autoStart: true,
    enableVoice: true,
    selectedRole: null,
  };
  protected aiService: AIAnalysisService;
  protected scoringEngine: ScoringEngine;
  protected voiceService: VoiceGuideService;
  protected active: boolean = false;

  protected constructor(gameInfo: GameInfo) {
    super();
    this.gameInfo = gameInfo;
    this.aiService = AIAnalysisService.getInstance();
    this.scoringEngine = ScoringEngine.getInstance();
    this.voiceService = VoiceGuideService.getInstance();
  }

  activate(config: Partial<AdapterConfig> = {}): void {
    this.config = { ...this.config, ...config };
    this.aiService.registerRegions(this.gameInfo.id, this.regions);
    this.scoringEngine.registerRuleSet({
      gameId: this.gameInfo.id,
      rules: this.scoringRules,
      tasks: this.tasks,
      maxScore: this.gameInfo.maxScore,
    });
    this.state = this.createInitialState();
    this.active = true;
    this.emit('activated', this.gameInfo);
    log.info(`[GameAdapter] 激活游戏适配: ${this.gameInfo.name}`);
  }

  deactivate(): void {
    this.aiService.unregisterRegions(this.gameInfo.id);
    this.scoringEngine.unregisterRuleSet(this.gameInfo.id);
    this.scoringEngine.reset();
    this.state = null;
    this.active = false;
    this.emit('deactivated', this.gameInfo);
    log.info(`[GameAdapter] 停用游戏适配: ${this.gameInfo.name}`);
  }

  isActive(): boolean {
    return this.active;
  }

  getGameInfo(): GameInfo {
    return this.gameInfo;
  }

  getState(): GameState | null {
    return this.state;
  }

  updateState(partial: Partial<GameState>): void {
    if (!this.state) return;
    const oldPhase = this.state.phase;
    this.state = { ...this.state, ...partial };
    if (oldPhase !== this.state.phase) {
      this.onPhaseChange(this.state.phase);
    }
  }

  computeScore(): void {
    if (!this.state) return;
    const score = this.scoringEngine.calculate(this.state);
    this.emit('score-computed', score);
    if (this.config.enableVoice) {
      this.voiceService.speakScoreUpdate(score);
    }
  }

  protected createInitialState(): GameState {
    return {
      gameId: this.gameInfo.id,
      phase: 'idle',
      role: this.config.selectedRole,
      hero: null,
      kills: 0,
      deaths: 0,
      assists: 0,
      matchTime: 0,
      isAlive: true,
      healthPercent: 100,
      manaPercent: 100,
      gold: 0,
      level: 1,
      damageDealt: 0,
      damageTaken: 0,
      turretDamage: 0,
      position: 'own_turret',
      inCombat: false,
      teamTotalKills: 0,
    };
  }

  protected onPhaseChange(newPhase: string): void {
    log.info(`[GameAdapter] ${this.gameInfo.name} 阶段变化: ${newPhase}`);
    if (this.config.enableVoice) {
      this.voiceService.speakPhaseChange(newPhase);
    }
    const phaseTasks = this.tasks.filter(task => task.phase === newPhase);
    for (const task of phaseTasks) {
      if (task.role === 'all' || task.role === this.config.selectedRole) {
        if (this.config.enableVoice) {
          this.voiceService.speakTask(task.description, 'normal');
        }
      }
    }
  }

  protected abstract initRegions(): void;
  protected abstract initScoringRules(): void;
  protected abstract initTasks(): void;

  setRole(role: Role): void {
    this.config.selectedRole = role;
    if (this.state) {
      this.state.role = role;
    }
    log.info(`[GameAdapter] ${this.gameInfo.name} 设置位置: ${role}`);
  }
}
