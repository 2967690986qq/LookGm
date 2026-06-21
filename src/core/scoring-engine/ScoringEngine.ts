import { EventEmitter } from 'events';
import log from 'loglevel';
import { GameState, Role } from '@types/game';
import { ScoreResult, TaskProgress, ScoringRule, TaskItem, ScoreBreakdown, GradeTaskItem, RoleScoringCriteria } from '@types/scoring';

export interface EngineConfig {
  maxScore: number;
  updateIntervalMs: number;
  enabled: boolean;
}

export interface RuleSet {
  gameId: string;
  rules: ScoringRule[];
  tasks: TaskItem[];
  maxScore: number;
  scoringCriteria?: Record<Role, RoleScoringCriteria>;
}

export class ScoringEngine extends EventEmitter {
  private static instance: ScoringEngine;
  private config: EngineConfig = {
    maxScore: 16.0,
    updateIntervalMs: 2000,
    enabled: true,
  };
  private ruleSets: Map<string, RuleSet> = new Map();
  private currentScore: ScoreResult | null = null;
  private taskProgressMap: Map<string, TaskProgress> = new Map();
  private lastUpdateTime: number = 0;

  private constructor() {
    super();
  }

  static getInstance(): ScoringEngine {
    if (!ScoringEngine.instance) {
      ScoringEngine.instance = new ScoringEngine();
    }
    return ScoringEngine.instance;
  }

  configure(config: Partial<EngineConfig>): void {
    this.config = { ...this.config, ...config };
  }

  registerRuleSet(ruleSet: RuleSet): void {
    this.ruleSets.set(ruleSet.gameId, ruleSet);
    this.config.maxScore = ruleSet.maxScore;
    log.info(`[ScoringEngine] 注册评分规则: ${ruleSet.gameId} (5维度 + 评分任务系统)`);
  }

  unregisterRuleSet(gameId: string): void {
    this.ruleSets.delete(gameId);
  }

  calculate(gameState: GameState): ScoreResult {
    const ruleSet = this.ruleSets.get(gameState.gameId);
    if (!ruleSet) {
      log.warn(`[ScoringEngine] 未找到游戏 ${gameState.gameId} 的评分规则`);
      return this.createEmptyResult();
    }
    const now = Date.now();
    if (now - this.lastUpdateTime < this.config.updateIntervalMs && this.currentScore) {
      return this.currentScore;
    }
    this.lastUpdateTime = now;

    const breakdown = this.computeBreakdown(ruleSet, gameState);
    const taskResults = this.computeTaskResults(ruleSet, gameState);
    const taskBonus = this.computeTaskBonus(taskResults.completed, ruleSet);
    const taskPenalty = this.computeTaskPenalty(taskResults.failed, ruleSet);
    const totalScore = this.computeTotalScore(breakdown, ruleSet, taskBonus, taskPenalty);
    const tasks = this.computeTaskProgress(ruleSet, gameState);
    const grade = this.computeGrade(totalScore, ruleSet.maxScore);
    const feedback = this.generateFeedback(breakdown, gameState, totalScore, ruleSet.maxScore, taskResults);

    this.currentScore = {
      totalScore,
      maxScore: ruleSet.maxScore,
      breakdown,
      tasks,
      timestamp: now,
      phase: gameState.phase,
      grade,
      feedback,
      completedTasks: taskResults.completed.map(t => t.description),
      failedTasks: taskResults.failed.map(t => t.description),
      positiveActions: taskResults.positiveActions,
      negativeActions: taskResults.negativeActions,
    };

    this.emit('score-updated', this.currentScore);
    return this.currentScore;
  }

  getCurrentScore(): ScoreResult | null {
    return this.currentScore;
  }

  reset(): void {
    this.currentScore = null;
    this.taskProgressMap.clear();
    this.lastUpdateTime = 0;
    this.emit('reset');
  }

