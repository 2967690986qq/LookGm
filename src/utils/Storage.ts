/**
 * LookGm 统一存储工具
 * 自动适配：浏览器 (localStorage) / React Native (AsyncStorage)
 */

let _storage: {
  setItem: (key: string, value: string) => Promise<void>;
  getItem: (key: string) => Promise<string | null>;
  removeItem: (key: string) => Promise<void>;
  getAllKeys: () => Promise<string[]>;
} | null = null;

let _syncStorage: Record<string, string> = {};
let _syncStorageLoaded = false;

async function ensureStorage() {
  if (_storage) return;

  // 浏览器环境
  if (typeof localStorage !== 'undefined') {
    _storage = {
      async setItem(key: string, value: string) {
        localStorage.setItem(key, value);
      },
      async getItem(key: string) {
        return localStorage.getItem(key);
      },
      async removeItem(key: string) {
        localStorage.removeItem(key);
      },
      async getAllKeys() {
        const keys: string[] = [];
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          if (k) keys.push(k);
        }
        return keys;
      },
    };
    return;
  }

  // React Native 环境 - 延迟加载
  try {
    const AsyncStorage = require('@react-native-async-storage/async-storage').default;
    _storage = AsyncStorage;
  } catch {
    // fallback: 内存存储（仅开发阶段使用）
    _syncStorageLoaded = true;
    _storage = {
      async setItem(key: string, value: string) {
        _syncStorage[key] = value;
      },
      async getItem(key: string) {
        return _syncStorage[key] ?? null;
      },
      async removeItem(key: string) {
        delete _syncStorage[key];
      },
      async getAllKeys() {
        return Object.keys(_syncStorage);
      },
    };
  }
}

// 同步读取（仅浏览器 / fallback 模式）
function getSync(key: string): string | null {
  if (typeof localStorage !== 'undefined') {
    return localStorage.getItem(key);
  }
  return _syncStorage[key] ?? null;
}

function setSync(key: string, value: string) {
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(key, value);
  } else {
    _syncStorage[key] = value;
  }
}

function removeSync(key: string) {
  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem(key);
  } else {
    delete _syncStorage[key];
  }
}

export const storage = {
  async setItem(key: string, value: string): Promise<void> {
    await ensureStorage();
    await _storage!.setItem(key, value);
    setSync(key, value);
  },

  async getItem(key: string): Promise<string | null> {
    // 优先同步返回（如果有缓存）
    const sync = getSync(key);
    if (sync !== null) return sync;
    await ensureStorage();
    const result = await _storage!.getItem(key);
    if (result !== null) setSync(key, result);
    return result;
  },

  async removeItem(key: string): Promise<void> {
    await ensureStorage();
    await _storage!.removeItem(key);
    removeSync(key);
  },

  async getAllKeys(): Promise<string[]> {
    await ensureStorage();
    return _storage!.getAllKeys();
  },

  // JSON 便捷封装
  async setJson<T>(key: string, value: T): Promise<void> {
    await this.setItem(key, JSON.stringify(value));
  },

  async getJson<T>(key: string): Promise<T | null> {
    const raw = await this.getItem(key);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as T;
    } catch {
      return null;
    }
  },

  async removePrefix(prefix: string): Promise<void> {
    const keys = await this.getAllKeys();
    await Promise.all(
      keys.filter(k => k.startsWith(prefix)).map(k => this.removeItem(k))
    );
  },
};

export default storage;
