import { EventEmitter } from 'events';
import log from 'loglevel';
import storage from '../../utils/Storage';

export type AIProvider =
  | 'deepseek'
  | 'qwen'
  | 'gpt'
  | 'claude'
  | 'gemini'
  | 'zhipu'
  | 'ollama'
  | 'custom'
  | 'none';

export interface AIProviderConfig {
  provider: AIProvider;
  name: string;
  apiKey: string;
  baseUrl: string;
  model: string;
  apiVersion?: string;
  enabled: boolean;
  maxTokens?: number;
  temperature?: number;
  systemPrompt?: string;
  description?: string;
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp?: number;
}

export interface ChatRequest {
  messages: ChatMessage[];
  provider?: AIProvider;
  model?: string;
  maxTokens?: number;
  temperature?: number;
  systemPrompt?: string;
  stream?: boolean;
}

export interface ChatResponse {
  success: boolean;
  content?: string;
  provider?: AIProvider;
  model?: string;
  error?: string;
  responseTime?: number;
  tokensUsed?: number;
}

// 提供商元数据（提供给UI展示，明亮简约的配色）
export const PROVIDER_META: Record<AIProvider, { name: string; icon: string; description: string; color: string }> = {
  deepseek: {
    name: 'DeepSeek',
    icon: '🐋',
    description: '国内优秀开源大模型，支持对话和推理',
    color: '#4F46E5',
  },
  qwen: {
    name: '通义千问',
    icon: '💡',
    description: '阿里旗下大模型，中文表现优秀',
    color: '#6366F1',
  },
  gpt: {
    name: 'OpenAI GPT',
    icon: '🧠',
    description: 'GPT系列大模型（需科学上网）',
    color: '#10B981',
  },
  claude: {
    name: 'Claude',
    icon: '📚',
    description: 'Anthropic旗下模型，长文本处理强',
    color: '#F59E0B',
  },
  gemini: {
    name: 'Google Gemini',
    icon: '✨',
    description: 'Google多模态大模型',
    color: '#3B82F6',
  },
  zhipu: {
    name: '智谱 AI',
    icon: '🎯',
    description: 'GLM系列模型，国内免费额度',
    color: '#EF4444',
  },
  ollama: {
    name: 'Ollama',
    icon: '🦙',
    description: '本地部署开源大模型，完全离线',
    color: '#10B981',
  },
  custom: {
    name: '自定义',
    icon: '⚙️',
    description: '自定义API配置，兼容OpenAI格式',
    color: '#6B7280',
  },
  none: {
    name: '未配置',
    icon: '📭',
    description: '请选择AI服务商并配置API Key',
    color: '#9CA3AF',
  },
};

const DEFAULT_PROVIDER_CONFIGS: Record<AIProvider, Omit<AIProviderConfig, 'apiKey' | 'enabled'>> = {
  deepseek: {
    provider: 'deepseek',
    name: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1/chat/completions',
    model: 'deepseek-chat',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: '你是一个专业的游戏分析助手，擅长帮助玩家提高游戏水平。请用简洁的中文回答。',
    description: '国内优秀开源大模型，支持对话和推理',
  },
  qwen: {
    provider: 'qwen',
    name: '通义千问',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
    model: 'qwen-plus',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: '你是一个专业的游戏分析助手，擅长帮助玩家提高游戏水平。请用简洁的中文回答。',
    description: '阿里旗下大模型，中文表现优秀',
  },
  gpt: {
    provider: 'gpt',
    name: 'OpenAI GPT',
    baseUrl: 'https://api.openai.com/v1/chat/completions',
    model: 'gpt-4o-mini',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: 'You are a professional gaming analysis assistant. Respond in Chinese. Be concise and helpful.',
    description: 'GPT系列大模型（需科学上网）',
  },
  claude: {
    provider: 'claude',
    name: 'Claude',
    baseUrl: 'https://api.anthropic.com/v1/messages',
    model: 'claude-3-haiku-20240307',
    apiVersion: '2023-06-01',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: '你是一个专业的游戏分析助手，擅长帮助玩家提高游戏水平。请用简洁的中文回答。',
    description: 'Anthropic旗下模型，长文本处理强',
  },
  gemini: {
    provider: 'gemini',
    name: 'Google Gemini',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta',
    model: 'gemini-1.5-flash',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: 'You are a professional gaming analysis assistant. Respond in Chinese.',
    description: 'Google多模态大模型',
  },
  zhipu: {
    provider: 'zhipu',
    name: '智谱 AI',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
    model: 'glm-4.5-flash',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: '你是一个专业的游戏分析助手，擅长帮助玩家提高游戏水平。请用简洁的中文回答。',
    description: 'GLM系列模型，国内免费额度',
  },
  ollama: {
    provider: 'ollama',
    name: 'Ollama',
    baseUrl: 'http://localhost:11434/v1/chat/completions',
    model: 'llama2',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: '你是一个专业的游戏分析助手，擅长帮助玩家提高游戏水平。请用简洁的中文回答。',
    description: '本地部署开源大模型，完全离线',
  },
  custom: {
    provider: 'custom',
    name: '自定义',
    baseUrl: 'https://your-api-endpoint.com/v1/chat/completions',
    model: 'custom-model',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: '你是一个专业的游戏分析助手。',
    description: '自定义API配置，兼容OpenAI格式',
  },
  none: {
    provider: 'none',
    name: '未配置',
    baseUrl: '',
    model: '',
    maxTokens: 4096,
    temperature: 0.7,
    systemPrompt: '',
    description: '请选择AI服务商并配置API Key',
  },
};