  private computeBreakdown(ruleSet: RuleSet, state: GameState): ScoreBreakdown {
    const breakdown: ScoreBreakdown = {
      damage: 0,
      survival: 0,
      teamfight: 0,
      farm: 0,
      kda: 0,
    };
    for (const rule of ruleSet.rules) {
      const value = rule.calculate(state);
      const normalized = Math.min(value, rule.maxValue) / rule.maxValue;
      const categoryScore = normalized * rule.weight;
      switch (rule.category) {
        case 'damage':
          breakdown.damage += categoryScore;
          break;
        case 'survival':
          breakdown.survival += categoryScore;
          break;
        case 'teamfight':
          breakdown.teamfight += categoryScore;
          break;
        case 'farm':
          breakdown.farm += categoryScore;
          break;
        case 'kda':
          breakdown.kda += categoryScore;
          break;
      }
    }
    return breakdown;
  }

  private computeTaskResults(ruleSet: RuleSet, gameState: GameState) {
    const criteria = ruleSet.scoringCriteria?.[gameState.role || 'exp_lane'];
    if (!criteria) {
      return { completed: [], failed: [], positiveActions: [], negativeActions: [] };
    }

    const completed: GradeTaskItem[] = [];
    const failed: GradeTaskItem[] = [];
    const positiveActions: string[] = [];
    const negativeActions: string[] = [];

    for (const task of criteria.increaseTasks) {
      if (this.checkTaskCompletion(task, gameState, criteria)) {
        completed.push(task);
        positiveActions.push(`✅ ${task.description}: ${task.requirement}`);
      }
    }

    for (const task of criteria.decreaseTasks) {
      if (this.checkTaskFailed(task, gameState, criteria)) {
        failed.push(task);
        negativeActions.push(`❌ ${task.description}: ${task.requirement}`);
      }
    }

    return { completed, failed, positiveActions, negativeActions };
  }

  private checkTaskCompletion(task: GradeTaskItem, state: GameState, criteria: RoleScoringCriteria): boolean {
    const taskId = task.id.toLowerCase();

    if (taskId.includes('kill')) {
      const kills = state.kills || 0;
      const total = (state.kills || 0) + (state.assists || 0);
      if (taskId.includes('total') || taskId.includes('ka')) {
        return total >= 15;
      }
      return kills >= 3;
    }
    if (taskId.includes('death')) {
      return (state.deaths || 0) <= 3;
    }
    if (taskId.includes('assist')) {
      return (state.assists || 0) >= 5;
    }
    if (taskId.includes('turret')) {
      return (state.turretDamage || 0) > 0;
    }
    if (taskId.includes('teamfight') || taskId.includes('participation')) {
      const totalKills = Math.max(state.teamTotalKills || 0, 1);
      const participation = ((state.kills || 0) + (state.assists || 0)) / totalKills;
      return participation >= 0.5;
    }
    if (taskId.includes('damage')) {
      return (state.damageDealt || 0) > 10000;
    }
    if (taskId.includes('farm') || taskId.includes('gold')) {
      return (state.gold || 0) > 5000;
    }

    return false;
  }

  private checkTaskFailed(task: GradeTaskItem, state: GameState, criteria: RoleScoringCriteria): boolean {
    const taskId = task.id.toLowerCase();

    if (taskId.includes('death') && taskId.includes('penalty')) {
      return (state.deaths || 0) > 5;
    }
    if (taskId.includes('death') && taskId.includes('high')) {
      return (state.deaths || 0) > 7;
    }
    if (taskId.includes('kill') && taskId.includes('no')) {
      return (state.kills || 0) === 0;
    }
    if (taskId.includes('participation') || taskId.includes('low')) {
      const totalKills = Math.max(state.teamTotalKills || 0, 1);
      const participation = ((state.kills || 0) + (state.assists || 0)) / totalKills;
      return participation < 0.3;
    }
    if (taskId.includes('farm') || taskId.includes('low')) {
      return (state.gold || 0) < 3000;
    }

    return false;
  }

  private computeTaskBonus(completedTasks: GradeTaskItem[], ruleSet: RuleSet): number {
    let bonus = 0;
    for (const task of completedTasks) {
      if (task.critical) {
        bonus += task.scoreBonus * task.weight;
      } else {
        bonus += task.scoreBonus;
      }
    }
    return bonus;
  }

  private computeTaskPenalty(failedTasks: GradeTaskItem[], ruleSet: RuleSet): number {
    let penalty = 0;
    for (const task of failedTasks) {
      if (task.critical) {
        penalty += Math.abs(task.scoreBonus) * task.weight;
      } else {
        penalty += Math.abs(task.scoreBonus);
      }
    }
    return penalty;
  }

