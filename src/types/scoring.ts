export type GamePhase =
  | 'loading'
  | 'hero_select'
  | 'early_game'
  | 'mid_game'
  | 'late_game'
  | 'battle'
  | 'idle';

export interface TaskItem {
  id: string;
  description: string;
  phase: 'early_game' | 'mid_game' | 'late_game' | 'battle';
  role: string;
  weight: number;
  maxScore: number;
  duration: number;
}

export interface TaskProgress {
  taskId: string;
  currentScore: number;
  maxScore: number;
  completed: boolean;
  timeRemaining: number;
}

export interface ScoringRule {
  id: string;
  description: string;
  category: 'damage' | 'survival' | 'teamfight' | 'farm' | 'kda';
  weight: number;
  maxValue: number;
  calculate: (state: any) => number;
}

export interface ScoreBreakdown {
  damage: number;
  survival: number;
  teamfight: number;
  farm: number;
  kda: number;
}

export interface ScoreResult {
  totalScore: number;
  maxScore: number;
  breakdown: ScoreBreakdown;
  tasks: TaskProgress[];
  timestamp: number;
  phase: string;
  grade: string;
  feedback: string[];
  completedTasks: string[];
  failedTasks: string[];
  positiveActions: string[];
  negativeActions: string[];
}

export interface HistoricalScore {
  matchId: string;
  gameId: string;
  date: string;
  score: number;
  role: string;
  hero: string;
  duration: number;
  result: 'win' | 'lose' | 'unknown';
}

// ========== 评分任务系统 ==========

export type GradeType = 'top' | 'gold' | 'silver' | 'bronze' | 'none';

export type ActionEffect = 'increase' | 'decrease' | 'neutral';

export interface ScoringAction {
  id: string;
  description: string;
  effect: ActionEffect;
  scoreImpact: number;
  dimension: 'damage' | 'survival' | 'teamfight' | 'farm' | 'kda';
  reason: string;
}

export interface GradeRequirement {
  grade: GradeType;
  minScore: number;
  maxScore: number;
  emoji: string;
  label: string;
}

export interface RoleScoringTask {
  role: string;
  grade: GradeType;
  tasks: GradeTaskItem[];
}

export interface GradeTaskItem {
  id: string;
  description: string;
  requirement: string;
  type: 'increase' | 'decrease' | 'maintain';
  weight: number;
  scoreBonus: number;
  critical: boolean;
}

export interface RoleScoringCriteria {
  role: string;
  gradeRequirements: {
    bronze: GradeRequirement;
    silver: GradeRequirement;
    gold: GradeRequirement;
    top: GradeRequirement;
  };
  increaseTasks: GradeTaskItem[];
  decreaseTasks: GradeTaskItem[];
  dimensionWeights: {
    dimension: string;
    weight: number;
    bronze: number;
    silver: number;
    gold: number;
    top: number;
  }[];
}