export const AVAILABLE_PROVIDERS: AIProvider[] = [
  'zhipu', 'deepseek', 'qwen', 'ollama', 'gpt', 'claude', 'gemini', 'custom',
];

// 每个提供商的可选模型（供UI切换）
export const PROVIDER_MODELS: Record<AIProvider, string[]> = {
  deepseek: ['deepseek-chat', 'deepseek-reasoner'],
  qwen: ['qwen-turbo', 'qwen-plus', 'qwen-max', 'qwen-long'],
  gpt: ['gpt-4o-mini', 'gpt-4o', 'gpt-3.5-turbo'],
  claude: ['claude-3-haiku-20240307', 'claude-3-sonnet-20240229', 'claude-3-opus-20240229'],
  gemini: ['gemini-1.5-flash', 'gemini-1.5-pro', 'gemini-1.0-pro'],
  zhipu: ['glm-4.5-flash', 'glm-4-flash', 'glm-4', 'glm-4-plus', 'glm-4-air', 'glm-4-airx', 'glm-3-turbo'],
  ollama: ['llama2', 'llama3', 'qwen2.5', 'mistral', 'gemma2', 'codellama'],
  custom: ['custom-model'],
  none: [],
};

// 游戏分路定义
const GAME_ROLES = [
  { id: 'exp_lane', name: '对抗路', hint: '单人线，英雄对打' },
  { id: 'jungle', name: '打野', hint: '在野区活动，帮助队友' },
  { id: 'mid_lane', name: '中路', hint: '中间通道，法师常见' },
  { id: 'roam', name: '游走', hint: '辅助角色，保护队友' },
  { id: 'gold_lane', name: '发育路', hint: '射手位置，稳定发育' },
] as const;

export class AIApiService extends EventEmitter {
  private static instance: AIApiService;
  private providerConfigs: Map<AIProvider, AIProviderConfig> = new Map();
  private currentProvider: AIProvider = 'zhipu';
  private chatHistory: ChatMessage[] = [];
  private requestCount: number = 0;
  private totalTokens: number = 0;
  private lastRequestTime: number = 0;

  private constructor() {
    super();
    this.initializeDefaults();
    this.loadFromStorage();
  }

  static getInstance(): AIApiService {
    if (!AIApiService.instance) {
      AIApiService.instance = new AIApiService();
    }
    return AIApiService.instance;
  }

  private initializeDefaults(): void {
    for (const provider of AVAILABLE_PROVIDERS) {
      const base = DEFAULT_PROVIDER_CONFIGS[provider];
      this.providerConfigs.set(provider, {
        ...base,
        apiKey: '',
        enabled: false,
      });
    }
  }

  private async saveToStorage(key: string, value: any): Promise<void> {
    try {
      await storage.setJson(`lookgm_ai_${key}`, value);
    } catch (err) {
      log.error('[AI] 保存配置失败:', err);
    }
  }

