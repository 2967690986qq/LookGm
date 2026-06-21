import log from 'loglevel';
import { GameAdapter } from '../base/GameAdapter';
import { RegionTemplate } from '../../core/ai-analysis/AIAnalysisService';
import { ScoringRule, TaskItem } from '@types/scoring';
import { Role } from '@types/game';
import { KOG_SCORING_CRITERIA } from './ScoringCriteria';

const KOG_ROLES: Role[] = ['exp_lane', 'jungle', 'mid_lane', 'roam', 'gold_lane'];

const KOG_TASKS: Record<Role, TaskItem[]> = {
  exp_lane: [
    { id: 'exp_early_1', description: '对抗路: 稳定发育，优先清理兵线', phase: 'early_game', role: 'exp_lane', weight: 2, maxScore: 1, duration: 180 },
    { id: 'exp_early_2', description: '对抗路: 注意对方打野位置，避免被gank', phase: 'early_game', role: 'exp_lane', weight: 1.5, maxScore: 1, duration: 180 },
    { id: 'exp_early_3', description: '对抗路: 争取完成单杀，压制对线', phase: 'early_game', role: 'exp_lane', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'exp_mid_1', description: '对抗路: 中期积极参团，协助队友推进', phase: 'mid_game', role: 'exp_lane', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'exp_mid_2', description: '对抗路: 利用兵线优势牵制对方', phase: 'mid_game', role: 'exp_lane', weight: 2, maxScore: 1, duration: 300 },
    { id: 'exp_mid_3', description: '对抗路: 推塔贡献，争取推塔数≥2座', phase: 'mid_game', role: 'exp_lane', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'exp_late_1', description: '对抗路: 后期切入后排，限制对方输出', phase: 'late_game', role: 'exp_lane', weight: 3, maxScore: 1, duration: 600 },
    { id: 'exp_battle_1', description: '对抗路: 战斗中优先攻击脆皮，注意拉扯', phase: 'battle', role: 'exp_lane', weight: 2.5, maxScore: 1, duration: 20 },
  ],
  jungle: [
    { id: 'jungle_early_1', description: '打野: 快速刷野，注意时间点', phase: 'early_game', role: 'jungle', weight: 3, maxScore: 1, duration: 120 },
    { id: 'jungle_early_2', description: '打野: 积极寻找gank机会，帮助队友建立优势', phase: 'early_game', role: 'jungle', weight: 3, maxScore: 1, duration: 300 },
    { id: 'jungle_early_3', description: '打野: 控龙，争取击杀龙≥2条', phase: 'early_game', role: 'jungle', weight: 3, maxScore: 1, duration: 300 },
    { id: 'jungle_mid_1', description: '打野: 中期控龙，扩大团队经济优势', phase: 'mid_game', role: 'jungle', weight: 3, maxScore: 1, duration: 300 },
    { id: 'jungle_mid_2', description: '打野: 团战中优先击杀对方核心输出', phase: 'mid_game', role: 'jungle', weight: 3, maxScore: 1, duration: 300 },
    { id: 'jungle_mid_3', description: '打野: 反野，争取反野次数≥3次', phase: 'mid_game', role: 'jungle', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'jungle_late_1', description: '打野: 后期精准切入，保护后排', phase: 'late_game', role: 'jungle', weight: 3.5, maxScore: 1, duration: 600 },
    { id: 'jungle_battle_1', description: '打野: 战斗中看准时机切入，切勿硬拼', phase: 'battle', role: 'jungle', weight: 3, maxScore: 1, duration: 20 },
  ],
  mid_lane: [
    { id: 'mid_early_1', description: '中路: 快速清线，及时支援队友', phase: 'early_game', role: 'mid_lane', weight: 3, maxScore: 1, duration: 180 },
    { id: 'mid_early_2', description: '中路: 注意河道视野，防止被gank', phase: 'early_game', role: 'mid_lane', weight: 2, maxScore: 1, duration: 180 },
    { id: 'mid_early_3', description: '中路: 争取支援成功≥5次', phase: 'early_game', role: 'mid_lane', weight: 3, maxScore: 1, duration: 300 },
    { id: 'mid_mid_1', description: '中路: 中期团战中优先消耗对方前排', phase: 'mid_game', role: 'mid_lane', weight: 3, maxScore: 1, duration: 300 },
    { id: 'mid_mid_2', description: '中路: 保持站位，避免被刺客切入', phase: 'mid_game', role: 'mid_lane', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'mid_mid_3', description: '中路: 输出占比争取≥30%', phase: 'mid_game', role: 'mid_lane', weight: 3, maxScore: 1, duration: 300 },
    { id: 'mid_late_1', description: '中路: 后期确保团战爆发输出', phase: 'late_game', role: 'mid_lane', weight: 3.5, maxScore: 1, duration: 600 },
    { id: 'mid_battle_1', description: '中路: 战斗中注意位置，防止被刺客切入', phase: 'battle', role: 'mid_lane', weight: 3, maxScore: 1, duration: 20 },
  ],
  roam: [
    { id: 'roam_early_1', description: '游走: 开局跟打野或射手，提供视野与保护', phase: 'early_game', role: 'roam', weight: 2.5, maxScore: 1, duration: 180 },
    { id: 'roam_early_2', description: '游走: 在关键草丛处放置视野', phase: 'early_game', role: 'roam', weight: 2.5, maxScore: 1, duration: 180 },
    { id: 'roam_early_3', description: '游走: 争取视野得分≥35，控制时长≥25秒', phase: 'early_game', role: 'roam', weight: 3, maxScore: 1, duration: 300 },
    { id: 'roam_mid_1', description: '游走: 中期团战中优先保护核心输出', phase: 'mid_game', role: 'roam', weight: 3, maxScore: 1, duration: 300 },
    { id: 'roam_mid_2', description: '游走: 积极提供控制，配合队友击杀', phase: 'mid_game', role: 'roam', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'roam_mid_3', description: '游走: 争取参团率≥75%', phase: 'mid_game', role: 'roam', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'roam_late_1', description: '游走: 后期提供持续的保护和控制', phase: 'late_game', role: 'roam', weight: 3, maxScore: 1, duration: 600 },
    { id: 'roam_battle_1', description: '游走: 战斗中及时释放技能保护队友', phase: 'battle', role: 'roam', weight: 3, maxScore: 1, duration: 20 },
  ],
  gold_lane: [
    { id: 'gold_early_1', description: '发育路: 专注发育，保证经济领先', phase: 'early_game', role: 'gold_lane', weight: 3, maxScore: 1, duration: 300 },
    { id: 'gold_early_2', description: '发育路: 避免被gank，注意视野位置', phase: 'early_game', role: 'gold_lane', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'gold_early_3', description: '发育路: 争取输出占比≥25%', phase: 'early_game', role: 'gold_lane', weight: 2.5, maxScore: 1, duration: 300 },
    { id: 'gold_mid_1', description: '发育路: 中期稳步输出，保持距离', phase: 'mid_game', role: 'gold_lane', weight: 3, maxScore: 1, duration: 300 },
    { id: 'gold_mid_2', description: '发育路: 推塔优先，快速扩大优势', phase: 'mid_game', role: 'gold_lane', weight: 3, maxScore: 1, duration: 300 },
    { id: 'gold_mid_3', description: '发育路: 争取推塔数≥3座', phase: 'mid_game', role: 'gold_lane', weight: 3, maxScore: 1, duration: 300 },
    { id: 'gold_late_1', description: '发育路: 后期团战中持续输出，注意站位', phase: 'late_game', role: 'gold_lane', weight: 3.5, maxScore: 1, duration: 600 },
    { id: 'gold_battle_1', description: '发育路: 战斗中优先攻击最近的敌人，保持输出距离', phase: 'battle', role: 'gold_lane', weight: 3, maxScore: 1, duration: 20 },
  ],
};

