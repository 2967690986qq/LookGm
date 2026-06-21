import React, { useEffect, useState, useMemo } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Switch,
  SafeAreaView,
  TextInput,
  Alert,
  StatusBar,
  Platform,
  Modal,
  Pressable,
  ActivityIndicator,
  Image,
} from 'react-native';
import { AppStore, Page } from './store/AppStore';
import { Role } from './types/game';
import { ScoreResult } from './types/scoring';
import { AIProvider, PROVIDER_META, PROVIDER_MODELS } from './core/ai-api/AIApiService';

// ==================== 主题颜色 - 明亮简约 ====================
const COLORS = {
  primary: '#4F46E5',
  primarySoft: '#EEF2FF',
  secondary: '#10B981',
  success: '#10B981',
  warning: '#F59E0B',
  danger: '#EF4444',
  gold: '#F59E0B',
  text: '#1F2937',
  textSecondary: '#6B7280',
  textMuted: '#9CA3AF',
  border: '#E5E7EB',
  background: '#F8FAFC',
  card: '#FFFFFF',
  white: '#FFFFFF',
  shadow: '#000000',
  divider: '#F3F4F6',
  overlay: 'rgba(15,23,42,0.5)',
};

// ==================== 头像颜色池 ====================
const AVATAR_COLORS = [
  '#4F46E5', '#10B981', '#F59E0B', '#EF4444',
  '#8B5CF6', '#3B82F6', '#EC4899', '#14B8A6',
  '#F97316', '#6366F1', '#84CC16', '#06B6D4',
];

// ==================== 分路配置 ====================
const ROLE_LABELS: Record<Role, string> = {
  exp_lane: '对抗路',
  jungle: '打野',
  mid_lane: '中路',
  roam: '游走',
  gold_lane: '发育路',
};

const ROLE_ICONS: Record<Role, string> = {
  exp_lane: '⚔️',
  jungle: '🌲',
  mid_lane: '🔮',
  roam: '🛡️',
  gold_lane: '🏹',
};

const ROLE_COLORS: Record<Role, string> = {
  exp_lane: '#EF4444',
  jungle: '#22C55E',
  mid_lane: '#8B5CF6',
  roam: '#3B82F6',
  gold_lane: '#EAB308',
};

const GRADE_CONFIG: Record<string, { color: string; emoji: string; bg: string }> = {
  '顶级评分': { color: '#F59E0B', emoji: '👑', bg: '#FEF3C7' },
  '金牌评分': { color: '#EA580C', emoji: '🥇', bg: '#FFEDD5' },
  '银牌评分': { color: '#6B7280', emoji: '🥈', bg: '#F3F4F6' },
  '铜牌评分': { color: '#92400E', emoji: '🥉', bg: '#FDE68A' },
  '暂无评级': { color: '#9CA3AF', emoji: '—', bg: '#F3F4F6' },
};

// ==================== Toast ====================
const showToast = (message: string) => {
  Alert.alert('提示', message, [{ text: '好' }]);
};

// ==================== 主应用组件 ====================
export default function App() {
  const [store] = useState(() => AppStore.getInstance());
  const [state, setState] = useState(store.getState());
  const [initialized, setInitialized] = useState(false);

  useEffect(() => {
    // 初始化：等待AuthService加载完成，然后检查登录状态
    const init = async () => {
      await store.initialize();
      setInitialized(true);
    };
    init();

    const handler = () => setState({ ...store.getState() });
    store.on('state-changed', handler);
    return () => store.removeListener('state-changed', handler);
  }, [store]);

  if (!initialized) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.splashContainer}>
          <Text style={styles.splashEmoji}>🎮</Text>
          <Text style={styles.splashTitle}>LookGm</Text>
          <Text style={styles.splashSubtitle}>游戏评分助手</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor={COLORS.background} />
      {state.page === 'login' ? (
        <LoginPage store={store} />
      ) : (
        <>
          <Header state={state} store={store} />
          <ScrollView
            style={styles.content}
            contentContainerStyle={styles.contentContainer}
            showsVerticalScrollIndicator={false}
          >
            {state.page === 'home' && <HomePage store={store} state={state} />}
            {state.page === 'profile' && <ProfilePage store={store} state={state} />}
            {state.page === 'ai-settings' && <AISettingsPage store={store} state={state} />}
            {state.page === 'settings' && <SettingsPage store={store} state={state} />}
          </ScrollView>
          <BottomNav store={store} currentPage={state.page} />
        </>
      )}
    </SafeAreaView>
  );
}

// ==================== 顶部栏 ====================
function Header({ state, store }: { state: any; store: AppStore }) {
  const titles: Record<Page, string> = {
    login: '',
    home: '🎮 游戏评分助手',
    profile: '👤 我的',
    'ai-settings': '🤖 AI 配置',
    settings: '⚙️ 设置',
    'recording-history': '📚 历史记录',
  };

  return (
    <View style={styles.header}>
      <Text style={styles.headerTitle}>{titles[state.page as Page] || 'LookGm'}</Text>
      <View style={styles.headerRight}>
        {state.backgroundServiceRunning && (
          <View style={[styles.badge, styles.badgeActive]}>
            <Text style={styles.badgeText}>监测中</Text>
          </View>
        )}
        {state.aiConfigured && (
          <View style={[styles.badge, styles.badgeAI]}>
            <Text style={styles.badgeText}>AI</Text>
          </View>
        )}
      </View>
    </View>
  );
}