  private async loadFromStorage(): Promise<void> {
    try {
      const savedConfigs = await storage.getItem('lookgm_ai_configs');
      if (savedConfigs) {
        const parsed = JSON.parse(savedConfigs) as AIProviderConfig[];
        for (const cfg of parsed) {
          this.providerConfigs.set(cfg.provider, cfg);
        }
      }
      const currentProvider = await storage.getItem('lookgm_ai_current');
      if (currentProvider) {
        this.currentProvider = currentProvider as AIProvider;
      }
      const history = await storage.getItem('lookgm_ai_history');
      if (history) {
        this.chatHistory = JSON.parse(history);
      }
      const stats = await storage.getItem('lookgm_ai_stats');
      if (stats) {
        const parsed = JSON.parse(stats);
        this.requestCount = parsed.requestCount || 0;
        this.totalTokens = parsed.totalTokens || 0;
      }
    } catch (err) {
      log.error('[AI] 加载配置失败:', err);
    }
  }

  private async saveAll(): Promise<void> {
    const configs = Array.from(this.providerConfigs.values());
    await this.saveToStorage('configs', configs);
    await this.saveToStorage('current', this.currentProvider);
    await this.saveToStorage('history', this.chatHistory.slice(-50));
    await this.saveToStorage('stats', {
      requestCount: this.requestCount,
      totalTokens: this.totalTokens,
    });
  }

  configureProvider(provider: AIProvider, config: Partial<AIProviderConfig>): boolean {
    const existing = this.providerConfigs.get(provider);
    if (!existing) {
      log.warn(`[AI] Provider ${provider} 不存在`);
      return false;
    }
    const updated: AIProviderConfig = { ...existing, ...config, provider };
    // 如果提供了API Key，自动启用
    if (config.apiKey && config.apiKey.trim().length > 0) {
      updated.enabled = true;
    }
    this.providerConfigs.set(provider, updated);
    this.saveAll();
    this.emit('provider-updated', { provider, config: updated });
    log.info(`[AI] Provider ${provider} 已更新`);
    return true;
  }

  setApiKey(provider: AIProvider, apiKey: string): boolean {
    return this.configureProvider(provider, { apiKey, enabled: apiKey.trim().length > 0 });
  }

  setModel(provider: AIProvider, model: string): boolean {
    return this.configureProvider(provider, { model });
  }

  setBaseUrl(provider: AIProvider, baseUrl: string): boolean {
    return this.configureProvider(provider, { baseUrl });
  }

  setCurrentProvider(provider: AIProvider): void {
    this.currentProvider = provider;
    this.saveAll();
    this.emit('provider-changed', provider);
    log.info(`[AI] 当前 Provider 切换为: ${provider}`);
  }

  getCurrentProvider(): AIProvider {
    return this.currentProvider;
  }

  getProviderConfig(provider: AIProvider): AIProviderConfig | null {
    return this.providerConfigs.get(provider) || null;
  }

  getCurrentConfig(): AIProviderConfig | null {
    return this.providerConfigs.get(this.currentProvider) || null;
  }

  getAllProviderConfigs(): AIProviderConfig[] {
    return Array.from(this.providerConfigs.values());
  }

  isConfigured(): boolean {
    const cfg = this.providerConfigs.get(this.currentProvider);
    return !!(cfg && cfg.apiKey && cfg.baseUrl && cfg.enabled);
  }

  isProviderConfigured(provider: AIProvider): boolean {
    const cfg = this.providerConfigs.get(provider);
    return !!(cfg && cfg.apiKey && cfg.baseUrl);
  }

  clearChatHistory(): void {
    this.chatHistory = [];
    this.saveAll();
    this.emit('history-cleared');
  }

  getChatHistory(): ChatMessage[] {
    return [...this.chatHistory];
  }

  getStats(): { requestCount: number; totalTokens: number; lastRequestTime: number } {
    return {
      requestCount: this.requestCount,
      totalTokens: this.totalTokens,
      lastRequestTime: this.lastRequestTime,
    };
  }