const DEFAULT_KOG_RULES: ScoringRule[] = [
  {
    id: 'kog_damage_score',
    description: '输出',
    category: 'damage',
    weight: 2.5,
    maxValue: 50000,
    calculate: (state: any) => Math.min((state.damageDealt || 0) / 3000, 16.7),
  },
  {
    id: 'kog_survival_score',
    description: '生存',
    category: 'survival',
    weight: 2.0,
    maxValue: 10,
    calculate: (state: any) => Math.max(0, 10 - (state.deaths || 0) * 1.5),
  },
  {
    id: 'kog_teamfight_score',
    description: '团战(参团率)',
    category: 'teamfight',
    weight: 2.0,
    maxValue: 10,
    calculate: (state: any) => {
      const totalKills = Math.max(state.teamTotalKills || 0, 1);
      const participation = ((state.kills || 0) + (state.assists || 0)) / totalKills;
      return Math.min(participation * 10, 10);
    },
  },
  {
    id: 'kog_farm_score',
    description: '发育',
    category: 'farm',
    weight: 2.0,
    maxValue: 15000,
    calculate: (state: any) => Math.min((state.gold || 0) / 1000, 10),
  },
  {
    id: 'kog_kda_score',
    description: 'KDA',
    category: 'kda',
    weight: 2.5,
    maxValue: 10,
    calculate: (state: any) => {
      const k = state.kills || 0;
      const d = state.deaths || 0;
      const a = state.assists || 0;
      if (d === 0) return Math.min((k + a) * 1.2, 10);
      return Math.min((k + a) / d, 10);
    },
  },
];

