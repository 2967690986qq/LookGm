export interface VoiceMessage {
  id: string;
  text: string;
  priority: 'low' | 'normal' | 'high' | 'critical';
  type: 'task' | 'warning' | 'info' | 'score_update' | 'phase_change';
  timestamp: number;
  cooldown: number;
}

export interface VoiceConfig {
  enabled: boolean;
  volume: number;
  rate: number;
  pitch: number;
  language: string;
  cooldownMs: number;
  silentMode: boolean;
}