  // ========== 核心聊天功能 ==========
  async chat(message: string, useHistory: boolean = true): Promise<ChatResponse> {
    const cfg = this.providerConfigs.get(this.currentProvider);
    if (!cfg || !cfg.apiKey || !cfg.apiKey.trim()) {
      return { success: false, error: '请先配置 AI API Key' };
    }
    if (!cfg.enabled) {
      return { success: false, error: '当前 Provider 未启用' };
    }

    const startTime = Date.now();
    const messages: ChatMessage[] = [];

    if (cfg.systemPrompt) {
      messages.push({ role: 'system', content: cfg.systemPrompt });
    }

    if (useHistory && this.chatHistory.length > 0) {
      messages.push(...this.chatHistory.slice(-10));
    }

    messages.push({ role: 'user', content: message, timestamp: Date.now() });

    try {
      const response = await this.makeRequest({
        messages,
        provider: this.currentProvider,
        model: cfg.model,
        maxTokens: cfg.maxTokens,
        temperature: cfg.temperature,
      }, cfg);

      this.lastRequestTime = Date.now();
      this.requestCount++;
      if (response.tokensUsed) {
        this.totalTokens += response.tokensUsed;
      }

      if (response.success && response.content) {
        this.chatHistory.push({ role: 'user', content: message, timestamp: Date.now() });
        this.chatHistory.push({ role: 'assistant', content: response.content, timestamp: Date.now() });
        this.saveAll();
        this.emit('chat-completed', {
          message,
          response: response.content,
          responseTime: Date.now() - startTime,
        });
      }

      return response;
    } catch (err: any) {
      log.error('[AI] 聊天请求失败:', err);
      return {
        success: false,
        error: err.message || '未知错误',
        provider: this.currentProvider,
        responseTime: Date.now() - startTime,
      };
    }
  }

  private async makeRequest(request: ChatRequest, config: AIProviderConfig): Promise<ChatResponse> {
    const startTime = Date.now();

    try {
      switch (request.provider) {
        case 'deepseek':
        case 'gpt':
        case 'zhipu':
        case 'custom':
        case 'ollama':
        case 'qwen':
          return this.makeOpenAIRequest(request, config);
        case 'claude':
          return this.makeClaudeRequest(request, config);
        case 'gemini':
          return this.makeGeminiRequest(request, config);
        default:
          return this.makeOpenAIRequest(request, config);
      }
    } catch (err: any) {
      log.error(`[AI] Request 失败 (${request.provider}):`, err);
      return {
        success: false,
        error: `请求失败: ${err.message || '网络错误'}`,
        provider: request.provider,
        responseTime: Date.now() - startTime,
      };
    }
  }