  private computeTotalScore(
    breakdown: ScoreBreakdown,
    ruleSet: RuleSet,
    taskBonus: number,
    taskPenalty: number
  ): number {
    const totalWeight = ruleSet.rules.reduce((sum, r) => sum + r.weight, 0);
    const sum =
      breakdown.damage +
      breakdown.survival +
      breakdown.teamfight +
      breakdown.farm +
      breakdown.kda;
    const raw = sum / totalWeight;
    const baseScore = raw * this.config.maxScore;
    const withTasks = baseScore + taskBonus - taskPenalty;
    const scaled = Math.round(withTasks * 10) / 10;
    return Math.max(0, Math.min(this.config.maxScore, scaled));
  }

  private computeTaskProgress(ruleSet: RuleSet, state: GameState): TaskProgress[] {
    const phase = state.phase;
    return ruleSet.tasks
      .filter(task => task.role === state.role || task.role === 'all')
      .filter(task => {
        if (task.phase === 'battle') return state.inCombat;
        return phase === task.phase ||
          (task.phase === 'early_game' && (phase === 'mid_game' || phase === 'late_game'));
      })
      .map(task => {
        const key = `${state.gameId}_${task.id}`;
        const existing = this.taskProgressMap.get(key);
        const progress = existing || {
          taskId: task.id,
          currentScore: 0,
          maxScore: task.maxScore,
          completed: false,
          timeRemaining: task.duration,
        };
        if (!existing) {
          this.taskProgressMap.set(key, progress);
          this.emit('task-started', { taskId: task.id, description: task.description });
        }
        return progress;
      });
  }

  private computeGrade(score: number, maxScore: number): string {
    const ratio = score / maxScore;
    if (ratio >= 0.875) return '顶级评分';
    if (ratio >= 0.75) return '金牌评分';
    if (ratio >= 0.56) return '银牌评分';
    if (ratio >= 0.44) return '铜牌评分';
    return '暂无评级';
  }

  private generateFeedback(
    breakdown: ScoreBreakdown,
    state: GameState,
    totalScore: number,
    maxScore: number,
    taskResults: { completed: GradeTaskItem[]; failed: GradeTaskItem[] }
  ): string[] {
    const feedback: string[] = [];
    const avg = maxScore / 2;

    if (totalScore < avg * 0.7) {
      feedback.push('当前表现较弱，注意加强团队配合');
    } else if (totalScore >= avg) {
      feedback.push('表现良好，继续保持');
    }

    if (breakdown.survival < 0.4) {
      feedback.push('注意减少死亡次数');
    } else if (breakdown.survival > 0.8) {
      feedback.push('存活表现优秀');
    }

    if (breakdown.kda < 0.4) {
      feedback.push('可以更加积极参团，提升击杀和助攻');
    }

    if (breakdown.teamfight < 0.4) {
      feedback.push('参团率偏低，建议多跟随队友');
    }

    if (state.healthPercent < 30 && state.isAlive) {
      feedback.push('血量较低，请注意撤退');
    }

    if (taskResults.failed.length > 0) {
      const criticalFailed = taskResults.failed.filter(t => t.critical);
      if (criticalFailed.length > 0) {
        feedback.push(`❌ 关键指标未达标: ${criticalFailed[0].description}`);
      }
    }

    if (totalScore >= maxScore * 0.75) {
      feedback.push('当前可达金牌评分，继续努力可冲击顶级评分！');
    } else if (totalScore >= maxScore * 0.56) {
      feedback.push('当前可达银牌评分，再加强一下可冲击金牌评分');
    } else if (totalScore >= maxScore * 0.44) {
      feedback.push('当前可达铜牌评分，继续努力可提升至银牌评分');
    }

    return feedback;
  }

  private createEmptyResult(): ScoreResult {
    return {
      totalScore: 0,
      maxScore: this.config.maxScore,
      breakdown: { damage: 0, survival: 0, teamfight: 0, farm: 0, kda: 0 },
      tasks: [],
      timestamp: Date.now(),
      phase: 'idle',
      grade: '-',
      feedback: [],
      completedTasks: [],
      failedTasks: [],
      positiveActions: [],
      negativeActions: [],
    };
  }
}
