export type GameId = string;

export type Platform = 'android' | 'ios' | 'windows' | 'macos';

export type GamePhase =
  | 'loading'
  | 'hero_select'
  | 'early_game'
  | 'mid_game'
  | 'late_game'
  | 'battle'
  | 'idle';

export type Role =
  | 'exp_lane'
  | 'jungle'
  | 'mid_lane'
  | 'roam'
  | 'gold_lane';

export interface GameInfo {
  id: GameId;
  name: string;
  displayName: string;
  packageName: string;
  supportedPlatforms: Platform[];
  version: string;
  roles: Role[];
  maxScore: number;
}

export interface GameState {
  gameId: GameId;
  phase: GamePhase;
  role: Role | null;
  hero: string | null;
  kills: number;
  deaths: number;
  assists: number;
  matchTime: number;
  isAlive: boolean;
  healthPercent: number;
  manaPercent: number;
  gold: number;
  level: number;
  damageDealt: number;
  damageTaken: number;
  turretDamage: number;
  position: 'own_turret' | 'mid_lane' | 'enemy_turret' | 'jungle' | 'unknown';
  inCombat: boolean;
  teamTotalKills: number;
}

export interface ScreenRegion {
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
  description: string;
}

export interface DetectedElement {
  type: string;
  value: string | number;
  confidence: number;
  region: ScreenRegion;
}

export interface AnalyzedFrame {
  timestamp: number;
  gameState: GameState;
  detectedElements: DetectedElement[];
  rawImage?: string;
}