  private async makeOpenAIRequest(request: ChatRequest, config: AIProviderConfig): Promise<ChatResponse> {
    const startTime = Date.now();
    try {
      const body: Record<string, any> = {
        model: config.model,
        messages: request.messages.map(m => ({ role: m.role, content: m.content })),
        max_tokens: request.maxTokens || config.maxTokens || 2000,
        temperature: typeof request.temperature === 'number' ? request.temperature : (config.temperature ?? 0.7),
      };

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.apiKey}`,
      };

      // 智谱API和其他API使用标准的Bearer认证
      const url = config.baseUrl;

      const response = await this.fetchWrapper(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        timeout: 30000,
      });

      if (!response.ok) {
        let errorText = '';
        try {
          errorText = await response.text();
        } catch {
          errorText = `HTTP ${response.status}`;
        }
        return {
          success: false,
          error: `HTTP ${response.status}: ${errorText.slice(0, 200)}`,
          provider: config.provider,
          responseTime: Date.now() - startTime,
        };
      }

      const data = await response.json();
      const content = data.choices?.[0]?.message?.content || '';
      const tokensUsed = data.usage?.total_tokens || 0;

      return {
        success: true,
        content,
        provider: config.provider,
        model: config.model,
        responseTime: Date.now() - startTime,
        tokensUsed,
      };
    } catch (err: any) {
      return {
        success: false,
        error: `网络请求失败: ${err.message || err}`,
        provider: config.provider,
        responseTime: Date.now() - startTime,
      };
    }
  }

  private async makeClaudeRequest(request: ChatRequest, config: AIProviderConfig): Promise<ChatResponse> {
    const startTime = Date.now();
    try {
      const systemMessage = request.messages.find(m => m.role === 'system');
      const nonSystemMessages = request.messages
        .filter(m => m.role !== 'system')
        .map(m => ({ role: m.role, content: m.content }));

      const body: any = {
        model: config.model,
        max_tokens: request.maxTokens || config.maxTokens || 2000,
        temperature: typeof request.temperature === 'number' ? request.temperature : (config.temperature ?? 0.7),
        messages: nonSystemMessages,
      };

      if (systemMessage) {
        body.system = systemMessage.content;
      }

      const headers = {
        'Content-Type': 'application/json',
        'x-api-key': config.apiKey,
        'anthropic-version': config.apiVersion || '2023-06-01',
      };

      const response = await this.fetchWrapper(config.baseUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        timeout: 30000,
      });

      if (!response.ok) {
        const errorText = await response.text();
        return {
          success: false,
          error: `HTTP ${response.status}: ${errorText.slice(0, 200)}`,
          provider: config.provider,
          responseTime: Date.now() - startTime,
        };
      }

      const data = await response.json();
      const content = data.content?.[0]?.text || '';
      const tokensUsed = (data.usage?.input_tokens || 0) + (data.usage?.output_tokens || 0);

      return {
        success: true,
        content,
        provider: config.provider,
        model: config.model,
        responseTime: Date.now() - startTime,
        tokensUsed,
      };
    } catch (err: any) {
      return {
        success: false,
        error: `网络请求失败: ${err.message}`,
        provider: config.provider,
        responseTime: Date.now() - startTime,
      };
    }
  }

  private async makeGeminiRequest(request: ChatRequest, config: AIProviderConfig): Promise<ChatResponse> {
    const startTime = Date.now();
    try {
      const systemMessage = request.messages.find(m => m.role === 'system');
      const nonSystemMessages = request.messages
        .filter(m => m.role !== 'system')
        .map(m => ({
          role: m.role === 'assistant' ? 'model' : 'user',
          parts: [{ text: m.content }],
        }));

      const body: any = {
        contents: nonSystemMessages,
        generationConfig: {
          maxOutputTokens: request.maxTokens || config.maxTokens || 2000,
          temperature: typeof request.temperature === 'number' ? request.temperature : (config.temperature ?? 0.7),
        },
      };

      if (systemMessage) {
        body.systemInstruction = {
          parts: [{ text: systemMessage.content }],
        };
      }

      const url = `${config.baseUrl}/models/${config.model}:generateContent?key=${config.apiKey}`;
      const headers = {
        'Content-Type': 'application/json',
      };

      const response = await this.fetchWrapper(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        timeout: 30000,
      });

      if (!response.ok) {
        const errorText = await response.text();
        return {
          success: false,
          error: `HTTP ${response.status}: ${errorText.slice(0, 200)}`,
          provider: config.provider,
          responseTime: Date.now() - startTime,
        };
      }

      const data = await response.json();
      const content = data.candidates?.[0]?.content?.parts?.[0]?.text
        || data.candidates?.[0]?.text
        || '';
      const tokensUsed = data.usageMetadata?.totalTokenCount || 0;

      return {
        success: true,
        content,
        provider: config.provider,
        model: config.model,
        responseTime: Date.now() - startTime,
        tokensUsed,
      };
    } catch (err: any) {
      return {
        success: false,
        error: `网络请求失败: ${err.message}`,
        provider: config.provider,
        responseTime: Date.now() - startTime,
      };
    }
  }

  private async fetchWrapper(url: string, options: any & { timeout?: number }): Promise<any> {
    if (typeof fetch !== 'undefined') {
      const controller = new AbortController();
      const timeout = options.timeout || 30000;
      const timeoutId = setTimeout(() => controller.abort(), timeout);

      try {
        const fetchOptions = { ...options, signal: controller.signal };
        delete fetchOptions.timeout;
        const resp = await fetch(url, fetchOptions);
        clearTimeout(timeoutId);
        return resp;
      } catch (err: any) {
        clearTimeout(timeoutId);
        log.error('[AI] Fetch 失败:', err);
        if (err.name === 'AbortError') {
          throw new Error('请求超时');
        }
        throw err;
      }
    }

    return new Promise((resolve, reject) => {
      const timeoutId = setTimeout(() => reject(new Error('请求超时')), options.timeout || 30000);

      const XHR = (global as any).XMLHttpRequest;
      if (!XHR) {
        clearTimeout(timeoutId);
        reject(new Error('当前环境不支持 HTTP 请求'));
        return;
      }

      const xhr = new XHR();
      xhr.open(options.method || 'GET', url);

      if (options.headers) {
        for (const [key, value] of Object.entries(options.headers)) {
          xhr.setRequestHeader(key, value as string);
        }
      }

      xhr.onload = () => {
        clearTimeout(timeoutId);
        resolve({
          ok: xhr.status >= 200 && xhr.status < 300,
          status: xhr.status,
          text: async () => xhr.responseText,
          json: async () => JSON.parse(xhr.responseText),
        });
      };

      xhr.onerror = () => {
        clearTimeout(timeoutId);
        reject(new Error('网络连接失败'));
      };

      xhr.send(options.body || null);
    });
  }

  async testProvider(provider: AIProvider): Promise<{ success: boolean; responseTime: number; error?: string }> {
    const cfg = this.providerConfigs.get(provider);
    if (!cfg || !cfg.apiKey || !cfg.apiKey.trim()) {
      return { success: false, responseTime: 0, error: '请先配置 API Key' };
    }

    const originalProvider = this.currentProvider;
    this.currentProvider = provider;

    const testMessage = '你好，请用一句话介绍一下你自己。';
    const response = await this.chat(testMessage, false);

    this.currentProvider = originalProvider;

    return {
      success: response.success,
      responseTime: response.responseTime || 0,
      error: response.error,
    };
  }

  // ========== AI辅助功能：游戏分析 ==========
  async analyzeGameState(gameState: any, role: string): Promise<ChatResponse> {
    const systemPrompt = `你是一个专业的${role}游戏分析助手。根据游戏状态数据，为玩家提供具体的操作建议和评分分析。请用简洁的中文回答，分点列出。`;

    const message = `当前游戏状态：${JSON.stringify(gameState).slice(0, 500)}

请分析当前局势并给出操作建议。`;

    const cfg = this.providerConfigs.get(this.currentProvider);
    if (!cfg || !cfg.apiKey) {
      return { success: false, error: '请先配置 AI API Key' };
    }

    const messages: ChatMessage[] = [
      { role: 'system', content: systemPrompt },
      { role: 'user', content: message, timestamp: Date.now() },
    ];

    return this.makeRequest({
      messages,
      provider: this.currentProvider,
      model: cfg.model,
      maxTokens: cfg.maxTokens,
      temperature: cfg.temperature,
    }, cfg);
  }

  // ========== AI辅助功能：自动检测分路 ==========
  // 改进版：优先用基于关键词的快速识别（可靠无依赖），
  // 若用户已配置 API 则增强为 AI 分析（更智能）
  async autoDetectRole(screenDescription?: string): Promise<{ success: boolean; role?: string; roleName?: string; confidence?: number; error?: string; method?: string }> {
    // 先尝试关键词匹配（快速、无网络依赖）
    const keywordMatch = this.keywordBasedRoleDetect(screenDescription);
    if (keywordMatch && keywordMatch.confidence >= 0.7) {
      return {
        success: true,
        role: keywordMatch.role,
        roleName: keywordMatch.roleName,
        confidence: keywordMatch.confidence,
        method: 'keyword',
      };
    }

    // 如果没有配置 AI，直接返回关键词结果或提示用户手动选择
    const cfg = this.providerConfigs.get(this.currentProvider);
    if (!cfg || !cfg.apiKey || !cfg.baseUrl) {
      if (keywordMatch && keywordMatch.confidence > 0.5) {
        return {
          success: true,
          role: keywordMatch.role,
          roleName: keywordMatch.roleName,
          confidence: keywordMatch.confidence,
          method: 'keyword',
        };
      }
      return { success: false, error: '请先配置AI服务，或手动选择分路' };
    }

    // 尝试用 AI 做更智能的识别
    const prompt = `你是一个王者荣耀游戏分析师。根据以下描述，判断玩家正在玩的分路。

可选分路：对抗路、打野、中路、游走、发育路

${screenDescription ? `游戏界面描述: ${screenDescription}` : '请根据常见游戏行为模式分析'}

请直接返回JSON格式: {"role": "分路名", "confidence": 0-1之间的数字}
只返回JSON，不要有其他内容。`;

    const messages: ChatMessage[] = [
      { role: 'system', content: '你是一个专业的游戏分析师。' },
      { role: 'user', content: prompt, timestamp: Date.now() },
    ];

    try {
      const response = await this.makeRequest({
        messages,
        provider: this.currentProvider,
        model: cfg.model,
        maxTokens: 200,
        temperature: 0.3,
      }, cfg);

      if (!response.success || !response.content) {
        // AI 失败，退回关键词结果
        if (keywordMatch && keywordMatch.confidence > 0.5) {
          return {
            success: true,
            role: keywordMatch.role,
            roleName: keywordMatch.roleName,
            confidence: keywordMatch.confidence,
            method: 'keyword',
          };
        }
        return { success: false, error: response.error || 'AI分析失败' };
      }

      // 尝试解析 JSON
      let result: any = null;
      const content = response.content.trim();

      try {
        result = JSON.parse(content);
      } catch {
        const jsonMatch = content.match(/\{[\s\S]*\}/);
        if (jsonMatch) {
          try {
            result = JSON.parse(jsonMatch[0]);
          } catch {
            result = null;
          }
        }
      }

      // 如果 JSON 解析失败，尝试从文本中提取关键词
      if (!result || !result.role) {
        const roleNames: Record<string, string> = {
          '对抗路': 'exp_lane',
          '打野': 'jungle',
          '中路': 'mid_lane',
          '游走': 'roam',
          '发育路': 'gold_lane',
        };

        for (const [name, key] of Object.entries(roleNames)) {
          if (content.includes(name)) {
            return { success: true, role: key, roleName: name, confidence: 0.6, method: 'ai-keyword' };
          }
        }
        return { success: false, error: '无法识别分路' };
      }

      const roleMap: Record<string, string> = {
        '对抗路': 'exp_lane',
        '打野': 'jungle',
        '中路': 'mid_lane',
        '游走': 'roam',
        '发育路': 'gold_lane',
      };

      const detectedRole = roleMap[result.role] || result.role;
      const detectedRoleName = Object.keys(roleMap).find(k => roleMap[k] === detectedRole) || result.role;

      return {
        success: true,
        role: detectedRole,
        roleName: detectedRoleName,
        confidence: result.confidence || 0.7,
        method: 'ai',
      };
    } catch (error: any) {
      log.error('[AI] 自动检测分路失败:', error);
      // 网络异常时也退回关键词
      if (keywordMatch && keywordMatch.confidence > 0.5) {
        return {
          success: true,
          role: keywordMatch.role,
          roleName: keywordMatch.roleName,
          confidence: keywordMatch.confidence,
          method: 'keyword',
        };
      }
      return { success: false, error: error.message || '检测异常' };
    }
  }

  // 基于关键词的快速分路识别
  private keywordBasedRoleDetect(description?: string): { role: string; roleName: string; confidence: number } | null {
    if (!description) return null;

    const keywords: Record<string, string[]> = {
      exp_lane: ['对抗路', '上单', '单挑', '战士', '坦克', '防御塔', '1v1'],
      jungle: ['打野', '野区', '暴君', '主宰', '红蓝 buff', '红 buff', '蓝 buff', 'gank', '支援三路'],
      mid_lane: ['中路', '法师', '中间', '中路塔', '中路兵线'],
      roam: ['游走', '辅助', '保护', '插眼', '辅助装备', '跟队友', '开团'],
      gold_lane: ['发育路', '射手', '下路', '稳定发育', '经济最高'],
    };

    const roleNames: Record<string, string> = {
      exp_lane: '对抗路',
      jungle: '打野',
      mid_lane: '中路',
      roam: '游走',
      gold_lane: '发育路',
    };

    let bestRole: string | null = null;
    let bestCount = 0;

    for (const [role, kws] of Object.entries(keywords)) {
      let count = 0;
      for (const kw of kws) {
        if (description.includes(kw)) count++;
      }
      if (count > bestCount) {
        bestCount = count;
        bestRole = role;
      }
    }

    if (bestRole && bestCount > 0) {
      const confidence = Math.min(0.5 + bestCount * 0.15, 0.95);
      return { role: bestRole, roleName: roleNames[bestRole], confidence };
    }
    return null;
  }

  // ========== AI辅助功能：语音任务建议 ==========
  async getTaskSuggestions(role: string, gamePhase: string = 'mid_game'): Promise<string[]> {
    // 如果没有配置 AI，返回默认任务建议（无需 AI 也能工作）
    const cfg = this.providerConfigs.get(this.currentProvider);
    if (!cfg || !cfg.apiKey || !cfg.apiKey.trim()) {
      return this.getDefaultTasks(role, gamePhase);
    }

    const roleNames: Record<string, string> = {
      exp_lane: '对抗路',
      jungle: '打野',
      mid_lane: '中路',
      roam: '游走',
      gold_lane: '发育路',
    };

    const roleName = roleNames[role] || role;
    const prompt = `作为王者荣耀的${roleName}，请列出3-5条${gamePhase}阶段应该完成的关键任务，用简洁中文。返回JSON数组格式：["任务1","任务2","任务3"]`;

    try {
      const messages: ChatMessage[] = [
        { role: 'system', content: '你是王者荣耀专业教练，擅长给出具体的游戏建议。' },
        { role: 'user', content: prompt, timestamp: Date.now() },
      ];

      const response = await this.makeRequest({
        messages,
        provider: this.currentProvider,
        model: cfg.model,
        maxTokens: 500,
        temperature: 0.6,
      }, cfg);

      if (response.success && response.content) {
        const jsonMatch = response.content.match(/\[[\s\S]*\]/);
        if (jsonMatch) {
          try {
            const tasks = JSON.parse(jsonMatch[0]);
            if (Array.isArray(tasks) && tasks.length > 0) {
              return tasks.slice(0, 5);
            }
          } catch {
            // 解析失败，使用默认
          }
        }
      }
    } catch (err) {
      log.warn('[AI] 获取任务建议失败，使用默认任务:', err);
    }

    return this.getDefaultTasks(role, gamePhase);
  }

  private getDefaultTasks(role: string, gamePhase: string): string[] {
    const defaultTasks: Record<string, Record<string, string[]>> = {
      exp_lane: {
        early_game: ['快速清线升级', '争夺河道之灵', '观察打野动向', '保存闪现技能'],
        mid_game: ['带线推塔', '参加团战', '观察对方打野位置', '寻找单杀机会'],
        late_game: ['配合队友推进', '保护后排', '寻找绕后机会', '控制龙坑视野'],
      },
      jungle: {
        early_game: ['快速清理野区', '寻找Gank机会', '控制河道之灵', '关注对方打野位置'],
        mid_game: ['控制龙和暴君', '组织Gank', '入侵对方野区', '保护核心发育'],
        late_game: ['开团和反开', '控制大龙', '保护后排', '带领队伍节奏'],
      },
      mid_lane: {
        early_game: ['快速清线', '支援边路', '控制中路视野', '配合打野入侵'],
        mid_game: ['参与团战', '推塔支援', '配合打野节奏', '保护核心'],
        late_game: ['团战输出', '保护自己', '控制龙坑', '带领节奏'],
      },
      roam: {
        early_game: ['保护核心发育', '做好视野', '配合打野Gank', '骚扰对方'],
        mid_game: ['开团准备', '团队视野', '保护核心', '控制关键位置'],
        late_game: ['开团和反开', '保护后排', '团战视野', '骚扰对方核心'],
      },
      gold_lane: {
        early_game: ['稳定发育', '塔下补兵', '注意对方打野位置', '请求支援'],
        mid_game: ['参加团战', '推塔输出', '保持经济优势', '注意自身位置'],
        late_game: ['团战主要输出', '保护自己', '推塔和消耗', '控制龙坑'],
      },
    };

    return defaultTasks[role]?.[gamePhase] || defaultTasks[role]?.mid_game || ['注意地图', '配合队友', '保持发育'];
  }

  // 获取支持的分路列表（供UI使用）
  getSupportedRoles(): { id: string; name: string; hint: string }[] {
    return GAME_ROLES.map(r => ({ id: r.id, name: r.name, hint: r.hint }));
  }

  async clearAllData(): Promise<void> {
    await storage.removeItem('lookgm_ai_configs');
    await storage.removeItem('lookgm_ai_current');
    await storage.removeItem('lookgm_ai_history');
    await storage.removeItem('lookgm_ai_stats');
    this.initializeDefaults();
    this.emit('all-data-cleared');
    log.info('[AI] 所有数据已清除');
  }
}