// ==================== 底部导航 ====================
function BottomNav({ store, currentPage }: { store: AppStore; currentPage: Page }) {
  const navItems = [
    { key: 'home' as Page, icon: '🏠', label: '首页' },
    { key: 'ai-settings' as Page, icon: '🤖', label: 'AI配置' },
    { key: 'settings' as Page, icon: '⚙️', label: '设置' },
    { key: 'profile' as Page, icon: '👤', label: '我的' },
  ];

  return (
    <View style={styles.bottomNav}>
      {navItems.map((item) => (
        <Pressable
          key={item.key}
          style={styles.navItem}
          onPress={() => store.navigate(item.key)}
        >
          <Text style={[styles.navIcon, currentPage === item.key && styles.navIconActive]}>
            {item.icon}
          </Text>
          <Text style={[styles.navLabel, currentPage === item.key && styles.navLabelActive]}>
            {item.label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

// ==================== 登录页 ====================
function LoginPage({ store }: { store: AppStore }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [nickname, setNickname] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async () => {
    setError('');
    const name = nickname.trim();
    if (name.length === 0) {
      setError('请输入昵称');
      return;
    }

    if (mode === 'register') {
      if (password.length === 0) {
        setError('请设置密码');
        return;
      }
      if (password !== confirmPassword) {
        setError('两次密码不一致');
        return;
      }
    }

    setLoading(true);
    try {
      if (mode === 'login') {
        const result = await store.login(name, password);
        if (result.success) {
          showToast(`欢迎，${name}！`);
        } else {
          setError(result.error || '登录失败');
        }
      } else {
        const result = await store.register(name, password);
        if (result.success) {
          showToast(`注册成功，欢迎 ${name}！`);
        } else {
          setError(result.error || '注册失败');
        }
      }
    } catch (err: any) {
      setError(err.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  const handleQuickStart = async () => {
    setLoading(true);
    try {
      await store.quickLogin('游戏玩家');
      showToast('快速启动成功！');
    } catch (err: any) {
      setError(err.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.loginContainer} keyboardShouldPersistTaps="handled">
      <View style={styles.loginContent}>
        <View style={styles.logoBox}>
          <Text style={styles.logoEmoji}>🎮</Text>
        </View>
        <Text style={styles.appTitle}>LookGm</Text>
        <Text style={styles.appSubtitle}>王者荣耀智能评分助手</Text>

        <View style={styles.loginCard}>
          {/* 切换登录/注册 */}
          <View style={styles.authTabContainer}>
            <TouchableOpacity
              style={[styles.authTab, mode === 'login' && styles.authTabActive]}
              onPress={() => {
                setMode('login');
                setError('');
              }}
            >
              <Text style={[styles.authTabText, mode === 'login' && styles.authTabTextActive]}>登录</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.authTab, mode === 'register' && styles.authTabActive]}
              onPress={() => {
                setMode('register');
                setError('');
              }}
            >
              <Text style={[styles.authTabText, mode === 'register' && styles.authTabTextActive]}>注册</Text>
            </TouchableOpacity>
          </View>

          <Text style={styles.cardLabel}>昵称</Text>
          <TextInput
            style={styles.textInput}
            placeholder="请输入昵称"
            placeholderTextColor={COLORS.textMuted}
            value={nickname}
            onChangeText={setNickname}
            returnKeyType="next"
            autoCapitalize="none"
            autoCorrect={false}
          />

          <Text style={styles.cardLabel}>密码</Text>
          <TextInput
            style={styles.textInput}
            placeholder={mode === 'register' ? '请设置密码' : '请输入密码'}
            placeholderTextColor={COLORS.textMuted}
            value={password}
            onChangeText={setPassword}
            secureTextEntry
            returnKeyType={mode === 'login' ? 'done' : 'next'}
          />

          {mode === 'register' && (
            <>
              <Text style={styles.cardLabel}>确认密码</Text>
              <TextInput
                style={styles.textInput}
                placeholder="请再次输入密码"
                placeholderTextColor={COLORS.textMuted}
                value={confirmPassword}
                onChangeText={setConfirmPassword}
                secureTextEntry
                returnKeyType="done"
              />
            </>
          )}

          {error ? (
            <Text style={styles.authError}>{error}</Text>
          ) : null}

          <TouchableOpacity
            style={styles.primaryButton}
            onPress={handleSubmit}
            disabled={loading}
          >
            {loading ? (
              <ActivityIndicator color={COLORS.white} size="small" />
            ) : (
              <Text style={styles.primaryButtonText}>{mode === 'login' ? '登录' : '注册并登录'}</Text>
            )}
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.quickStartButton}
            onPress={handleQuickStart}
            disabled={loading}
          >
            <Text style={styles.quickStartText}>⚡ 不创建账号，直接快速使用</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.featuresText}>
          ✨ AI智能分析 · 📊 实时评分 · 🔊 语音播报 · 🪟 悬浮窗
        </Text>
      </View>
    </ScrollView>
  );
}

// ==================== 首页 ====================
function HomePage({ store, state }: { store: AppStore; state: any }) {
  const [aiDetecting, setAIDetecting] = useState(false);

  const games = store.getAvailableGames();
  const user = state.user;

  const handleStartBackground = async () => {
    if (state.backgroundServiceRunning) {
      const ok = await store.stopBackgroundService();
      if (ok) showToast('已停止后台监测');
    } else {
      const ok = await store.startBackgroundService();
      if (ok) showToast('后台监测已启动');
    }
  };

  const handleFloatingWindow = async () => {
    if (state.floatingWindowEnabled) {
      await store.stopFloatingWindow();
      showToast('已关闭悬浮窗');
    } else {
      await store.startFloatingWindow();
      showToast('悬浮窗已启动');
    }
  };

  const handleAIDetectRole = async () => {
    setAIDetecting(true);
    try {
      const result: any = await store.autoDetectRole();
      if (result && result.success && result.role) {
        const roleName = ROLE_LABELS[result.role as Role] || result.role;
        showToast(`AI 识别为 ${roleName}`);
      } else if (result && result.error) {
        Alert.alert('识别失败', result.error);
      }
    } catch (err: any) {
      Alert.alert('识别失败', err.message || '请稍后重试');
    } finally {
      setAIDetecting(false);
    }
  };

  return (
    <View style={styles.homeContainer}>
      {/* 用户卡片 */}
      {user && (
        <TouchableOpacity style={styles.userCard} onPress={() => store.navigate('profile')} activeOpacity={0.7}>
          <View style={[styles.userAvatar, { backgroundColor: user.avatar || COLORS.primarySoft }]}>
            <Text style={styles.userAvatarText}>{getInitial(user.nickname)}</Text>
          </View>
          <View style={styles.userInfo}>
            <Text style={styles.userName}>{user.nickname || '用户'}</Text>
            <Text style={styles.userMeta}>👤 本地账号</Text>
          </View>
          <Text style={styles.userArrow}>›</Text>
        </TouchableOpacity>
      )}

      {/* 状态卡片 */}
      <View style={styles.statusRow}>
        <View style={styles.statusCard}>
          <Text style={styles.statusIcon}>📡</Text>
          <Text style={styles.statusLabel}>后台监测</Text>
          <Text style={[styles.statusValue, { color: state.backgroundServiceRunning ? COLORS.success : COLORS.textMuted }]}>
            {state.backgroundServiceRunning ? '已开启' : '未开启'}
          </Text>
        </View>
        <View style={styles.statusCard}>
          <Text style={styles.statusIcon}>🪟</Text>
          <Text style={styles.statusLabel}>悬浮窗</Text>
          <Text style={[styles.statusValue, { color: state.floatingWindowEnabled ? COLORS.success : COLORS.textMuted }]}>
            {state.floatingWindowEnabled ? '已开启' : '未开启'}
          </Text>
        </View>
      </View>

      {/* 游戏选择 */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>选择游戏</Text>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 4 }}>
          {games.map((game: any) => (
            <TouchableOpacity
              key={game.id}
              style={[styles.gameCard, state.gameId === game.id && styles.gameCardActive]}
              onPress={() => store.selectGame(game.id)}
            >
              <Text style={[styles.gameName, state.gameId === game.id && styles.gameNameActive]}>
                {game.displayName}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* 分路选择 */}
      {state.gameId && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>选择分路</Text>
          <View style={styles.rolesGrid}>
            {(['exp_lane', 'jungle', 'mid_lane', 'roam', 'gold_lane'] as Role[]).map((role) => (
              <TouchableOpacity
                key={role}
                style={[
                  styles.roleCard,
                  state.role === role && {
                    borderColor: ROLE_COLORS[role],
                    backgroundColor: `${ROLE_COLORS[role]}15`,
                  },
                ]}
                onPress={() => store.selectRole(role)}
              >
                <Text style={styles.roleIcon}>{ROLE_ICONS[role]}</Text>
                <Text style={[styles.roleName, state.role === role && { color: ROLE_COLORS[role] }]}>
                  {ROLE_LABELS[role]}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          <TouchableOpacity style={styles.aiDetectButton} onPress={handleAIDetectRole} disabled={aiDetecting}>
            {aiDetecting ? (
              <ActivityIndicator color={COLORS.primary} size="small" />
            ) : (
              <>
                <Text style={styles.aiDetectIcon}>🤖</Text>
                <Text style={styles.aiDetectText}>AI 自动检测当前分路</Text>
              </>
            )}
          </TouchableOpacity>
        </View>
      )}

      {/* 评分展示 */}
      {state.score && <ScoreCard score={state.score} />}

      {/* 快速评分按钮 */}
      {state.gameId && state.role && (
        <TouchableOpacity
          style={styles.computeScoreButton}
          onPress={() => {
            store.computeScoreNow();
            showToast('评分已更新');
          }}
        >
          <Text style={styles.computeScoreIcon}>📊</Text>
          <Text style={styles.computeScoreText}>立即计算评分</Text>
        </TouchableOpacity>
      )}

      {/* 功能开关 */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>功能开关</Text>

        <FeatureCard
          icon="📡"
          title="后台监测"
          subtitle="后台运行，实时分析游戏画面"
          active={state.backgroundServiceRunning}
          onPress={handleStartBackground}
        />

        <FeatureCard
          icon="🪟"
          title="悬浮窗"
          subtitle="显示实时评分和任务提示"
          active={state.floatingWindowEnabled}
          onPress={handleFloatingWindow}
        />

        <FeatureCard
          icon="🔊"
          title="语音播报"
          subtitle="评分变化和任务语音提示"
          active={state.voiceEnabled}
          onPress={() => {
            store.setVoiceEnabled(!state.voiceEnabled);
            showToast(state.voiceEnabled ? '语音播报已关闭' : '语音播报已开启');
          }}
        />

        <FeatureCard
          icon="🛡️"
          title="防检测模式"
          subtitle="降低被游戏反作弊系统识别风险"
          active={state.antiDetectionEnabled}
          onPress={() => {
            store.setAntiDetectionEnabled(!state.antiDetectionEnabled);
            showToast(state.antiDetectionEnabled ? '防检测已关闭' : '防检测已开启');
          }}
        />
      </View>

      {/* 使用说明 */}
      <View style={styles.helpCard}>
        <Text style={styles.helpTitle}>💡 使用说明</Text>
        <Text style={styles.helpText}>1. 选择游戏和分路（或点击 AI 自动检测）</Text>
        <Text style={styles.helpText}>2. 开启「后台监测」</Text>
        <Text style={styles.helpText}>3. 打开王者荣耀开始游戏</Text>
        <Text style={styles.helpText}>4. 应用会在后台实时评分并语音播报</Text>
        <Text style={styles.helpText}>5. 可开启悬浮窗查看实时评分</Text>
      </View>
    </View>
  );
}

function getInitial(name: string): string {
  if (!name) return 'U';
  const trimmed = name.trim();
  if (trimmed.length === 0) return 'U';
  return trimmed.charAt(0).toUpperCase();
}

function FeatureCard({ icon, title, subtitle, active, onPress }: {
  icon: string;
  title: string;
  subtitle: string;
  active: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable style={[styles.featureCard, active && styles.featureCardActive]} onPress={onPress}>
      <View style={styles.featureLeft}>
        <View style={styles.featureIconBox}>
          <Text style={styles.featureIcon}>{icon}</Text>
        </View>
        <View style={styles.featureInfo}>
          <Text style={styles.featureTitle}>{title}</Text>
          <Text style={styles.featureSubtitle}>{subtitle}</Text>
        </View>
      </View>
      <Switch
        value={active}
        onValueChange={onPress}
        trackColor={{ true: COLORS.primary, false: COLORS.border }}
        thumbColor={COLORS.white}
      />
    </Pressable>
  );
}

// ==================== 评分卡片 ====================
function ScoreCard({ score }: { score: ScoreResult }) {
  const gradeConfig = GRADE_CONFIG[score.grade] || GRADE_CONFIG['暂无评级'];
  return (
    <View style={[styles.scoreCard, { backgroundColor: gradeConfig.bg }]}>
      <View style={styles.scoreHeaderRow}>
        <Text style={[styles.gradeText, { color: gradeConfig.color }]}>
          {gradeConfig.emoji} {score.grade}
        </Text>
        <Text style={[styles.scoreValue, { color: gradeConfig.color }]}>{score.totalScore.toFixed(1)}</Text>
      </View>

      <View style={styles.dimensionsRow}>
        <DimensionItem label="输出" value={score.breakdown.damage} color="#EF4444" />
        <DimensionItem label="生存" value={score.breakdown.survival} color="#22C55E" />
        <DimensionItem label="团战" value={score.breakdown.teamfight} color="#3B82F6" />
        <DimensionItem label="发育" value={score.breakdown.farm} color="#EAB308" />
        <DimensionItem label="KDA" value={score.breakdown.kda} color="#8B5CF6" />
      </View>

      {score.positiveActions && score.positiveActions.length > 0 && (
        <View style={styles.actionsBox}>
          <Text style={styles.actionsTitle}>✅ 加分操作</Text>
          {score.positiveActions.slice(0, 3).map((action, i) => (
            <Text key={i} style={styles.actionText}>· {action}</Text>
          ))}
        </View>
      )}
      {score.negativeActions && score.negativeActions.length > 0 && (
        <View style={styles.actionsBox}>
          <Text style={styles.actionsTitleNeg}>❌ 扣分操作</Text>
          {score.negativeActions.slice(0, 3).map((action, i) => (
            <Text key={i} style={styles.actionTextNeg}>· {action}</Text>
          ))}
        </View>
      )}
    </View>
  );
}

function DimensionItem({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <View style={styles.dimensionBox}>
      <Text style={[styles.dimensionValue, { color }]}>{value.toFixed(1)}</Text>
      <Text style={styles.dimensionLabel}>{label}</Text>
    </View>
  );
}

// ==================== 个人中心 ====================
function ProfilePage({ store, state }: { store: AppStore; state: any }) {
  const user = state.user;
  const [editModal, setEditModal] = useState(false);
  const [avatarModal, setAvatarModal] = useState(false);
  const [newNickname, setNewNickname] = useState(user?.nickname || '');

  return (
    <View style={styles.profileContainer}>
      {/* 用户信息卡片 */}
      <View style={styles.profileHeader}>
        <TouchableOpacity
          style={[styles.profileAvatar]}
          onPress={() => setAvatarModal(true)}
        >
          {user?.avatar && user.avatar.startsWith('http') ? (
            <Image
              source={{ uri: user.avatar }}
              style={styles.profileAvatarImage}
            />
          ) : (
            <View style={[styles.profileAvatarPlaceholder, { backgroundColor: user?.avatarColor || user?.avatar || COLORS.primary }]}>
              <Text style={styles.profileAvatarText}>{getInitial(user?.nickname || 'U')}</Text>
            </View>
          )}
        </TouchableOpacity>
        <Text style={styles.profileName}>{user?.nickname || '未登录'}</Text>
        <Text style={styles.profileMeta}>👤 本地账号</Text>
        <TouchableOpacity
          style={styles.editProfileBtn}
          onPress={() => {
            setNewNickname(user?.nickname || '');
            setEditModal(true);
          }}
        >
          <Text style={styles.editProfileBtnText}>✏️ 修改资料</Text>
        </TouchableOpacity>
      </View>

      {/* 菜单 */}
      <View style={styles.menuGroup}>
        <MenuItem
          icon="🤖"
          label="AI 配置"
          subtitle={state.aiConfigured ? '已配置' : '未配置'}
          onPress={() => store.navigate('ai-settings')}
        />
        <MenuItem
          icon="⚙️"
          label="偏好设置"
          subtitle="语音 / 悬浮窗 / 监测"
          onPress={() => store.navigate('settings')}
        />
        <MenuItem
          icon="📖"
          label="使用说明"
          subtitle="功能使用指南"
          onPress={() => Alert.alert(
            '使用说明',
            '1. 选择游戏和分路\n2. 开启后台监测\n3. 打开王者荣耀\n4. 应用自动分析评分\n5. 可开启悬浮窗查看实时评分'
          )}
        />
      </View>

      {/* 退出登录 */}
      <TouchableOpacity
        style={styles.dangerButton}
        onPress={() => {
          Alert.alert('退出登录', '确定要退出登录吗？', [
            { text: '取消', style: 'cancel' },
            { text: '确定', style: 'destructive', onPress: () => store.logout() },
          ]);
        }}
      >
        <Text style={styles.dangerButtonText}>🚪 退出登录</Text>
      </TouchableOpacity>

      {/* 修改昵称弹窗 */}
      <Modal visible={editModal} transparent animationType="fade" onRequestClose={() => setEditModal(false)}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>修改昵称</Text>
            <TextInput
              style={styles.textInput}
              placeholder="请输入昵称"
              placeholderTextColor={COLORS.textMuted}
              value={newNickname}
              onChangeText={setNewNickname}
            />
            <View style={styles.modalButtonRow}>
              <TouchableOpacity style={[styles.modalButton, styles.modalButtonSecondary]} onPress={() => setEditModal(false)}>
                <Text style={styles.modalButtonSecondaryText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalButton, styles.modalButtonPrimary]}
                onPress={() => {
                  const n = newNickname.trim();
                  if (n.length === 0) {
                    Alert.alert('提示', '昵称不能为空');
                    return;
                  }
                  store.updateNickname(n);
                  setEditModal(false);
                  showToast('昵称已更新');
                }}
              >
                <Text style={styles.modalButtonPrimaryText}>保存</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* 修改头像弹窗 */}
      <Modal visible={avatarModal} transparent animationType="fade" onRequestClose={() => setAvatarModal(false)}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>选择头像</Text>
            <TouchableOpacity
              style={styles.avatarUrlButton}
              onPress={() => {
                Alert.prompt(
                  '输入图片链接',
                  '请输入图片URL地址',
                  [
                    { text: '取消', style: 'cancel' },
                    {
                      text: '确认',
                      onPress: (url) => {
                        if (url && url.trim()) {
                          store.updateAvatar(url.trim());
                          setAvatarModal(false);
                          showToast('头像已更新');
                        }
                      },
                    },
                  ],
                  'plain-text',
                  user?.avatar && user.avatar.startsWith('http') ? user.avatar : ''
                );
              }}
            >
              <Text style={styles.avatarUrlButtonText}>🌐 输入图片链接</Text>
            </TouchableOpacity>
            <Text style={styles.avatarSectionLabel}>或选择颜色</Text>
            <View style={styles.avatarColorGrid}>
              {AVATAR_COLORS.map((color) => {
                const active = user?.avatarColor === color;
                return (
                  <TouchableOpacity
                    key={color}
                    style={[styles.avatarColorItem, { backgroundColor: color }, active && styles.avatarColorItemActive]}
                    onPress={() => {
                      store.updateAvatarColor(color);
                      setAvatarModal(false);
                      showToast('头像已更新');
                    }}
                  >
                    {active && <Text style={styles.avatarCheck}>✓</Text>}
                  </TouchableOpacity>
                );
              })}
            </View>
            <TouchableOpacity
              style={[styles.modalButton, styles.modalButtonSecondary, styles.modalFullWidth]}
              onPress={() => setAvatarModal(false)}
            >
              <Text style={styles.modalButtonSecondaryText}>关闭</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    </View>
  );
}

function MenuItem({ icon, label, subtitle, onPress }: {
  icon: string; label: string; subtitle?: string; onPress: () => void;
}) {
  return (
    <Pressable style={styles.menuItem} onPress={onPress}>
      <View style={styles.menuIconBox}>
        <Text style={styles.menuIcon}>{icon}</Text>
      </View>
      <View style={styles.menuInfo}>
        <Text style={styles.menuLabel}>{label}</Text>
        {subtitle && <Text style={styles.menuSubtitle}>{subtitle}</Text>}
      </View>
      <Text style={styles.menuArrow}>›</Text>
    </Pressable>
  );
}

// ==================== AI 配置页 ====================
function AISettingsPage({ store, state }: { store: AppStore; state: any }) {
  const availableProviders: AIProvider[] = useMemo(() => Object.keys(PROVIDER_META).filter(
    (k) => k !== 'none'
  ) as AIProvider[], []);

  const currentProvider = state.aiProvider || 'zhipu';
  const providerConfigs = useMemo(() => {
    const configs: Record<string, { apiKey: string; baseUrl: string; model: string; enabled: boolean }> = {};
    for (const p of availableProviders) {
      const cfg = store.getAIProviderConfig(p);
      if (cfg) {
        configs[p] = {
          apiKey: cfg.apiKey || '',
          baseUrl: cfg.baseUrl || '',
          model: cfg.model || '',
          enabled: !!cfg.enabled,
        };
      }
    }
    return configs;
  }, [state.aiProvider]);

  const [selectedProvider, setSelectedProvider] = useState<AIProvider>(currentProvider);
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<string>('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [showModelPicker, setShowModelPicker] = useState(false);

  // 切换提供商时填充默认或已保存的配置
  useEffect(() => {
    const saved = providerConfigs[selectedProvider];
    const defaults: Partial<Record<AIProvider, string>> = {
      zhipu: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
      deepseek: 'https://api.deepseek.com/v1/chat/completions',
      qwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
      gpt: 'https://api.openai.com/v1/chat/completions',
      ollama: 'http://localhost:11434/v1/chat/completions',
      claude: 'https://api.anthropic.com/v1/messages',
      gemini: 'https://generativelanguage.googleapis.com/v1beta',
      custom: '',
    };
    const models = (PROVIDER_MODELS as any)[selectedProvider] || [];

    // 优先使用已保存的配置
    if (saved && saved.apiKey) {
      setApiKey(saved.apiKey);
    } else {
      setApiKey('');
    }
    if (saved && saved.baseUrl) {
      setBaseUrl(saved.baseUrl);
    } else if (defaults[selectedProvider]) {
      setBaseUrl(defaults[selectedProvider] || '');
    } else {
      setBaseUrl('');
    }
    if (saved && saved.model) {
      setModel(saved.model);
    } else if (models.length > 0) {
      setModel(models[0]);
    } else {
      setModel('');
    }
  }, [selectedProvider, state.aiProvider]);

  const handleTest = async () => {
    if (!apiKey.trim()) {
      Alert.alert('提示', '请输入 API Key');
      return;
    }
    setTesting(true);
    setTestResult('正在测试连接...');
    try {
      store.configureAIProvider(selectedProvider, {
        apiKey: apiKey.trim(),
        baseUrl: baseUrl.trim(),
        model: model.trim() || undefined,
        enabled: true,
      });
      store.setCurrentAIProvider(selectedProvider);
      const result: any = await store.testAIProvider(selectedProvider);
      if (result && result.success) {
        setTestResult(`✅ 连接成功！响应时间: ${result.responseTime}ms`);
        showToast('AI 配置成功');
      } else {
        setTestResult(`❌ 测试失败: ${result?.error || '未知错误'}`);
      }
    } catch (err: any) {
      setTestResult(`❌ 异常: ${err.message || err}`);
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async () => {
    if (!apiKey.trim()) {
      Alert.alert('提示', '请输入 API Key');
      return;
    }
    try {
      store.configureAIProvider(selectedProvider, {
        apiKey: apiKey.trim(),
        baseUrl: baseUrl.trim(),
        model: model.trim() || undefined,
        enabled: true,
      });
      store.setCurrentAIProvider(selectedProvider);
      setTestResult('✅ 配置已保存');
      showToast('配置已保存');
    } catch (err: any) {
      setTestResult(`❌ 保存失败: ${err.message || err}`);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.aiContainer}>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle}>🤖 AI 服务配置</Text>
        <Text style={styles.pageSubtitle}>选择 AI 提供商并配置 API Key 以启用智能分析</Text>
      </View>

      {/* 配置卡片 */}
      <View style={styles.inputCard}>
        <Text style={styles.inputLabel}>API Key</Text>
        <View style={styles.apiKeyContainer}>
          <TextInput
            style={styles.apiKeyInput}
            placeholder="请输入 API Key"
            placeholderTextColor={COLORS.textMuted}
            value={apiKey}
            onChangeText={setApiKey}
            autoCapitalize="none"
            autoCorrect={false}
            secureTextEntry={!showApiKey}
          />
          <TouchableOpacity
            style={styles.showHideButton}
            onPress={() => setShowApiKey(!showApiKey)}
          >
            <Text style={styles.showHideText}>{showApiKey ? '👁️' : '👁️‍🗨️'}</Text>
          </TouchableOpacity>
        </View>
        <Text style={styles.inputLabel}>服务地址 (Base URL)</Text>
        <TextInput
          style={styles.textInputLarge}
          placeholder="可选 - 默认会使用官方地址"
          placeholderTextColor={COLORS.textMuted}
          value={baseUrl}
          onChangeText={setBaseUrl}
          autoCapitalize="none"
          autoCorrect={false}
        />
        <Text style={styles.inputLabel}>模型名称</Text>
        <TouchableOpacity
          style={styles.modelSelector}
          onPress={() => setShowModelPicker(!showModelPicker)}
        >
          <Text style={[styles.modelSelectorText, !model && styles.modelSelectorPlaceholder]}>
            {model || '点击选择模型'}
          </Text>
          <Text style={styles.modelSelectorArrow}>{showModelPicker ? '▲' : '▼'}</Text>
        </TouchableOpacity>
        {showModelPicker && (
          <View style={styles.modelPickerContainer}>
            {(PROVIDER_MODELS as any)[selectedProvider]?.map((m: string) => (
              <TouchableOpacity
                key={m}
                style={[styles.modelOption, model === m && styles.modelOptionSelected]}
                onPress={() => {
                  setModel(m);
                  setShowModelPicker(false);
                }}
              >
                <Text style={[styles.modelOptionText, model === m && styles.modelOptionTextSelected]}>
                  {m}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        )}
        {!showModelPicker && model && (
          <TextInput
            style={[styles.textInputLarge, { marginTop: -8 }]}
            placeholder="也可直接输入模型名称"
            placeholderTextColor={COLORS.textMuted}
            value={model}
            onChangeText={setModel}
            autoCapitalize="none"
            autoCorrect={false}
          />
        )}

        <View style={styles.buttonRow}>
          <TouchableOpacity
            style={[styles.saveButton, !apiKey.trim() && styles.testButtonDisabled]}
            onPress={handleSave}
            disabled={!apiKey.trim()}
          >
            <Text style={styles.testButtonText}>💾 保存配置</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.testButton, (testing || !apiKey.trim()) && styles.testButtonDisabled]}
            onPress={handleTest}
            disabled={testing || !apiKey.trim()}
          >
            {testing ? (
              <ActivityIndicator color={COLORS.white} size="small" />
            ) : (
              <Text style={styles.testButtonText}>🧪 测试连接</Text>
            )}
          </TouchableOpacity>
        </View>
        {testResult ? (
          <Text style={[styles.testResultText, testResult.startsWith('✅') && styles.testResultSuccess, testResult.startsWith('❌') && styles.testResultError]}>
            {testResult}
          </Text>
        ) : null}
      </View>

      {/* 提供商列表 */}
      <Text style={styles.sectionTitle}>选择 AI 服务商</Text>
      {availableProviders.map((p) => {
        const meta = (PROVIDER_META as any)[p];
        const isActive = selectedProvider === p;
        const isConfigured = state.aiConfigured && state.aiProvider === p;
        return (
          <TouchableOpacity
            key={p}
            style={[styles.providerCard, isActive && styles.providerCardActive]}
            onPress={() => setSelectedProvider(p)}
          >
            <View style={styles.providerLeft}>
              <Text style={styles.providerIcon}>{meta?.icon || '🧠'}</Text>
              <View style={styles.providerInfo}>
                <View style={styles.providerNameRow}>
                  <Text style={styles.providerName}>{meta?.name || p}</Text>
                  {isConfigured && (
                    <View style={styles.configuredTag}>
                      <Text style={styles.configuredTagText}>当前使用</Text>
                    </View>
                  )}
                </View>
                {meta?.description && <Text style={styles.providerDesc}>{meta.description}</Text>}
              </View>
            </View>
            <View style={[styles.radioCircle, isActive && styles.radioCircleActive]}>
              {isActive && <View style={styles.radioDot} />}
            </View>
          </TouchableOpacity>
        );
      })}

      {/* 功能说明 */}
      <View style={styles.aiFunctionsCard}>
        <Text style={styles.functionsTitle}>✨ AI 能为你做什么</Text>
        <View style={styles.functionRow}>
          <Text style={styles.functionIcon}>🎯</Text>
          <Text style={styles.functionText}>智能识别当前对局分路</Text>
        </View>
        <View style={styles.functionRow}>
          <Text style={styles.functionIcon}>📊</Text>
          <Text style={styles.functionText}>AI 辅助计算评分与操作分析</Text>
        </View>
        <View style={styles.functionRow}>
          <Text style={styles.functionIcon}>💬</Text>
          <Text style={styles.functionText}>提供对局建议和复盘分析</Text>
        </View>
        <View style={styles.functionRow}>
          <Text style={styles.functionIcon}>🔊</Text>
          <Text style={styles.functionText}>配合语音播报进行任务提示</Text>
        </View>
      </View>
    </ScrollView>
  );
}

// ==================== 设置页 ====================
function SettingsPage({ store, state }: { store: AppStore; state: any }) {
  const voiceConfig = store.getVoiceConfig ? store.getVoiceConfig() : { enabled: state.voiceEnabled };
  return (
    <ScrollView contentContainerStyle={styles.settingsContainer}>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle}>⚙️ 偏好设置</Text>
      </View>

      {/* 语音 */}
      <Text style={styles.groupTitle}>🔊 语音播报</Text>
      <View style={styles.card}>
        <SwitchSetting
          icon="📢"
          title="启用语音播报"
          subtitle="评分变化和任务语音提示"
          value={state.voiceEnabled}
          onChange={(v) => {
            store.setVoiceEnabled(v);
            showToast(v ? '语音播报已开启' : '语音播报已关闭');
          }}
        />
      </View>

      {/* 悬浮窗 */}
      <Text style={styles.groupTitle}>🪟 悬浮窗</Text>
      <View style={styles.card}>
        <SwitchSetting
          icon="📺"
          title="启用悬浮窗"
          subtitle="在游戏上方显示小窗口"
          value={state.floatingWindowEnabled}
          onChange={async (v) => {
            if (v) {
              await store.startFloatingWindow();
              showToast('悬浮窗已启动');
            } else {
              await store.stopFloatingWindow();
              showToast('悬浮窗已关闭');
            }
          }}
        />
      </View>

      {/* 屏幕监测 */}
      <Text style={styles.groupTitle}>📡 屏幕监测</Text>
      <View style={styles.card}>
        <SwitchSetting
          icon="👁️"
          title="后台监测"
          subtitle="后台运行分析游戏画面"
          value={state.backgroundServiceRunning}
          onChange={async (v) => {
            if (v) {
              await store.startBackgroundService();
              showToast('后台监测已启动');
            } else {
              await store.stopBackgroundService();
              showToast('后台监测已停止');
            }
          }}
        />
        <View style={styles.cardDivider} />
        <SwitchSetting
          icon="🛡️"
          title="防检测模式"
          subtitle="降低被游戏反作弊系统识别的风险"
          value={state.antiDetectionEnabled}
          onChange={(v) => {
            store.setAntiDetectionEnabled(v);
            showToast(v ? '防检测已开启' : '防检测已关闭');
          }}
        />
      </View>

      {/* 信息 */}
      <Text style={styles.groupTitle}>ℹ️ 应用信息</Text>
      <View style={styles.card}>
        <Text style={styles.infoText}>LookGm v0.1.0</Text>
        <Text style={styles.infoTextSub}>游戏评分助手</Text>
      </View>

      {/* 清理数据 */}
      <TouchableOpacity
        style={styles.cleanButton}
        onPress={() => {
          Alert.alert('清理数据', '确定要清除所有本地配置数据吗？', [
            { text: '取消', style: 'cancel' },
            {
              text: '确定',
              style: 'destructive',
              onPress: async () => {
                await store.clearAllLocalData();
                showToast('数据已清理');
              },
            },
          ]);
        }}
      >
        <Text style={styles.cleanButtonText}>🗑️ 清理本地数据</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

function SwitchSetting({ icon, title, subtitle, value, onChange }: {
  icon: string; title: string; subtitle?: string; value: boolean; onChange: (v: boolean) => void;
}) {
  return (
    <View style={styles.switchSetting}>
      <View style={styles.switchSettingLeft}>
        <View style={styles.settingIconBox}>
          <Text style={styles.settingIcon}>{icon}</Text>
        </View>
        <View style={styles.switchSettingInfo}>
          <Text style={styles.switchSettingTitle}>{title}</Text>
          {subtitle && <Text style={styles.switchSettingSubtitle}>{subtitle}</Text>}
        </View>
      </View>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ true: COLORS.primary, false: COLORS.border }}
        thumbColor={COLORS.white}
      />
    </View>
  );
}

// ==================== 样式定义 ====================
const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: COLORS.background,
  },

  // 顶部栏
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: COLORS.white,
    borderBottomWidth: 1,
    borderBottomColor: COLORS.border,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: COLORS.text,
  },
  headerRight: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  badge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    marginLeft: 6,
  },
  badgeActive: {
    backgroundColor: COLORS.success,
  },
  badgeAI: {
    backgroundColor: COLORS.primarySoft,
  },
  badgeText: {
    color: COLORS.white,
    fontSize: 11,
    fontWeight: '700',
  },

  // 内容区
  content: {
    flex: 1,
  },
  contentContainer: {
    paddingBottom: 110,
    paddingHorizontal: 16,
    paddingTop: 16,
  },

  // 底部导航
  bottomNav: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    backgroundColor: COLORS.white,
    borderTopWidth: 1,
    borderTopColor: COLORS.border,
    paddingVertical: 8,
    paddingBottom: Platform.OS === 'ios' ? 28 : 12,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: -2 },
    shadowOpacity: 0.08,
    shadowRadius: 6,
    elevation: 4,
  },
  navItem: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 4,
  },
  navIcon: {
    fontSize: 22,
    marginBottom: 2,
    opacity: 0.5,
  },
  navIconActive: {
    opacity: 1,
  },
  navLabel: {
    fontSize: 11,
    color: COLORS.textMuted,
    fontWeight: '500',
  },
  navLabelActive: {
    color: COLORS.primary,
    fontWeight: '700',
  },

  // 启动页
  splashContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: COLORS.background,
  },
  splashEmoji: {
    fontSize: 72,
    marginBottom: 16,
  },
  splashTitle: {
    fontSize: 32,
    fontWeight: '800',
    color: COLORS.text,
    marginBottom: 8,
  },
  splashSubtitle: {
    fontSize: 16,
    color: COLORS.textSecondary,
  },

  // 登录页
  loginContainer: {
    flexGrow: 1,
    padding: 24,
    paddingTop: 40,
    backgroundColor: COLORS.background,
  },
  loginContent: {
    flex: 1,
  },
  logoBox: {
    alignSelf: 'center',
    width: 92,
    height: 92,
    borderRadius: 24,
    backgroundColor: COLORS.primarySoft,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 20,
  },
  logoEmoji: {
    fontSize: 48,
  },
  appTitle: {
    fontSize: 28,
    fontWeight: '800',
    color: COLORS.text,
    textAlign: 'center',
    marginBottom: 6,
    letterSpacing: 1,
  },
  appSubtitle: {
    fontSize: 14,
    color: COLORS.textSecondary,
    textAlign: 'center',
    marginBottom: 32,
  },
  loginCard: {
    backgroundColor: COLORS.white,
    borderRadius: 16,
    padding: 20,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 2,
  },
  cardLabel: {
    fontSize: 13,
    color: COLORS.textSecondary,
    marginBottom: 8,
    fontWeight: '600',
  },
  textInput: {
    backgroundColor: COLORS.background,
    borderRadius: 12,
    padding: 14,
    fontSize: 15,
    color: COLORS.text,
    borderWidth: 1,
    borderColor: COLORS.border,
    marginBottom: 14,
  },
  textInputLarge: {
    backgroundColor: COLORS.background,
    borderRadius: 12,
    padding: 14,
    fontSize: 15,
    color: COLORS.text,
    borderWidth: 1,
    borderColor: COLORS.border,
    marginBottom: 12,
  },
  primaryButton: {
    backgroundColor: COLORS.primary,
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 20,
    shadowColor: COLORS.primary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
    elevation: 4,
    flexDirection: 'row',
  },
  primaryButtonText: {
    color: COLORS.white,
    fontSize: 16,
    fontWeight: '700',
  },
  authTabContainer: {
    flexDirection: 'row',
    marginBottom: 20,
    backgroundColor: COLORS.background,
    borderRadius: 12,
    padding: 4,
  },
  authTab: {
    flex: 1,
    paddingVertical: 10,
    alignItems: 'center',
    borderRadius: 8,
  },
  authTabActive: {
    backgroundColor: COLORS.white,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1,
    shadowRadius: 2,
    elevation: 2,
  },
  authTabText: {
    fontSize: 14,
    color: COLORS.textSecondary,
    fontWeight: '600',
  },
  authTabTextActive: {
    color: COLORS.primary,
  },
  authError: {
    color: COLORS.danger,
    fontSize: 13,
    marginBottom: 12,
    paddingHorizontal: 4,
  },
  quickStartButton: {
    padding: 12,
    alignItems: 'center',
  },
  quickStartText: {
    fontSize: 13,
    color: COLORS.textSecondary,
    fontWeight: '500',
  },
  divider: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 14,
  },
  dividerLine: {
    flex: 1,
    height: 1,
    backgroundColor: COLORS.border,
  },
  dividerText: {
    color: COLORS.textMuted,
    paddingHorizontal: 12,
    fontSize: 12,
    fontWeight: '500',
  },
  socialButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 14,
    borderRadius: 12,
    marginBottom: 10,
    borderWidth: 1,
  },
  wechatButton: {
    backgroundColor: '#ECFDF5',
    borderColor: '#10B981',
  },
  qqButton: {
    backgroundColor: '#EFF6FF',
    borderColor: '#3B82F6',
  },
  socialIcon: {
    fontSize: 18,
    marginRight: 10,
  },
  socialButtonText: {
    color: COLORS.text,
    fontSize: 15,
    fontWeight: '600',
  },
  featuresText: {
    textAlign: 'center',
    color: COLORS.textMuted,
    fontSize: 12,
    marginTop: 24,
    lineHeight: 20,
  },

  // 首页
  homeContainer: {
    paddingTop: 0,
  },
  userCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: COLORS.white,
    borderRadius: 16,
    padding: 16,
    marginBottom: 12,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.06,
    shadowRadius: 4,
    elevation: 2,
  },
  userAvatar: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: COLORS.primarySoft,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  userAvatarText: {
    fontSize: 20,
    fontWeight: '700',
    color: COLORS.white,
  },
  userInfo: {
    flex: 1,
  },
  userName: {
    fontSize: 16,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 2,
  },
  userMeta: {
    fontSize: 13,
    color: COLORS.textSecondary,
  },
  userArrow: {
    fontSize: 24,
    color: COLORS.textMuted,
    fontWeight: '300',
  },

  // 状态卡片
  statusRow: {
    flexDirection: 'row',
    marginHorizontal: -4,
    marginBottom: 16,
  },
  statusCard: {
    flex: 1,
    backgroundColor: COLORS.white,
    borderRadius: 14,
    padding: 14,
    marginHorizontal: 4,
    alignItems: 'center',
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.06,
    shadowRadius: 4,
    elevation: 2,
  },
  statusIcon: {
    fontSize: 20,
    marginBottom: 6,
  },
  statusLabel: {
    fontSize: 12,
    color: COLORS.textSecondary,
    marginBottom: 4,
    fontWeight: '500',
  },
  statusValue: {
    fontSize: 12,
    color: COLORS.textMuted,
    fontWeight: '700',
  },

  section: {
    marginBottom: 20,
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 10,
    marginLeft: 4,
  },

  // 游戏卡片
  gameCard: {
    backgroundColor: COLORS.white,
    borderRadius: 12,
    paddingVertical: 10,
    paddingHorizontal: 18,
    marginRight: 10,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  gameCardActive: {
    backgroundColor: COLORS.primary,
    borderColor: COLORS.primary,
  },
  gameName: {
    fontSize: 14,
    fontWeight: '600',
    color: COLORS.text,
  },
  gameNameActive: {
    color: COLORS.white,
  },

  // 分路卡片
  rolesGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginHorizontal: -4,
    marginBottom: 12,
  },
  roleCard: {
    width: '31%',
    backgroundColor: COLORS.white,
    borderRadius: 12,
    padding: 14,
    margin: '1.16%',
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.04,
    shadowRadius: 3,
    elevation: 1,
  },
  roleIcon: {
    fontSize: 24,
    marginBottom: 6,
  },
  roleName: {
    fontSize: 13,
    fontWeight: '600',
    color: COLORS.text,
  },
  aiDetectButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: COLORS.primarySoft,
    padding: 14,
    borderRadius: 12,
    marginTop: 4,
  },
  aiDetectIcon: {
    fontSize: 18,
    marginRight: 8,
  },
  aiDetectText: {
    color: COLORS.primary,
    fontSize: 14,
    fontWeight: '700',
  },

  // 评分卡片
  scoreCard: {
    borderRadius: 16,
    padding: 20,
    marginBottom: 16,
  },
  scoreHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 14,
  },
  gradeText: {
    fontSize: 16,
    fontWeight: '800',
  },
  scoreValue: {
    fontSize: 42,
    fontWeight: '800',
    letterSpacing: -1,
  },
  dimensionsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    backgroundColor: COLORS.white,
    borderRadius: 12,
    padding: 12,
    marginBottom: 12,
  },
  dimensionBox: {
    alignItems: 'center',
    flex: 1,
  },
  dimensionValue: {
    fontSize: 16,
    fontWeight: '700',
  },
  dimensionLabel: {
    fontSize: 11,
    color: COLORS.textMuted,
    marginTop: 4,
    fontWeight: '500',
  },
  actionsBox: {
    backgroundColor: COLORS.white,
    borderRadius: 12,
    padding: 12,
    marginTop: 8,
  },
  actionsTitle: {
    fontSize: 12,
    fontWeight: '700',
    color: COLORS.success,
    marginBottom: 6,
  },
  actionsTitleNeg: {
    fontSize: 12,
    fontWeight: '700',
    color: COLORS.danger,
    marginBottom: 6,
  },
  actionText: {
    fontSize: 12,
    color: COLORS.text,
    marginBottom: 3,
  },
  actionTextNeg: {
    fontSize: 12,
    color: COLORS.text,
    marginBottom: 3,
  },

  // 计算评分按钮
  computeScoreButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: COLORS.primary,
    padding: 14,
    borderRadius: 12,
    marginBottom: 16,
    shadowColor: COLORS.primary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
    elevation: 3,
  },
  computeScoreIcon: {
    fontSize: 18,
    marginRight: 8,
  },
  computeScoreText: {
    color: COLORS.white,
    fontSize: 15,
    fontWeight: '700',
  },

  // 功能卡片
  featureCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: COLORS.white,
    borderRadius: 12,
    padding: 14,
    marginBottom: 8,
  },
  featureCardActive: {
    backgroundColor: COLORS.primarySoft,
    borderWidth: 1,
    borderColor: COLORS.primary,
  },
  featureLeft: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  featureIconBox: {
    width: 40,
    height: 40,
    borderRadius: 10,
    backgroundColor: COLORS.background,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  featureIcon: {
    fontSize: 20,
  },
  featureInfo: {
    flex: 1,
  },
  featureTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: COLORS.text,
    marginBottom: 2,
  },
  featureSubtitle: {
    fontSize: 12,
    color: COLORS.textSecondary,
  },

  // 帮助卡片
  helpCard: {
    backgroundColor: COLORS.white,
    borderRadius: 16,
    padding: 18,
    marginTop: 4,
    marginBottom: 12,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.05,
    shadowRadius: 3,
    elevation: 1,
  },
  helpTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 10,
  },
  helpText: {
    fontSize: 13,
    color: COLORS.textSecondary,
    marginBottom: 5,
    lineHeight: 20,
  },

  // 个人中心
  profileContainer: {
    paddingTop: 0,
  },
  profileHeader: {
    alignItems: 'center',
    paddingVertical: 28,
    backgroundColor: COLORS.white,
    borderRadius: 16,
    marginBottom: 16,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.06,
    shadowRadius: 4,
    elevation: 2,
  },
  profileAvatar: {
    width: 84,
    height: 84,
    borderRadius: 42,
    marginBottom: 12,
    overflow: 'hidden',
  },
  profileAvatarImage: {
    width: 84,
    height: 84,
    borderRadius: 42,
  },
  profileAvatarPlaceholder: {
    width: 84,
    height: 84,
    borderRadius: 42,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: COLORS.primary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
    elevation: 4,
  },
  profileAvatarText: {
    fontSize: 34,
    fontWeight: '800',
    color: COLORS.white,
  },
  profileName: {
    fontSize: 20,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 4,
  },
  profileMeta: {
    fontSize: 13,
    color: COLORS.textSecondary,
    marginBottom: 14,
  },
  editProfileBtn: {
    backgroundColor: COLORS.primarySoft,
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 12,
  },
  editProfileBtnText: {
    color: COLORS.primary,
    fontSize: 13,
    fontWeight: '700',
  },
  menuGroup: {
    backgroundColor: COLORS.white,
    borderRadius: 16,
    marginBottom: 16,
    overflow: 'hidden',
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.05,
    shadowRadius: 3,
    elevation: 1,
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 14,
    borderBottomWidth: 1,
    borderBottomColor: COLORS.divider,
  },
  menuIconBox: {
    width: 36,
    height: 36,
    borderRadius: 10,
    backgroundColor: COLORS.primarySoft,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  menuIcon: {
    fontSize: 18,
  },
  menuInfo: {
    flex: 1,
  },
  menuLabel: {
    fontSize: 15,
    color: COLORS.text,
    fontWeight: '500',
  },
  menuSubtitle: {
    fontSize: 12,
    color: COLORS.textMuted,
    marginTop: 2,
  },
  menuArrow: {
    fontSize: 24,
    color: COLORS.textMuted,
    fontWeight: '300',
  },
  dangerButton: {
    backgroundColor: '#FEF2F2',
    padding: 14,
    borderRadius: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#FECACA',
    marginBottom: 16,
  },
  dangerButtonText: {
    color: COLORS.danger,
    fontSize: 15,
    fontWeight: '700',
  },
  avatarUrlButton: {
    backgroundColor: COLORS.primarySoft,
    padding: 14,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 16,
  },
  avatarUrlButtonText: {
    color: COLORS.primary,
    fontSize: 15,
    fontWeight: '700',
  },
  avatarSectionLabel: {
    fontSize: 12,
    color: COLORS.textSecondary,
    marginBottom: 12,
    textAlign: 'center',
  },

  // Modal
  modalOverlay: {
    flex: 1,
    backgroundColor: COLORS.overlay,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  modalCard: {
    backgroundColor: COLORS.white,
    borderRadius: 16,
    padding: 20,
    width: '100%',
    maxWidth: 400,
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 16,
    textAlign: 'center',
  },
  modalButtonRow: {
    flexDirection: 'row',
    marginTop: 4,
    marginHorizontal: -4,
  },
  modalButton: {
    flex: 1,
    padding: 14,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginHorizontal: 4,
  },
  modalFullWidth: {
    marginTop: 12,
    marginHorizontal: 0,
  },
  modalButtonSecondary: {
    backgroundColor: COLORS.background,
  },
  modalButtonPrimary: {
    backgroundColor: COLORS.primary,
  },
  modalButtonSecondaryText: {
    color: COLORS.textSecondary,
    fontSize: 15,
    fontWeight: '600',
  },
  modalButtonPrimaryText: {
    color: COLORS.white,
    fontSize: 15,
    fontWeight: '600',
  },
  avatarColorGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginHorizontal: -6,
    marginBottom: 4,
  },
  avatarColorItem: {
    width: 52,
    height: 52,
    borderRadius: 26,
    margin: 6,
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 3,
    borderColor: 'transparent',
  },
  avatarColorItemActive: {
    borderColor: COLORS.white,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.4,
    shadowRadius: 4,
    elevation: 4,
  },
  avatarCheck: {
    color: COLORS.white,
    fontSize: 24,
    fontWeight: '800',
  },

  // AI 配置
  aiContainer: {
    paddingTop: 0,
  },
  pageHeader: {
    marginBottom: 16,
  },
  pageTitle: {
    fontSize: 20,
    fontWeight: '800',
    color: COLORS.text,
    marginBottom: 4,
  },
  pageSubtitle: {
    fontSize: 13,
    color: COLORS.textSecondary,
    lineHeight: 18,
  },
  inputCard: {
    backgroundColor: COLORS.white,
    borderRadius: 16,
    padding: 18,
    marginBottom: 20,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.05,
    shadowRadius: 4,
    elevation: 2,
  },
  inputLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: COLORS.textSecondary,
    marginBottom: 8,
    marginTop: 4,
  },
  apiKeyContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: COLORS.background,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: COLORS.border,
    marginBottom: 12,
  },
  apiKeyInput: {
    flex: 1,
    padding: 14,
    fontSize: 15,
    color: COLORS.text,
  },
  showHideButton: {
    padding: 14,
  },
  showHideText: {
    fontSize: 18,
  },
  modelSelector: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: COLORS.background,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 14,
    marginBottom: 8,
  },
  modelSelectorText: {
    fontSize: 15,
    color: COLORS.text,
    flex: 1,
  },
  modelSelectorPlaceholder: {
    color: COLORS.textMuted,
  },
  modelSelectorArrow: {
    fontSize: 12,
    color: COLORS.textSecondary,
    marginLeft: 8,
  },
  modelPickerContainer: {
    backgroundColor: COLORS.background,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: COLORS.border,
    marginBottom: 8,
    maxHeight: 200,
  },
  modelOption: {
    padding: 12,
    borderBottomWidth: 1,
    borderBottomColor: COLORS.divider,
  },
  modelOptionSelected: {
    backgroundColor: COLORS.primarySoft,
  },
  modelOptionText: {
    fontSize: 14,
    color: COLORS.text,
  },
  modelOptionTextSelected: {
    color: COLORS.primary,
    fontWeight: '600',
  },
  buttonRow: {
    flexDirection: 'row',
    marginHorizontal: -4,
    marginTop: 4,
  },
  saveButton: {
    flex: 1,
    backgroundColor: COLORS.secondary,
    padding: 14,
    borderRadius: 12,
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'center',
    marginHorizontal: 4,
  },
  testButton: {
    flex: 1,
    backgroundColor: COLORS.primary,
    padding: 14,
    borderRadius: 12,
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'center',
    marginHorizontal: 4,
  },
  testButtonDisabled: {
    backgroundColor: COLORS.textMuted,
  },
  testButtonText: {
    color: COLORS.white,
    fontSize: 15,
    fontWeight: '700',
  },
  testResultText: {
    marginTop: 12,
    fontSize: 13,
    color: COLORS.textSecondary,
    textAlign: 'center',
    fontWeight: '500',
  },
  testResultSuccess: {
    color: COLORS.success,
  },
  testResultError: {
    color: COLORS.danger,
  },
  providerCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: COLORS.white,
    padding: 14,
    borderRadius: 12,
    marginBottom: 8,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  providerCardActive: {
    borderColor: COLORS.primary,
    backgroundColor: COLORS.primarySoft,
  },
  providerLeft: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  providerIcon: {
    fontSize: 22,
    marginRight: 12,
  },
  providerInfo: {
    flex: 1,
  },
  providerNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  providerName: {
    fontSize: 15,
    fontWeight: '700',
    color: COLORS.text,
  },
  configuredTag: {
    backgroundColor: COLORS.primary,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 8,
    marginLeft: 8,
  },
  configuredTagText: {
    color: COLORS.white,
    fontSize: 10,
    fontWeight: '700',
  },
  providerDesc: {
    fontSize: 12,
    color: COLORS.textSecondary,
    marginTop: 2,
  },
  radioCircle: {
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 2,
    borderColor: COLORS.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  radioCircleActive: {
    borderColor: COLORS.primary,
  },
  radioDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: COLORS.primary,
  },
  aiFunctionsCard: {
    backgroundColor: COLORS.white,
    borderRadius: 16,
    padding: 18,
    marginBottom: 16,
  },
  functionsTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 10,
  },
  functionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 6,
  },
  functionIcon: {
    fontSize: 16,
    marginRight: 10,
  },
  functionText: {
    fontSize: 13,
    color: COLORS.textSecondary,
    lineHeight: 18,
  },

  // 设置
  settingsContainer: {
    paddingTop: 0,
  },
  groupTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: COLORS.textSecondary,
    marginTop: 8,
    marginBottom: 8,
    marginLeft: 4,
  },
  card: {
    backgroundColor: COLORS.white,
    borderRadius: 16,
    padding: 4,
    marginBottom: 16,
    shadowColor: COLORS.shadow,
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.04,
    shadowRadius: 3,
    elevation: 1,
  },
  cardDivider: {
    height: 1,
    backgroundColor: COLORS.divider,
    marginLeft: 68,
  },
  switchSetting: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 14,
  },
  switchSettingLeft: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  settingIconBox: {
    width: 38,
    height: 38,
    borderRadius: 10,
    backgroundColor: COLORS.primarySoft,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  settingIcon: {
    fontSize: 18,
  },
  switchSettingInfo: {
    flex: 1,
  },
  switchSettingTitle: {
    fontSize: 15,
    color: COLORS.text,
    fontWeight: '600',
  },
  switchSettingSubtitle: {
    fontSize: 12,
    color: COLORS.textMuted,
    marginTop: 2,
  },
  infoText: {
    fontSize: 15,
    color: COLORS.text,
    fontWeight: '600',
    padding: 16,
    textAlign: 'center',
  },
  infoTextSub: {
    fontSize: 12,
    color: COLORS.textMuted,
    textAlign: 'center',
    paddingBottom: 16,
  },
  cleanButton: {
    backgroundColor: '#FEF2F2',
    padding: 14,
    borderRadius: 12,
    alignItems: 'center',
    marginTop: 8,
    marginBottom: 24,
    borderWidth: 1,
    borderColor: '#FECACA',
  },
  cleanButtonText: {
    color: COLORS.danger,
    fontSize: 15,
    fontWeight: '700',
  },
});