const KOG_REGIONS: RegionTemplate[] = [
  { name: 'kog_health_bar', x: 10, y: 1000, width: 300, height: 30, expectedColor: { r: 200, g: 50, b: 50, tolerance: 30 } },
  { name: 'kog_mana_bar', x: 10, y: 1040, width: 300, height: 20, expectedColor: { r: 50, g: 100, b: 200, tolerance: 30 } },
  { name: 'kog_kills', x: 280, y: 50, width: 60, height: 40, containsText: '击杀' },
  { name: 'kog_deaths', x: 340, y: 50, width: 60, height: 40, containsText: '死亡' },
  { name: 'kog_assists', x: 400, y: 50, width: 60, height: 40, containsText: '助攻' },
  { name: 'kog_gold', x: 500, y: 1000, width: 100, height: 30, containsText: '金币' },
  { name: 'kog_level', x: 50, y: 960, width: 40, height: 40, containsText: '' },
  { name: 'kog_minimap', x: 0, y: 0, width: 250, height: 250, description: '小地图' },
  { name: 'kog_team_score', x: 300, y: 60, width: 200, height: 40, containsText: 'VS' },
  { name: 'kog_time', x: 350, y: 30, width: 80, height: 30, containsText: ':' },
  { name: 'kog_turret_damage', x: 600, y: 900, width: 150, height: 30, description: '推塔伤害' },
  { name: 'kog_damage_dealt', x: 700, y: 1000, width: 150, height: 30, description: '输出伤害' },
  { name: 'kog_damage_taken', x: 700, y: 1050, width: 150, height: 30, description: '承受伤害' },
];

export class KingOfGloryAdapter extends GameAdapter {
  private static INSTANCE: KingOfGloryAdapter | null = null;

  private constructor() {
    super({
      id: 'king-of-glory',
      name: 'king-of-glory',
      displayName: '王者荣耀',
      packageName: 'com.tencent.tmgp.sgame',
      supportedPlatforms: ['android', 'ios'],
      version: '1.1.0',
      roles: KOG_ROLES,
      maxScore: 16.0,
    });
    this.initRegions();
    this.initScoringRules();
    this.initTasks();
  }

  static getInstance(): KingOfGloryAdapter {
    if (!KingOfGloryAdapter.INSTANCE) {
      KingOfGloryAdapter.INSTANCE = new KingOfGloryAdapter();
    }
    return KingOfGloryAdapter.INSTANCE;
  }

  protected initRegions(): void {
    this.regions = KOG_REGIONS;
    log.info('[KingOfGlory] 初始化屏幕识别区域 (含推塔伤害/输出伤害/承受伤害)');
  }

  protected initScoringRules(): void {
    this.scoringRules = DEFAULT_KOG_RULES;
    log.info('[KingOfGlory] 初始化5维度评分规则 (输出/生存/团战/发育/KDA)');
  }

  protected initTasks(): void {
    this.tasks = [];
    for (const role of KOG_ROLES) {
      const roleTasks = KOG_TASKS[role] || [];
      this.tasks.push(...roleTasks);
    }
    log.info('[KingOfGlory] 初始化5分路任务列表 (含各等级评分任务)');
  }

  getRoleTasks(role: Role): TaskItem[] {
    return KOG_TASKS[role] || [];
  }

  getScoringCriteria(role: Role) {
    return KOG_SCORING_CRITERIA[role];
  }
}
